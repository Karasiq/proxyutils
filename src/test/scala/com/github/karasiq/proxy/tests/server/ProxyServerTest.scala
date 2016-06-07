package com.github.karasiq.proxy.tests.server

import java.net.InetSocketAddress

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import com.karasiq.networkutils.http.HttpStatus
import com.karasiq.parsers.http.{HttpMethod, HttpRequest, HttpResponse}
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
    val testIn = ByteString("Test bytes sent to server")
    val testOut = ByteString("Test bytes sent to client")
    val (future, serverProbe) = Source(Vector(request, testIn))
      .viaMat(ProxyServer())(Keep.right)
      .toMat(TestSink.probe)(Keep.both)
      .run()

    val (parsedRequest, flow) = Await.result(future, 10 seconds)
    parsedRequest shouldBe expect
    val (_, clientProbe) = flow.runWith(Source.single(testOut), TestSink.probe)

    clientProbe.requestNext(testIn)
    serverProbe.requestNext(testOut)
  }

  "Proxy server" should "accept HTTP CONNECT" in {
    testServer(HttpRequest((HttpMethod.CONNECT, "http://example.com", Nil)), ProxyConnectionRequest("http", InetSocketAddress.createUnresolved("example.com", 80)))
  }

  it should "fail on plain HTTP" in {
    val expectedAnswer = HttpResponse(HttpStatus(400, "Bad Request"), Nil) ++ ByteString("Request not supported")
    val (future, probe) = Source.single(HttpRequest((HttpMethod.GET, "/", Nil)))
      .viaMat(ProxyServer())(Keep.right)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    probe.requestNext(expectedAnswer)
    intercept[ProxyException](Await.result(future, 10 seconds))
  }

  it should "accept SOCKS5" in {
    testServer(ConnectionRequest((SocksV5, SocksClient.Command.TcpConnection, InetSocketAddress.createUnresolved("example.com", 80), "")), ProxyConnectionRequest("socks", InetSocketAddress.createUnresolved("example.com", 80)))
  }

  it should "accept SOCKS4" in {
    testServer(ConnectionRequest((SocksV4, SocksClient.Command.TcpConnection, InetSocketAddress.createUnresolved("example.com", 80), "")), ProxyConnectionRequest("socks4", InetSocketAddress.createUnresolved("example.com", 80)))
  }
}
