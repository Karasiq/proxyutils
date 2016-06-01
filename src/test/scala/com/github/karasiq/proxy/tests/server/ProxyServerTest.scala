package com.github.karasiq.proxy.tests.server

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.ByteString
import com.karasiq.parsers.http.{HttpMethod, HttpRequest}
import com.karasiq.parsers.socks.SocksClient
import com.karasiq.parsers.socks.SocksClient.ConnectionRequest
import com.karasiq.parsers.socks.SocksClient.SocksVersion.{SocksV4, SocksV5}
import com.karasiq.proxy.ProxyException
import com.karasiq.proxy.server.{ProxyConnectionRequest, ProxyServer}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class ProxyServerTest extends FlatSpec with Matchers with BeforeAndAfterAll {
  implicit val actorSystem = ActorSystem("proxy-server-test")
  implicit val materializer = ActorMaterializer.create(actorSystem)

  override protected def afterAll() = {
    actorSystem.terminate()
    super.afterAll()
  }

  def testServer(request: ByteString, expect: ProxyConnectionRequest): Unit = {
    val future = Source.single(request)
      .viaMat(ProxyServer())(Keep.right)
      .to(Sink.ignore)
      .run()
    Await.result(future, 10 seconds)._1 shouldBe expect
  }

  "Proxy server" should "accept HTTP CONNECT" in {
    testServer(HttpRequest((HttpMethod.CONNECT, "http://example.com", Nil)), ProxyConnectionRequest("http", InetSocketAddress.createUnresolved("example.com", 80)))
  }

  it should "fail on plain HTTP" in {
    intercept[ProxyException](testServer(HttpRequest((HttpMethod.GET, "/", Nil)), null))
  }

  it should "accept SOCKS5" in {
    testServer(ConnectionRequest((SocksV5, SocksClient.Command.TcpConnection, InetSocketAddress.createUnresolved("example.com", 80), "")), ProxyConnectionRequest("socks", InetSocketAddress.createUnresolved("example.com", 80)))
  }

  it should "accept SOCKS4" in {
    testServer(ConnectionRequest((SocksV4, SocksClient.Command.TcpConnection, InetSocketAddress.createUnresolved("example.com", 80), "")), ProxyConnectionRequest("socks4", InetSocketAddress.createUnresolved("example.com", 80)))
  }
}
