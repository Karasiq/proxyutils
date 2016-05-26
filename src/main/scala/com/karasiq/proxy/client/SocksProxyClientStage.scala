package com.karasiq.proxy.client

import java.net.InetSocketAddress

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.ByteString
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.{AuthMethod, _}
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, AuthStatusResponse, Codes, ConnectionStatusResponse}
import com.karasiq.proxy.ProxyException

class SocksProxyClientStage(destination: InetSocketAddress, version: SocksVersion = SocksVersion.SocksV5, proxy: Option[Proxy] = None) extends GraphStage[BidiShape[ByteString, ByteString, ByteString, ByteString]] {
  val input = Inlet[ByteString]("tcp-input")
  val output = Outlet[ByteString]("tcp-output")
  val proxyInput = Inlet[ByteString]("socks-input")
  val proxyOutput = Outlet[ByteString]("socks-output")

  def shape = BidiShape(input, output, proxyInput, proxyOutput)

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) {
    object Stage extends Enumeration {
      val NotInitialized, AuthMethod, Auth, Request, Connected = Value
    }

    val maxBufferSize = 4096
    var buffer = ByteString.empty
    var stage = Stage.NotInitialized

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
        failStage(new ProxyException("Socks TCP buffer overflow"))
      } else {
        buffer ++= data
      }
    }

    def sendAuthMethodRequest(): Unit = {
      stage = Stage.AuthMethod
      emit(output, AuthRequest(Seq(AuthMethod.NoAuth, AuthMethod.UsernamePassword)), () ⇒ if (!hasBeenPulled(input)) tryPull(input))
    }

    def sendAuthRequest(): Unit = {
      val (userName, password) = authInfo
      stage = Stage.Auth
      emit(output, UsernameAuthRequest(userName → password), () ⇒ if (!hasBeenPulled(input)) tryPull(input))
    }

    def sendConnectionRequest(): Unit = {
      stage = Stage.Request
      version match {
        case SocksVersion.SocksV5 ⇒
          emit(output, ConnectionRequest((SocksVersion.SocksV5, Command.TcpConnection, destination, "")), () ⇒ if (!hasBeenPulled(input)) tryPull(input))

        case SocksVersion.SocksV4 ⇒
          emit(output, ConnectionRequest((SocksVersion.SocksV4, Command.TcpConnection, destination, authInfo._1)), () ⇒ if (!hasBeenPulled(input)) tryPull(input))
      }
    }

    def onReceive(data: ByteString): Unit = {
      if (stage == Stage.Connected) {
        emit(proxyOutput, data, () ⇒ if (!hasBeenPulled(input)) tryPull(input))
      } else {
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
                if (!hasBeenPulled(input)) tryPull(input)
            }

          case Stage.Auth ⇒
            buffer match {
              case AuthStatusResponse(0x00, rest) ⇒
                buffer = ByteString(rest:_*)
                sendConnectionRequest()

              case AuthStatusResponse(_, _) ⇒
                failStage(new ProxyException("Socks authentication rejected"))

              case _ ⇒
                if (!hasBeenPulled(input)) tryPull(input)
            }

          case Stage.Request ⇒
            buffer match {
              case ConnectionStatusResponse((`version`, address, status), rest) ⇒
                if ((version == SocksVersion.SocksV5 && status != Codes.Socks5.REQUEST_GRANTED) || (version == SocksVersion.SocksV4 && status != Codes.Socks4.REQUEST_GRANTED)) {
                  failStage(new ProxyException(s"SOCKS request rejected: $status"))
                }

                stage = Stage.Connected
                buffer = ByteString.empty
                if (rest.nonEmpty) emit(proxyOutput, ByteString(rest:_*))
                if (!hasBeenPulled(input)) tryPull(input)
                if (!hasBeenPulled(proxyInput)) tryPull(proxyInput)
            }

          case _ ⇒
            failStage(new IllegalArgumentException)
        }
      }
    }

    setHandler(input, new InHandler {
      def onPush() = {
        val data = grab(input)
        onReceive(data)
      }
    })

    setHandler(proxyInput, new InHandler {
      def onPush() = {
        if (stage == Stage.Connected) {
          val data = grab(proxyInput)
          emit(output, data, () ⇒ if (!hasBeenPulled(proxyInput)) tryPull(proxyInput))
        } else {
          failStage(new ProxyException("Socks proxy is not ready"))
        }
      }

      override def onUpstreamFinish() = ()
    })

    val outHandler = new OutHandler {
      def onPull() = {
        if (stage == Stage.NotInitialized) {
          version match {
            case SocksVersion.SocksV5 ⇒
              sendAuthMethodRequest()

            case SocksVersion.SocksV4 ⇒
              sendConnectionRequest()
          }
        }
      }
    }
    setHandler(output, outHandler)
    setHandler(proxyOutput, outHandler)
  }
}
