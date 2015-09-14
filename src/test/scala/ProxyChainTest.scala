import java.net.{InetSocketAddress, URI}
import java.nio.channels.SocketChannel

import akka.actor._
import akka.io.Tcp._
import akka.testkit.TestActorRef
import akka.util.{ByteString, Timeout}
import com.karasiq.networkutils.SocketChannelWrapper.ReadWriteOps
import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpMethod, HttpRequest}
import com.karasiq.proxy._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.IOUtils
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps
import scala.util.control.Exception

// You need to have running proxies to run this test
class ProxyChainTest extends FlatSpec with Matchers {
  implicit val timeout = Timeout(1 minute)
  implicit val actorSystem = ActorSystem("proxyChain")
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

  private def checkResponse(future: Future[String]): Unit = {
    val response = Await.result(future, 1 minute)
    println(response)
    assert(response.startsWith(config.getString("ok-status")))
  }

  private def readFrom(socket: SocketChannel): Unit = {
    val request = HttpRequest((HttpMethod.GET, testUrl, Seq(HttpHeader("Host" → testHost.getHostString))))
    val response = socket.writeRead(request, 512).utf8String
    checkResponse(Future.successful(response))
  }

  private def connectActorTo(connection: ActorRef, proxy: Proxy): Future[Any] = {
    import akka.pattern.ask
    connection ? ConnectThroughProxy(proxy, testHost)
  }

  private def readFromActor(connection: ActorRef): Future[String] = {
    val promise = Promise[String]()

    val actor = TestActorRef(new Actor {
      @throws[Exception](classOf[Exception])
      override def preStart(): Unit = {
        super.preStart()
        connection ! EnableStreaming(self)
        connection ! Write(ByteString(HttpRequest((HttpMethod.GET, testUrl, Seq(HttpHeader("Host" → testHost.getHostString)))).toArray))
      }

      override def receive: Actor.Receive = {
        case Received(data) ⇒
          promise.success(data.utf8String)
          connection ! Close

        case _: ConnectionClosed ⇒
          context.stop(self)
      }
    })

    promise.future
  }

  private def tryAndClose(sc: SocketChannel) = Exception.allCatch.andFinally(IOUtils.closeQuietly(sc))

  "Connector" should "connect to HTTP proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "http")
    val socket = SocketChannel.open(proxy.toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector("http", Some(proxy)).connect(socket, testHost)
      readFrom(proxySocket)
    }
  }

  it should "connect to SOCKS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "socks")
    val socket = SocketChannel.open(proxy.toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector("socks", Some(proxy)).connect(socket, testHost)
      readFrom(proxySocket)
    }
  }

  it should "connect through TLS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme.startsWith("tls-"))
    val socket = SocketChannel.open(proxy.toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector(proxy.scheme, Some(proxy)).connect(socket, testHost)
      readFrom(proxySocket)
    }
  }

  it should "connect through proxy chain" in {
    val chain = ProxyChain(testProxies:_*)
    val socket = chain.connection(testHost)
    tryAndClose(socket) {
      readFrom(socket)
    }
  }

  "Connector actor" should "connect to HTTP proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "http")
    val connectorActor = TestActorRef[ProxyConnectorActor]
    checkResponse(connectActorTo(connectorActor, proxy).flatMap {
      case ConnectedThroughProxy(_, _) ⇒
        readFromActor(connectorActor)
    })
  }

  it should "connect to SOCKS proxy" in {
    val Some(proxy) = testProxies.find(_.scheme == "socks")
    val connectorActor = TestActorRef[ProxyConnectorActor]
    checkResponse(connectActorTo(connectorActor, proxy).flatMap {
      case ConnectedThroughProxy(_, _) ⇒
        readFromActor(connectorActor)
    })
  }

  it should "connect through proxy chain" in {
    val chain = ProxyChain(testProxies.filterNot(_.scheme.startsWith("tls-")):_*)
    checkResponse(chain.connectActor(actorSystem, testHost).flatMap {
      case connection ⇒
        readFromActor(connection)
    })
  }
}
