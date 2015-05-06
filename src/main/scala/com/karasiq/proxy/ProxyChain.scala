package com.karasiq.proxy

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.SocketChannel

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.Timeout
import com.karasiq.networkutils.proxy.Proxy
import com.typesafe.config.Config
import org.apache.commons.io.IOUtils

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.language.{implicitConversions, postfixOps}
import scala.util.Random
import scala.util.control.Exception

object ProxyChain {
  private[proxy] def resolved(p: Proxy): InetSocketAddress = p.toInetSocketAddress

  private[proxy] def unresolved(p: Proxy): InetSocketAddress = InetSocketAddress.createUnresolved(p.host, p.port)

  private def proxyFromString(s: String): Proxy = {
    Proxy(if (s.contains("://")) s else s"http://$s")
  }

  def apply(proxies: Proxy*): ProxyChain = {
    if (proxies.isEmpty) new EmptyProxyChain
    else new ProxyChainImpl(proxies)
  }

  def chainFrom(proxies: Seq[Proxy], randomize: Boolean = false, hops: Int = 0): Seq[Proxy] = {
    val ordered = if (randomize) Random.shuffle(proxies) else proxies
    if (hops == 0) ordered else ordered.take(hops)
  }

  private def selectProxies(config: Config): Seq[Proxy] = {
    import scala.collection.JavaConversions._
    val proxies: IndexedSeq[String] = config.getStringList("proxies").toIndexedSeq
    chainFrom(proxies.map(proxyFromString), config.getBoolean("randomize"), config.getInt("hops"))
  }

  @throws[IllegalArgumentException]("if invalid config provided")
  def config(config: Config): ProxyChain = {
    val chain: Seq[Proxy] = Seq(config.getConfig("entry"), config.getConfig("middle"), config.getConfig("exit")).flatMap(selectProxies)
    apply(chain: _*)
  }
}

abstract class ProxyChain {
  /**
   * Creates connection through proxy chain
   * @param address Destination address
   * @return Opened connection
   */
  @throws[ProxyException]("if connection failed")
  def connection(address: InetSocketAddress): SocketChannel

  def connectActor(actorSystem: ActorSystem, address: InetSocketAddress): Future[ActorRef]

  def proxies: Seq[Proxy]

  override def equals(obj: scala.Any): Boolean = obj match {
    case pc: ProxyChain ⇒ pc.proxies == this.proxies
    case _ ⇒ false
  }

  override def hashCode(): Int = {
    proxies.hashCode()
  }

  override def toString: String = {
    s"ProxyChain(${proxies.mkString("[", " -> ", "]")})"
  }
}

sealed private class EmptyProxyChain extends ProxyChain {
  /**
   * Creates direct connection
   * @param address Destination address
   * @return Opened connection
   */
  override def connection(address: InetSocketAddress): SocketChannel = {
    SocketChannel.open(new InetSocketAddress(InetAddress.getByName(address.getHostString), address.getPort))
  }


  override def connectActor(actorSystem: ActorSystem, address: InetSocketAddress): Future[ActorRef] = {
    Future.successful(actorSystem.actorOf(Props(new Actor {
      @throws[Exception](classOf[Exception])
      override def preStart(): Unit = {
        super.preStart()
        IO(Tcp)(actorSystem) ! Connect(address)
      }

      override def receive: Receive = {
        case Connected(remote, local) ⇒
          val connection = sender()
          connection ! Register(self)
          connection ! SuspendReading
          context.become {
            case EnableStreaming(handler) ⇒
              connection ! ResumeReading
              context.become {
                case close: ConnectionClosed ⇒
                  handler.forward(close)
                  context.stop(self)

                case ev: Tcp.Event ⇒
                  handler.forward(ev)

                case cmd: Tcp.Command ⇒
                  connection.forward(cmd)
              }
          }
      }
    })))
  }

  override def proxies: Seq[Proxy] = Nil
}

@throws[IllegalArgumentException]("if proxy chain is empty")
sealed private class ProxyChainImpl(val proxies: Seq[Proxy]) extends ProxyChain {

  import ProxyChain._

  if (proxies.isEmpty) throw new IllegalArgumentException("Proxy chain shouldn't be empty")

  @inline
  private def connect(socket: SocketChannel, proxy: Proxy, destination: InetSocketAddress): Unit = {
    ProxyConnector(proxy).connect(socket, destination)
  }

  private def tryConnect(socket: SocketChannel, proxy: Proxy, address: InetSocketAddress): SocketChannel = {
    val catcher = Exception.nonFatalCatch.withApply { exc ⇒
      IOUtils.closeQuietly(socket)
      throw new ProxyException(s"Connect through $proxy to $address failed", exc)
    }

    catcher {
      connect(socket, proxy, address)
      socket
    }
  }

  /**
   * Creates connection through proxy chain
   * @param address Destination address
   * @return Opened connection
   */
  @throws[ProxyException]("if connection failed")
  def connection(address: InetSocketAddress): SocketChannel = {
    @tailrec
    def proxyConnect(socket: SocketChannel, proxies: List[Proxy], address: InetSocketAddress): SocketChannel = {
      proxies match {
        case proxy :: Nil ⇒
          // Last proxy reached, connect to destination address
          tryConnect(socket, proxy, address)

        case proxy :: (tail @ (connectTo :: _)) ⇒
          val sc = tryConnect(socket, proxy, unresolved(connectTo))
          proxyConnect(sc, tail, address)

        case Nil ⇒
          socket
      }
    }

    val socketChannel: SocketChannel = {
      val sc = SocketChannel.open(resolved(proxies.head))
      val socket = sc.socket()
      socket.setKeepAlive(true)
      socket.setSoTimeout(60000)
      sc
    }

    proxyConnect(socketChannel, proxies.toList, address)
  }

  override def connectActor(actorSystem: ActorSystem, address: InetSocketAddress): Future[ActorRef] = {
    import actorSystem.dispatcher
    import akka.pattern.ask

    import scala.concurrent.duration._

    implicit val timeout = Timeout(1 minute)

    def proxyConnect(actor: ActorRef, proxies: List[Proxy], address: InetSocketAddress): Future[ActorRef] = {
      proxies match {
        case proxy :: Nil ⇒
          // Last proxy reached, connect to destination address
          actor ? ConnectThroughProxy(proxy, address) map {
            case _ ⇒
              actor
          }

        case proxy :: (tail @ (connectTo :: _)) ⇒
          actor ? ConnectThroughProxy(proxy, unresolved(connectTo)) flatMap {
            case ConnectedThroughProxy(_, _) ⇒
              proxyConnect(actor, tail, address)

            case ProxyConnectionFailed(exc) ⇒
              Future.failed(exc)
          }

        case Nil ⇒
          throw new IllegalArgumentException
      }
    }

    val proxyConnector = actorSystem.actorOf(Props[ProxyConnectorActor])
    proxyConnect(proxyConnector, proxies.toList, address)
  }
}
