package com.karasiq.proxy.server

import java.io.IOException

import akka.NotUsed
import akka.http.scaladsl.HttpsConnectionContext
import akka.stream.TLSProtocol.{SendBytes, SessionBytes, SslTlsInbound}
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.http.HttpStatus
import com.karasiq.networkutils.url.URLParser
import com.karasiq.parsers.http.{HttpConnect, HttpMethod, HttpRequest, HttpResponse}
import com.karasiq.parsers.socks.SocksClient._
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, _}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

object ProxyServer {
  def withTls(tlsContext: HttpsConnectionContext): Flow[ByteString, ByteString, Future[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]] = {
    Flow.fromGraph(GraphDSL.create(new ProxyServerStage) { implicit builder ⇒ stage ⇒
      import GraphDSL.Implicits._
      val tlsInbound = builder.add(Flow[SslTlsInbound].collect { case SessionBytes(_, bytes) ⇒ bytes })
      val tlsOutbound = builder.add(Flow[ByteString].map(SendBytes))
      val tls = builder.add(TLS(tlsContext.sslContext, tlsContext.firstSession, TLSRole.server))
      tls.out2 ~> tlsInbound ~> stage
      stage ~> tlsOutbound ~> tls.in1
      FlowShape(tls.in2, tls.out1)
    })
  }

  def apply(): Flow[ByteString, ByteString, Future[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]] = {
    Flow.fromGraph(new ProxyServerStage)
  }

  def withResponse[Mat](flow: Flow[ByteString, ByteString, Mat], response: ByteString): Flow[ByteString, ByteString, Mat] = {
    Flow.fromGraph(GraphDSL.create(flow) { implicit builder ⇒ connection ⇒
      import GraphDSL.Implicits._
      val inputHead = builder.add(Source.single(response))
      val input = builder.add(Concat[ByteString]())
      inputHead ~> input.in(0)
      input ~> connection
      FlowShape(input.in(1), connection.out)
    })
  }

  def withSuccess[Mat](flow: Flow[ByteString, ByteString, Mat], request: ProxyConnectionRequest): Flow[ByteString, ByteString, Mat] = {
    if (request.scheme == "http") flow else withResponse(flow, ProxyConnectionRequest.successResponse(request))
  }

  def withFailure[Mat](flow: Flow[ByteString, ByteString, Mat], request: ProxyConnectionRequest): Flow[ByteString, ByteString, Mat] = {
    withResponse(flow, ProxyConnectionRequest.failureResponse(request))
  }
}

