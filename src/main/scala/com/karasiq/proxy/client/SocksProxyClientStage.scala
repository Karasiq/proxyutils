package com.karasiq.proxy.client

import java.net.InetSocketAddress

import akka.Done
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.{AuthMethod, _}
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, AuthStatusResponse, Codes, ConnectionStatusResponse}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

final class SocksProxyClientStage(destination: InetSocketAddress, version: SocksVersion = SocksVersion.SocksV5, proxy: Option[Proxy] = None) extends GraphStageWithMaterializedValue[BidiShape[ByteString, ByteString, ByteString, ByteString], Future[Done]] {
  val input = Inlet[ByteString]("SocksProxyClient.tcpIn")
  val output = Outlet[ByteString]("SocksProxyClient.tcpOut")
  val proxyInput = Inlet[ByteString]("SocksProxyClient.dataIn")
  val proxyOutput = Outlet[ByteString]("SocksProxyClient.dataOut")

  def shape = BidiShape(input, output, proxyInput, proxyOutput)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      object Stage extends Enumeration {
        val NotConnecting, AuthMethod, Auth, Request = Value
      }

      val maxBufferSize = 4096
      var buffer = ByteString.empty
      var stage = Stage.NotConnecting

      def authInfo: (String, String) = {
        def userInfoSeq(proxy: Option[Proxy]): Option[Seq[String]] = {
          proxy.flatMap(_.userInfo)
            .map(_.split(":", 2).toVector)
        }

        userInfoSeq(proxy) match {
          case Some(userName +: password +: Nil) ⇒
            userName → password

          case _ ⇒
            "" → ""
        }
      }

      def writeBuffer(data: ByteString): Unit = {
        if (buffer.length + data.length > maxBufferSize) {
          failStage(BufferOverflowException("Socks TCP buffer overflow"))
        } else {
          buffer ++= data
        }
      }

      def sendAuthMethodRequest(): Unit = {
        stage = Stage.AuthMethod
        emit(output, AuthRequest(Seq(AuthMethod.NoAuth, AuthMethod.UsernamePassword)), () ⇒ pull(input))
      }

      def sendAuthRequest(): Unit = {
        val (userName, password) = authInfo
        stage = Stage.Auth
        emit(output, UsernameAuthRequest(userName → password), () ⇒ pull(input))
      }

      def sendConnectionRequest(): Unit = {
        stage = Stage.Request
        emit(output, ConnectionRequest((version, Command.TcpConnection, destination, authInfo._1)), () ⇒ pull(input))
      }

      def parseResponse(data: ByteString): Unit = {
        writeBuffer(data)
        stage match {
          case Stage.AuthMethod ⇒
            buffer match {
              case AuthMethodResponse(AuthMethod.NoAuth, rest) ⇒
                buffer = ByteString(rest:_*)
                sendConnectionRequest()

              case AuthMethodResponse(AuthMethod.UsernamePassword, rest) ⇒
                buffer = ByteString(rest:_*)
                sendAuthRequest()

              case AuthMethodResponse(method, _) ⇒
                failStage(new ProxyException(s"Authentication method not supported: $method"))

              case _ ⇒
                pull(input)
            }

          case Stage.Auth ⇒
            buffer match {
              case AuthStatusResponse(0x00, rest) ⇒
                buffer = ByteString(rest:_*)
                sendConnectionRequest()

              case AuthStatusResponse(_, _) ⇒
                failStage(new ProxyException("Socks authentication rejected"))

              case _ ⇒
                pull(input)
            }

          case Stage.Request ⇒
            buffer match {
              case ConnectionStatusResponse((`version`, address, status), rest) ⇒
                buffer = ByteString(rest:_*)
                def connectionEstablished(): Unit = {
                  setHandler(input, new InHandler {
                    def onPush() = push(proxyOutput, grab(input))
                  })
                  setHandler(output, new OutHandler {
                    def onPull() = if (!hasBeenPulled(proxyInput)) tryPull(proxyInput)
                  })
                  setHandler(proxyInput, new InHandler {
                    def onPush() = push(output, grab(proxyInput))
                    override def onUpstreamFinish() = ()
                  })
                  setHandler(proxyOutput, new OutHandler {
                    def onPull() = if (!hasBeenPulled(input)) pull(input)
                  })
                  promise.success(Done)
                }

                if ((version == SocksVersion.SocksV5 && status != Codes.Socks5.REQUEST_GRANTED) || (version == SocksVersion.SocksV4 && status != Codes.Socks4.REQUEST_GRANTED)) {
                  failStage(new ProxyException(s"SOCKS request rejected: $status"))
                } else {
                  stage = Stage.NotConnecting
                  connectionEstablished()
                  if (buffer.nonEmpty) {
                    emit(proxyOutput, buffer, () ⇒ if (!hasBeenPulled(input)) pull(input))
                    buffer = ByteString.empty
                  } else {
                    pull(input)
                  }
                  pull(proxyInput)
                }

              case _ ⇒
            }

          case _ ⇒
            failStage(new IllegalArgumentException("Invalid stage"))
        }
      }

      override def preStart() = {
        super.preStart()
        version match {
          case SocksVersion.SocksV5 ⇒
            sendAuthMethodRequest()

          case SocksVersion.SocksV4 ⇒
            sendConnectionRequest()
        }
      }

      override def postStop() = {
        promise.tryFailure(new ProxyException("Proxy connection closed"))
        super.postStop()
      }

      setHandler(input, new InHandler {
        def onPush() = parseResponse(grab(input))
      })
      setHandler(proxyInput, eagerTerminateInput)
      setHandler(output, eagerTerminateOutput)
      setHandler(proxyOutput, eagerTerminateOutput)
    }
    (logic, promise.future)
  }
}
