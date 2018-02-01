package com.github.karasiq.proxy.tests.client

import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import java.net.{InetSocketAddress, URI}
import java.security.SecureRandom

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.language.postfixOps

import akka.Done
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import akka.stream.scaladsl.{Flow, Keep, Source, Tcp}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.github.karasiq.proxy.tests.ActorSpec
import org.scalatest.FlatSpecLike
import org.scalatest.tags.Network

import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpMethod, HttpRequest}
import com.karasiq.proxy._
import com.karasiq.tls.TLSKeyStore

// You need to have running proxies to run this test
@Network
class ProxyChainTest extends ActorSpec with FlatSpecLike {
  import system.dispatcher

  "Stream connector" should "connect to HTTP proxy" in {
    val Some(proxy) = Settings.testProxies.find(_.scheme == "http")
    readFrom(ProxyChain.connect(Settings.testHost, Seq(proxy)))
  }

  it should "connect to SOCKS proxy" in {
    val Some(proxy) = Settings.testProxies.find(_.scheme == "socks")
    readFrom(ProxyChain.connect(Settings.testHost, Seq(proxy)))
  }

  it should "connect to TLS-SOCKS proxy" in {
    val Some(proxy) = Settings.testProxies.find(_.scheme == "tls-socks")
    readFrom(ProxyChain.connect(Settings.testHost, Seq(proxy), Some(Settings.tlsContext)))
  }

  it should "connect through proxy chain" in {
    readFrom(ProxyChain.connect(Settings.testHost, Settings.testProxies, Some(Settings.tlsContext)))
  }

  private[this] object Settings {
    private[this] val config = system.settings.config.getConfig("karasiq.proxy-chain-test")

    val (testHost, testUrl) = {
      val uri = new URI(config.getString("url"))
      (InetSocketAddress.createUnresolved(uri.getHost, Some(uri.getPort).filter(_ != -1).getOrElse(80)), uri.getPath)
    }

    val testProxies: List[Proxy] = {
      import com.karasiq.networkutils.uri._

      config.getStringList("proxies").asScala
        .map(url ⇒ Proxy(url))
        .toList
    }

    lazy val tlsContext: HttpsConnectionContext = {
      val keyStore = TLSKeyStore()
      val sslContext = SSLContext.getInstance("TLS")
      val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
      keyManagerFactory.init(keyStore.keyStore, keyStore.password.toCharArray)

      val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
      trustManagerFactory.init(keyStore.keyStore)
      sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, SecureRandom.getInstanceStrong)

      ConnectionContext.https(sslContext)
    }

    val okStatus = config.getString("ok-status")
  }

  private[this] def checkResponse(bytes: ByteString): Unit = {
    val response = bytes.utf8String
    println(response)
    assert(response.startsWith(Settings.okStatus))
  }

  private[this] def readFrom(flow: Flow[ByteString, ByteString, (Future[Tcp.OutgoingConnection], Future[Done])]): Unit = {
    val request = HttpRequest((HttpMethod.GET, Settings.testUrl, Seq(HttpHeader("Host" → Settings.testHost.getHostString))))
    val probe = Source.single(request)
      .viaMat(flow)(Keep.right)
      .runWith(TestSink.probe)

    checkResponse(probe.requestNext())
    probe.cancel()
  }
}