private[proxy] final class ProxyServerStage extends GraphStageWithMaterializedValue[FlowShape[ByteString, ByteString], Future[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]] {
  val tcpInput = Inlet[ByteString]("ProxyServer.tcpIn")
  val tcpOutput = Outlet[ByteString]("ProxyServer.tcpOut")
  val promise = Promise[(ProxyConnectionRequest, Flow[ByteString, ByteString, NotUsed])]()

  val shape = new FlowShape(tcpInput, tcpOutput)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val logic = new GraphStageLogic(shape) {
      val bufferSize = 8192
      var buffer = ByteString.empty

      override def postStop() = {
        promise.tryFailure(new IOException("Connection closed"))
        super.postStop()
      }

      override def preStart() = {
        super.preStart()
        pull(tcpInput)
      }

      def failConnection(ex: Exception): Unit = {
        promise.tryFailure(ex)
        failStage(ex)
      }

      def writeBuffer(data: ByteString): Unit = {
        if (buffer.length > bufferSize) {
          failConnection(BufferOverflowException("Buffer overflow"))
        } else {
          buffer ++= data
        }
      }

      def emitRequest(request: ProxyConnectionRequest): Unit = {
        val inlet = new SubSinkInlet[ByteString]("ProxyServer.tcpInConnected")
        val outlet = new SubSourceOutlet[ByteString]("ProxyServer.tcpOutConnected")
        var outletEmitting = false
        val outletHandler = new OutHandler {
          @scala.throws[Exception](classOf[Exception])
          def onPull() = ()

          override def onDownstreamFinish() = {
            cancel(tcpInput)
          }
        }
        def outletEmit(data: ByteString, andThen: () ⇒ Unit): Unit = {
          if (outlet.isAvailable) {
            outlet.push(data)
            andThen()
          } else {
            outletEmitting = true
            outlet.setHandler(new OutHandler {
              def onPull() = {
                outletEmitting = false
                outlet.push(data)
                outlet.setHandler(outletHandler)
                andThen()
              }

              override def onDownstreamFinish() = {
                outletHandler.onDownstreamFinish()
              }
            })
          }
        }

        setHandler(tcpInput, new InHandler {
          def onPush() = outletEmit(grab(tcpInput), () ⇒ if (isClosed(tcpInput)) outlet.complete() else pull(tcpInput))
          override def onUpstreamFinish() = if (!outletEmitting) outlet.complete()
        })

        setHandler(tcpOutput, new OutHandler {
          def onPull() = if (!inlet.hasBeenPulled && !inlet.isClosed) inlet.pull()
          override def onDownstreamFinish() = inlet.cancel()
        })

        inlet.setHandler(new InHandler {
          def onPush() = emit(tcpOutput, inlet.grab(), () ⇒ if (!inlet.hasBeenPulled && !inlet.isClosed) inlet.pull())
          override def onUpstreamFinish() = complete(tcpOutput)
        })
        outlet.setHandler(outletHandler)

        if (buffer.nonEmpty) {
          outletEmit(buffer, () ⇒ if (isClosed(tcpInput)) outlet.complete() else pull(tcpInput))
          buffer = ByteString.empty
        } else {
          tryPull(tcpInput)
        }

        promise.success(request → Flow.fromSinkAndSource(inlet.sink, outlet.source))
        inlet.pull()
      }

      def processBuffer(): Unit = {
        buffer match {
          case ConnectionRequest((socksVersion, command, address, userId), rest) ⇒
            buffer = ByteString(rest:_*)
            if (command != Command.TcpConnection) {
              val code = if (socksVersion == SocksVersion.SocksV5) Codes.Socks5.COMMAND_NOT_SUPPORTED else Codes.failure(socksVersion)
              emit(tcpOutput, ConnectionStatusResponse(socksVersion, None, code), () ⇒ {
                val ex = new ProxyException("Command not supported")
                failConnection(ex)
              })
            } else {
              emitRequest(ProxyConnectionRequest(if (socksVersion == SocksVersion.SocksV5) "socks" else "socks4", address))
            }

          case AuthRequest(methods, rest) ⇒
            buffer = ByteString(rest:_*)
            if (methods.contains(AuthMethod.NoAuth)) {
              emit(tcpOutput, AuthMethodResponse(AuthMethod.NoAuth), () ⇒ pull(tcpInput))
            } else {
              emit(tcpOutput, AuthMethodResponse.notSupported, () ⇒ {
                val ex = new ProxyException("No valid authentication methods provided")
                failConnection(ex)
              })
            }

          case HttpRequest((method, url, headers), rest) ⇒
            buffer = ByteString(rest:_*)
            val address = HttpConnect.addressOf(url)
            if (address.getHostString.isEmpty) { // Plain HTTP request
              emit(tcpOutput, HttpResponse(HttpStatus(400, "Bad Request"), Nil) ++ ByteString("Request not supported"), () ⇒ {
                val ex = new ProxyException("Plain HTTP not supported")
                failConnection(ex)
              })
            } else {
              if (method != HttpMethod.CONNECT) {
                // Replicate request
                val path = Option(URLParser.withDefaultProtocol(url).getFile).filter(_.nonEmpty).getOrElse("/")
                val request = HttpRequest((method, path, headers))
                buffer = request ++ buffer
                emitRequest(ProxyConnectionRequest("http", address))
              } else {
                emitRequest(ProxyConnectionRequest("https", address))
              }
            }

          case _ ⇒
            pull(tcpInput)
        }
      }

      setHandler(tcpInput, new InHandler {
        def onPush() = {
          val data = grab(tcpInput)
          writeBuffer(data)
          processBuffer()
        }
      })

      setHandler(tcpOutput, eagerTerminateOutput)
    }
    (logic, promise.future)
  }
}
