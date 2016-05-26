package com.karasiq.proxy

import java.net.InetSocketAddress

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.FlowShape
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Tcp}
import akka.util.ByteString
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.SocksVersion
import com.karasiq.proxy.client.{HttpProxyClientStage, SocksProxyClientStage}
import com.typesafe.config.Config

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.language.{implicitConversions, postfixOps}
import scala.util.Random

object ProxyChain {
  @inline
  private def proxyFromString(s: String): Proxy = {
    Proxy(if (s.contains("://")) s else s"http://$s")
  }

  private def proxyStage(address: InetSocketAddress, proxy: Proxy): BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
    BidiFlow.fromGraph(proxy.scheme.toLowerCase match {
      case "socks4" | "socks4a" ⇒
        new SocksProxyClientStage(address, SocksVersion.SocksV4, Some(proxy))

      case "socks5" | "socks" ⇒
        new SocksProxyClientStage(address, SocksVersion.SocksV5, Some(proxy))

      case "http" | "https" ⇒
        new HttpProxyClientStage(address, Some(proxy))

      case scheme ⇒
        throw new IllegalArgumentException(s"Unknown proxy protocol: $scheme")
    })
  }

  def connect(destination: InetSocketAddress, proxies: Proxy*)(implicit as: ActorSystem): Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] = {
    val address = proxies.headOption.fold(destination)(_.toInetSocketAddress)
    createFlow(Tcp().outgoingConnection(address), destination, proxies:_*)
  }

  def createFlow[Mat](flow: Flow[ByteString, ByteString, Mat], destination: InetSocketAddress, proxies: Proxy*): Flow[ByteString, ByteString, Mat] = {
    if (proxies.isEmpty) flow
    else {
      @tailrec
      def connect(flow: Flow[ByteString, ByteString, Mat], proxies: Seq[Proxy]): Flow[ByteString, ByteString, Mat] = {
        if (proxies.isEmpty) {
          flow
        } else {
          val (proxy, address) = proxies match {
            case Seq(p1, p2, _*) ⇒
              p1 → p2.toInetSocketAddress

            case Seq(p) ⇒
              p → destination

            case _ ⇒
              throw new IllegalArgumentException
          }
          val connectedFlow = Flow.fromGraph(GraphDSL.create(flow) { implicit b ⇒ connection ⇒
            import GraphDSL.Implicits._
            val stage = b.add(proxyStage(address, proxy))
            connection.out ~> stage.in1
            stage.out1 ~> connection.in
            FlowShape(stage.in2, stage.out2)
          })
          connect(connectedFlow, proxies.tail)
        }
      }
      connect(flow, proxies)
    }
  }

  def createChain(proxies: Seq[Proxy], randomize: Boolean = false, hops: Int = 0): Seq[Proxy] = {
    val ordered = if (randomize) Random.shuffle(proxies) else proxies
    if (hops == 0) ordered else ordered.take(hops)
  }

  private def selectProxies(config: Config): Seq[Proxy] = {
    import scala.collection.JavaConversions._
    val proxies: IndexedSeq[String] = config.getStringList("proxies").toIndexedSeq
    createChain(proxies.map(proxyFromString), config.getBoolean("randomize"), config.getInt("hops"))
  }

  @throws[IllegalArgumentException]("if invalid config provided")
  final def fromConfig(flow: Flow[ByteString, ByteString, NotUsed], destination: InetSocketAddress, config: Config): Flow[ByteString, ByteString, NotUsed] = {
    val chain: Seq[Proxy] = Seq(config.getConfig("entry"), config.getConfig("middle"), config.getConfig("exit")).flatMap(selectProxies)
    createFlow(flow, destination, chain: _*)
  }
}