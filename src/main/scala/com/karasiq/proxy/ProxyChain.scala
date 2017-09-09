package com.karasiq.proxy

import java.net.InetSocketAddress

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Random

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.HttpsConnectionContext
import akka.io.Tcp.SO
import akka.stream.{BidiShape, FlowShape, TLSClosing, TLSRole}
import akka.stream.TLSProtocol.{SendBytes, SessionBytes, SslTlsInbound}
import akka.stream.scaladsl.{BidiFlow, Flow, GraphDSL, Keep, Tcp, TLS}
import akka.util.ByteString
import com.typesafe.config.{Config, ConfigException}

import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.SocksVersion
import com.karasiq.proxy.client.{HttpProxyClientStage, SocksProxyClientStage}

object ProxyChain {
  private[proxy] val TlsPrefix = "tls-"

  private[proxy] def proxyStage(address: InetSocketAddress, proxy: Proxy,
                         tlsContext: Option[HttpsConnectionContext] = None)
                        (implicit as: ActorSystem): BidiFlow[ByteString, ByteString, ByteString, ByteString, Future[Done]] = {
    val log = Logging(as, "ProxyStage")
    def stageForScheme(scheme: String) = {
      scheme.toLowerCase match {
        case "socks4" | "socks4a" ⇒
          new SocksProxyClientStage(log, address, SocksVersion.SocksV4, Some(proxy))

        case "socks5" | "socks" ⇒
          new SocksProxyClientStage(log, address, SocksVersion.SocksV5, Some(proxy))

        case "http" | "https" ⇒
          new HttpProxyClientStage(log, address, Some(proxy))

        case _ ⇒
          throw new IllegalArgumentException(s"Unknown proxy protocol: $scheme")
      }
    }

    val graph = {
      if (proxy.scheme.startsWith(TlsPrefix) && tlsContext.nonEmpty) {
        val tls = TLS(tlsContext.get.sslContext, tlsContext.get.firstSession, TLSRole.client, TLSClosing.ignoreComplete, Some(proxy.host → proxy.port))
        BidiFlow.fromGraph(GraphDSL.create(stageForScheme(proxy.scheme.split(TlsPrefix, 2).last), tls)(Keep.left) { implicit builder ⇒ (connection, tls) ⇒
          import GraphDSL.Implicits._
          val bytesIn = builder.add(Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes })
          val bytesOut = builder.add(Flow[ByteString].map(SendBytes))
          connection.out1 ~> bytesOut ~> tls.in1
          tls.out2 ~> bytesIn ~> connection.in1
          BidiShape(tls.in2, tls.out1, connection.in2, connection.out2)
        })
      } else {
        stageForScheme(proxy.scheme)
      }
    }

    BidiFlow.fromGraph(graph).named("proxyStage")
  }

  def connect(destination: InetSocketAddress, proxies: Seq[Proxy],
              tlsContext: Option[HttpsConnectionContext] = None)
             (implicit as: ActorSystem, ec: ExecutionContext): Flow[ByteString, ByteString, (Future[Tcp.OutgoingConnection], Future[Done])] = {
    val address = proxies.headOption.fold(destination)(_.toInetSocketAddress)
    val connection = Tcp().outgoingConnection(address, options = List(SO.KeepAlive(true), SO.TcpNoDelay(true)),
      connectTimeout = 15 seconds, idleTimeout = 5 minutes)
    createFlow(connection, destination, proxies, tlsContext)
  }

  def createFlow[Mat](flow: Flow[ByteString, ByteString, Mat],
                      destination: InetSocketAddress, proxies: Seq[Proxy],
                      tlsContext: Option[HttpsConnectionContext] = None)
                     (implicit as: ActorSystem, ec: ExecutionContext): Flow[ByteString, ByteString, (Mat, Future[Done])] = {
    val flowWithDone = flow.mapMaterializedValue(_ → Future.successful(Done))
    if (proxies.isEmpty) {
      flowWithDone
    } else {
      @tailrec
      def connect(currentFlow: Flow[ByteString, ByteString, (Mat, Future[Done])], proxies: Seq[Proxy]): Flow[ByteString, ByteString, (Mat, Future[Done])] = {
        if (proxies.isEmpty) {
          currentFlow
        } else {
          val (proxy, address) = proxies match {
            case currentProxy +: nextProxy +: _ ⇒
              currentProxy → nextProxy.toInetSocketAddress

            case currentProxy +: Nil ⇒
              currentProxy → destination

            case _ ⇒
              throw new IllegalArgumentException(s"Invalid proxy chain: $proxies")
          }
          val connectedFlow = Flow.fromGraph(GraphDSL.create(currentFlow, proxyStage(address, proxy, tlsContext)) {
            case ((mat, ps1), ps2) ⇒ mat → ps1.flatMap(_ ⇒ ps2)
          } { implicit b ⇒ (connection, stage) ⇒
            import GraphDSL.Implicits._
            connection.out ~> stage.in1
            stage.out1 ~> connection.in
            FlowShape(stage.in2, stage.out2)
          })
          connect(connectedFlow, proxies.tail)
        }
      }
      connect(flowWithDone, proxies).named("proxyConnection")
    }
  }

  def select(proxies: Seq[Proxy], randomize: Boolean = false, hops: Int = 0): Seq[Proxy] = {
    val ordered = if (randomize) Random.shuffle(proxies) else proxies
    if (hops == 0) ordered else ordered.take(hops)
  }

  private[proxy] def configSelect(config: Config): Seq[Proxy] = {
    val proxies = config.getStringList("proxies").asScala
    select(proxies.map(s ⇒ Proxy(if (s.contains("://")) s else s"http://$s")), config.getBoolean("randomize"), config.getInt("hops"))
  }

  @throws[ConfigException]("if invalid config provided")
  def fromConfig(config: Config): Seq[Proxy] = {
    val configs = Seq(config.getConfig("entry"), config.getConfig("middle"), config.getConfig("exit"))
    configs.flatMap(configSelect)
  }
}