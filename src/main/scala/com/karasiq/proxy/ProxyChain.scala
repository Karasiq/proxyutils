package com.karasiq.proxy

import java.net.InetSocketAddress

import akka.Done
import akka.actor.ActorSystem
import akka.stream.FlowShape
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Tcp}
import akka.util.ByteString
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.SocksVersion
import com.karasiq.proxy.client.{HttpProxyClientStage, SocksProxyClientStage}
import com.typesafe.config.{Config, ConfigException}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.language.{implicitConversions, postfixOps}
import scala.util.Random

object ProxyChain {
  @inline
  private def proxyFromString(s: String): Proxy = {
    Proxy(if (s.contains("://")) s else s"http://$s")
  }

  private def proxyStage(address: InetSocketAddress, proxy: Proxy): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[Done]] = {
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

  def connect(destination: InetSocketAddress, proxies: Proxy*)(implicit as: ActorSystem, ec: ExecutionContext): Flow[ByteString, ByteString, (Future[Tcp.OutgoingConnection], Future[Done])] = {
    val address = proxies.headOption.fold(destination)(_.toInetSocketAddress)
    createFlow(Tcp().outgoingConnection(address), destination, proxies:_*)
  }

  def createFlow[Mat](flow: Flow[ByteString, ByteString, Mat], destination: InetSocketAddress, proxies: Proxy*)(implicit ec: ExecutionContext): Flow[ByteString, ByteString, (Mat, Future[Done])] = {
    val flowWithDone = flow.mapMaterializedValue(_ → Future.successful(Done))
    if (proxies.isEmpty) flowWithDone
    else {
      @tailrec
      def connect(flow: Flow[ByteString, ByteString, (Mat, Future[Done])], proxies: Seq[Proxy]): Flow[ByteString, ByteString, (Mat, Future[Done])] = {
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
          val connectedFlow = Flow.fromGraph(GraphDSL.create(flow, proxyStage(address, proxy)) {
            case ((mat, ps1), ps2) ⇒
              mat → ps1.flatMap(_ ⇒ ps2)
          } { implicit b ⇒ (connection, stage) ⇒
            import GraphDSL.Implicits._
            connection.out ~> stage.in1
            stage.out1 ~> connection.in
            FlowShape(stage.in2, stage.out2)
          })
          connect(connectedFlow, proxies.tail)
        }
      }
      connect(flowWithDone, proxies)
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

  @throws[ConfigException]("if invalid config provided")
  def chainFromConfig(config: Config): Seq[Proxy] = {
    Seq(config.getConfig("entry"), config.getConfig("middle"), config.getConfig("exit")).flatMap(selectProxies)
  }
}