import java.net.{InetSocketAddress, URI}
import java.security.SecureRandom
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.Done
import akka.actor._
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Source, Tcp}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.{ByteString, Timeout}
import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpMethod, HttpRequest}
import com.karasiq.proxy._
import com.karasiq.tls.TLSKeyStore
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

// You need to have running proxies to run this test
class ProxyChainTest extends FlatSpec with Matchers with BeforeAndAfterAll {
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

  def tlsContext: HttpsConnectionContext = {
    val keyStore = TLSKeyStore()
    val sslContext = SSLContext.getInstance("TLS")
    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore.keyStore, keyStore.password.toCharArray)

    val trustManagerFactory: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    trustManagerFactory.init(keyStore.keyStore)
    sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, SecureRandom.getInstanceStrong)

    ConnectionContext.https(sslContext)
  }

  override protected def afterAll() = {
    actorSystem.terminate()
    super.afterAll()
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
    checkResponse(probe.requestNext())
    probe.cancel()
  }

  "Stream connector" should "connect to HTTP proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "http")
    readFrom(ProxyChain.connect(testHost, Seq(proxy)))
  }


  it should "connect to SOCKS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "socks")
    readFrom(ProxyChain.connect(testHost, Seq(proxy)))
  }

  it should "connect to TLS-SOCKS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "tls-socks")
    readFrom(ProxyChain.connect(testHost, Seq(proxy), Some(tlsContext)))
  }

  it should "connect through proxy chain" in {
    readFrom(ProxyChain.connect(testHost, testProxies, Some(tlsContext)))
  }
}
