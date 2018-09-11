package com.karasiq.proxy.client

import java.net.InetSocketAddress

import scala.concurrent.{Future, Promise}

import akka.Done
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString

import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.socks.SocksClient.{AuthMethod, _}
import com.karasiq.parsers.socks.SocksServer.{AuthMethodResponse, AuthStatusResponse, Codes, ConnectionStatusResponse}
import com.karasiq.proxy.ProxyException

final class SocksProxyClientStage(log: LoggingAdapter, destination: InetSocketAddress, version: SocksVersion = SocksVersion.SocksV5, proxy: Option[Proxy] = None) extends GraphStageWithMaterializedValue[BidiShape[ByteString, ByteString, ByteString, ByteString], Future[Done]] {
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
      val proxyString = proxy.getOrElse("<unknown>")

      def authCredentials: (String, String) = {
        val loginPassword = for (p ← proxy; string ← p.userInfo; Array(login, password) ← Some(string.split(":", 2)))
          yield (login, password)
        loginPassword
          .orElse(proxy.flatMap(_.userInfo).map(_ → ""))
          .getOrElse("" → "")
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
        log.debug("Requesting available auth methods from SOCKS proxy: {}", proxyString)

        val authMethods = if (proxy.exists(_.userInfo.isDefined))
          Seq(AuthMethod.NoAuth, AuthMethod.UsernamePassword)
        else
          Seq(AuthMethod.NoAuth)

        emit(output, AuthRequest(authMethods), () ⇒ pull(input))
      }

      def sendAuthRequest(): Unit = {
        log.debug(s"Sending auth request to SOCKS proxy: {}", proxyString)
        val (login, password) = authCredentials
        stage = Stage.Auth
        emit(output, UsernameAuthRequest(login → password), () ⇒ pull(input))
      }

      def sendConnectionRequest(): Unit = {
        log.debug("Sending connection request to SOCKS proxy {} -> {}", proxyString, destination)
        stage = Stage.Request
        val (userId, _) = authCredentials
        emit(output, ConnectionRequest((version, Command.TcpConnection, destination, userId)), () ⇒ pull(input))
      }

      def parseResponse(data: ByteString): Unit = {
        writeBuffer(data)
        stage match {
          case Stage.AuthMethod ⇒
            buffer match {
              case AuthMethodResponse(AuthMethod.NoAuth, rest) ⇒
                buffer = ByteString(rest:_*)
                log.debug("SOCKS proxy has no authentication: {}", proxyString)
                sendConnectionRequest()

              case AuthMethodResponse(AuthMethod.UsernamePassword, rest) ⇒
                buffer = ByteString(rest:_*)
                log.debug("SOCKS proxy requested username/password authentication: {}", proxyString)
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
                log.debug("SOCKS proxy accepted authentication: {}", proxyString)
                sendConnectionRequest()

              case AuthStatusResponse(_, _) ⇒
                failStage(new ProxyException("SOCKS authentication rejected"))

              case _ ⇒
                pull(input)
            }

          case Stage.Request ⇒
            buffer match {
              case ConnectionStatusResponse((`version`, address, status), rest) ⇒
                buffer = ByteString(rest:_*)
                if ((version == SocksVersion.SocksV5 && status != Codes.Socks5.REQUEST_GRANTED) || (version == SocksVersion.SocksV4 && status != Codes.Socks4.REQUEST_GRANTED)) {
                  failStage(new ProxyException(s"SOCKS request rejected: $status"))
                } else {
                  log.debug("Connection established to {} through SOCKS proxy: {}", destination, proxyString)
                  stage = Stage.NotConnecting
                  passAlong(input, proxyOutput)
                  passAlong(proxyInput, output, doFinish = false, doPull = true)
                  promise.success(Done)
                  if (buffer.nonEmpty) {
                    emit(proxyOutput, buffer, () ⇒ if (!hasBeenPulled(input)) pull(input))
                    buffer = ByteString.empty
                  } else {
                    pull(input)
                  }
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
