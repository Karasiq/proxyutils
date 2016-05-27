import java.net.{InetSocketAddress, URI}

import akka.Done
import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Source, Tcp}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.{ByteString, Timeout}
import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpMethod, HttpRequest}
import com.karasiq.proxy._
import com.typesafe.config.ConfigFactory
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

// You need to have running proxies to run this test
class ProxyChainTest extends FlatSpec with Matchers {
  implicit val timeout = Timeout(1 minute)
  implicit val actorSystem = ActorSystem("proxy-chain-test")
  implicit val materializer = ActorMaterializer.create(actorSystem)
  import actorSystem.dispatcher

  val config = ConfigFactory.load().getConfig("karasiq.proxy-chain-test")

  val (testHost, testUrl) = {
    val uri = new URI(config.getString("url"))
    (InetSocketAddress.createUnresolved(uri.getHost, Some(uri.getPort).filter(_ != -1).getOrElse(80)), uri.getPath)
  }

  val testProxies: List[Proxy] = {
    import com.karasiq.networkutils.uri._

    import scala.collection.JavaConversions._

    config.getStringList("proxies").toList
      .map(url ⇒ Proxy(url))
  }

  private def checkResponse(bytes: ByteString): Unit = {
    val response = bytes.utf8String
    println(response)
    assert(response.startsWith(config.getString("ok-status")))
  }

  private def readFrom(flow: Flow[ByteString, ByteString, (Future[Tcp.OutgoingConnection], Future[Done])]): Unit = {
    val request = HttpRequest((HttpMethod.GET, testUrl, Seq(HttpHeader("Host" → testHost.getHostString))))
    val ((connection, proxyConnection), probe) = Source
      .single(request)
      .viaMat(flow)(Keep.right)
      .toMat(TestSink.probe)(Keep.both)
      .run()
    // println(Await.result(connection, Duration.Inf))
    // println(Await.result(proxyConnection, Duration.Inf))
    checkResponse(probe.requestNext())
    probe.cancel()
  }

  "Stream connector" should "connect to HTTP proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "http")
    readFrom(ProxyChain.connect(testHost, proxy))
  }

  it should "connect to SOCKS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "socks")
    readFrom(ProxyChain.connect(testHost, proxy))
  }

  it should "connect through proxy chain" in {
    readFrom(ProxyChain.connect(testHost, testProxies:_*))
  }
}
