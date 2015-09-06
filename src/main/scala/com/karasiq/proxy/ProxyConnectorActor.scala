package com.karasiq.proxy

import java.net.InetSocketAddress

import akka.actor.SupervisorStrategy.Stop
import akka.actor._
import akka.io.Tcp._
import akka.io.{IO, Tcp}
import akka.util.{ByteString, Timeout}
import com.karasiq.networkutils.http.headers.{HttpHeader, `Proxy-Authorization`}
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpConnect, HttpResponse}
import com.karasiq.parsers.socks.SocksClient._
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, AuthStatusResponse, Codes, ConnectionStatusResponse}

import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.control

case class EnableStreaming(handler: ActorRef)
case class ConnectThroughProxy(proxy: Proxy, address: InetSocketAddress)
case class ConnectedThroughProxy(proxy: Proxy, address: InetSocketAddress)
case class ProxyConnectionFailed(error: Throwable)

/**
 * @todo TLS proxy
 */
class ProxyConnectorActor extends Actor with ActorLogging {
  import context.system
  private implicit val timeout = Timeout(1 minute)

  private implicit def byteSeqToByteString(bytes: Seq[Byte]): ByteString = bytes match {
    case bs: ByteString ⇒
      bs // Cast

    case seq ⇒
      ByteString(seq.toArray) // Convert
  }

  private def userInfoAsLoginPassword(proxy: Proxy) = proxy.userInfo.map(_.split(":", 2).toList) match {
    case Some(userName :: password :: Nil) ⇒
      userName → password

    case _ ⇒
      "" → ""
  }

  private def writeRead(connection: ActorRef, data: ByteString, handler: ActorRef)(onSuccess: Receive): Unit = {
    connection ! Write(data)
    connection ! ResumeReading
    context.become(onSuccess.orElse {
      case _: CommandFailed | _: ConnectionClosed ⇒
        wrapException(handler)(throw new ProxyException("Server communication error"))

      case Received(bs) ⇒
        wrapException(handler)(throw new ProxyException(s"Invalid response from server: $bs"))
    })
  }

  protected def socks5Auth(proxy: Proxy, connection: ActorRef, authMethod: AuthMethod, handler: ActorRef)(onSuccess: ⇒ Unit): Unit = authMethod match {
    case AuthMethod.NoAuth ⇒
      onSuccess

    case AuthMethod.UsernamePassword if proxy.userInfo.isDefined ⇒
      val (userName, password) = userInfoAsLoginPassword(proxy)
      writeRead(connection, UsernameAuthRequest((userName, password)), handler) {
        case Received(AuthStatusResponse(0x00)) ⇒
          onSuccess

        case Received(AuthStatusResponse(_)) ⇒
          wrapException(handler)(throw new ProxyException("SOCKS authentication rejected"))
      }
  }

  protected def connectSocks(connection: ActorRef, proxy: Proxy, address: InetSocketAddress, socksVersion: SocksVersion, handler: ActorRef): Unit = {
    socksVersion match {
      case SocksVersion.SocksV5 ⇒
        writeRead(connection, AuthRequest(Seq(AuthMethod.NoAuth)), handler) {
          case Received(AuthMethodResponse(authMethod)) ⇒
            socks5Auth(proxy, connection, authMethod, handler)(writeRead(connection, ConnectionRequest((SocksVersion.SocksV5, Command.TcpConnection, address, "")), handler) {
              case Received(ConnectionStatusResponse((SocksVersion.SocksV5, localAddress, status))) ⇒
                if(status != Codes.Socks5.REQUEST_GRANTED) wrapException(handler)(throw new ProxyException(s"SOCKS request rejected: $status"))
                becomeConnected(connection)
                log.debug("Connected through {} to {}", proxy, address)
                handler ! ConnectedThroughProxy(proxy, address)
            })
        }

      case SocksVersion.SocksV4 ⇒
        writeRead(connection, ConnectionRequest((SocksVersion.SocksV4, Command.TcpConnection, address, userInfoAsLoginPassword(proxy)._1)), handler) {
          case Received(ConnectionStatusResponse((SocksVersion.SocksV4, _, status))) ⇒
            if(status != Codes.Socks4.REQUEST_GRANTED) wrapException(handler)(throw new ProxyException(s"SOCKS request rejected: $status"))
            becomeConnected(connection)
            log.debug("Connected through {} to {}", proxy, address)
            handler ! ConnectedThroughProxy(proxy, address)
        }
    }
  }

