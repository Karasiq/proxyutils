import java.net.InetSocketAddress
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

  val testHost: InetSocketAddress = InetSocketAddress.createUnresolved("ipecho.net", 80) // Host for test connection
  val testUrl: String = "/plain" // URL for test connection

  val testProxies = IndexedSeq(
    Proxy("127.0.0.1", 9999, "http"), // HTTP proxy
    Proxy("127.0.0.1", 1080, "socks"), // Socks proxy
    Proxy("127.0.0.1", 443, "tls-socks", Some("localhost")) // TLS-socks proxy
  )

  private def checkResponse(future: Future[String]): Unit = {
    val response = Await.result(future, 1 minute)
    println(response)
    assert(response.startsWith("HTTP/1.1 200 OK"))
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
    assert(testProxies.head.scheme == "http")
    val socket = SocketChannel.open(testProxies.head.toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector("http", Some(testProxies.head)).connect(socket, testHost)
      readFrom(proxySocket)
    }
  }

  it should "connect to SOCKS proxy" in {
    assert(testProxies(1).scheme == "socks")
    val socket = SocketChannel.open(testProxies(1).toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector("socks", Some(testProxies(1))).connect(socket, testHost)
      readFrom(proxySocket)
    }
  }

  it should "connect through TLS-SOCKS proxy" in {
    assert(testProxies.last.scheme == "tls-socks")
    val socket = SocketChannel.open(testProxies.last.toInetSocketAddress)
    tryAndClose(socket) {
      val proxySocket = ProxyConnector("tls-socks", Some(testProxies.last)).connect(socket, testHost)
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
    val connectorActor = TestActorRef[ProxyConnectorActor]
    checkResponse(connectActorTo(connectorActor, testProxies.head).flatMap {
      case ConnectedThroughProxy(_, _) ⇒
        readFromActor(connectorActor)
    })
  }

  it should "connect to SOCKS proxy" in {
    val connectorActor = TestActorRef[ProxyConnectorActor]
    checkResponse(connectActorTo(connectorActor, testProxies(1)).flatMap {
      case ConnectedThroughProxy(_, _) ⇒
        readFromActor(connectorActor)
    })
  }

  it should "connect through proxy chain" in {
    val chain = ProxyChain(testProxies.dropRight(1):_*)
    checkResponse(chain.connectActor(actorSystem, testHost).flatMap {
      case connection ⇒
        readFromActor(connection)
    })
  }
}