  protected def connectHttp(connection: ActorRef, proxy: Proxy, address: InetSocketAddress, handler: ActorRef): Unit = {
    val auth: Seq[HttpHeader] = proxy.userInfo.map(userInfo ⇒ `Proxy-Authorization`.basic(userInfo)).toSeq
    writeRead(connection, HttpConnect(address, auth), handler) {
      case Received(HttpResponse((status, headers))) if status.code == 200 ⇒
        becomeConnected(connection)
        log.debug("Connected through {} to {}", proxy, address)
        handler ! ConnectedThroughProxy(proxy, address)

      case Received(HttpResponse((status, _))) ⇒
        wrapException(handler)(throw new ProxyException(s"HTTP CONNECT failed: ${status.code} ${status.message}"))
    }
  }

  private def connectProxy(connection: ActorRef, proxy: Proxy, address: InetSocketAddress, handler: ActorRef): Unit = {
    log.debug("Connecting through proxy {} to {}", proxy, address)
    proxy.scheme match {
      case "socks4" ⇒
        connectSocks(connection, proxy, address, SocksVersion.SocksV4, handler)
      case "socks5" | "socks" ⇒
        connectSocks(connection, proxy, address, SocksVersion.SocksV5, handler)
      case "http" | "https" | "" ⇒
        connectHttp(connection, proxy, address, handler)
      case _ ⇒ throw new ProxyException("Invalid proxy scheme: " + proxy.scheme)
    }
  }

  private def connectTo(proxy: Proxy, destination: InetSocketAddress, handler: ActorRef): Unit = {
    val address = proxy.toInetSocketAddress
    IO(Tcp) ! Connect(address)
    context.become {
      case Connected(remote, local) ⇒ // Connection established
        val connection = sender()
        connection ! Register(self)
        log.debug("Connected to: {}", connection)
        connectProxy(connection, proxy, destination, handler)

      case _: CommandFailed | _: ConnectionClosed ⇒ // Connection failed
        val exc = new ProxyException("Connection failed")
        handler ! ProxyConnectionFailed(exc)
        log.error("Connection to {} failed", proxy)
        context.stop(self)
    }
  }

  private def wrapException(hander: ActorRef) = control.Exception.catching(classOf[ProxyException]).withApply { exc ⇒
    log.error(exc, "Proxy connector error")
    hander ! ProxyConnectionFailed(exc)
    context.stop(self)
  }

  private def onClose: Receive = {
    case cc: ConnectionClosed ⇒
      log.warning("Connection closed: {}", cc)
      context.stop(self)

    case cf: CommandFailed ⇒
      log.warning("Command failed: {}", cf)
      context.stop(self)
  }

  private def becomeConnected(connection: ActorRef): Unit = {
    connection ! SuspendReading
    context.become(onClose.orElse {
      case ConnectThroughProxy(proxy, address) ⇒
        connectProxy(connection, proxy, address, sender())

      case EnableStreaming(handler) ⇒
        connection ! ResumeReading
        context.become {
          case cmd: Tcp.Command ⇒
            connection.forward(cmd)

          case closed: ConnectionClosed ⇒
            handler.forward(closed)
            context.stop(self)

          case ev: Tcp.Event ⇒
            handler.forward(ev)
        }
    })
  }

  override def receive: Receive = {
    case ConnectThroughProxy(proxy, address) ⇒
      connectTo(proxy, address, sender())
  }

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _: ProxyException ⇒ Stop
  }
}
