package com.karasiq.proxy.client

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import akka.Done
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.http.headers.{HttpHeader, `Proxy-Authorization`}
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpConnect, HttpResponse}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

final class HttpProxyClientStage(destination: InetSocketAddress, proxy: Option[Proxy] = None) extends GraphStageWithMaterializedValue[BidiShape[ByteString, ByteString, ByteString, ByteString], Future[Done]] {
  val input = Inlet[ByteString]("HttpProxyClient.tcpIn")
  val output = Outlet[ByteString]("HttpProxyClient.tcpOut")
  val proxyInput = Inlet[ByteString]("HttpProxyClient.dataIn")
  val proxyOutput = Outlet[ByteString]("HttpProxyClient.dataOut")

  def shape = BidiShape(input, output, proxyInput, proxyOutput)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      val bufferSize = 8192
      val terminator = ByteString("\r\n\r\n", StandardCharsets.US_ASCII.name())
      var buffer = ByteString.empty

      def sendRequest(): Unit = {
        val auth: Seq[HttpHeader] = proxy.flatMap(_.userInfo).map(userInfo ⇒ `Proxy-Authorization`.basic(userInfo)).toVector
        emit(output, HttpConnect(destination, auth), () ⇒ pull(input))
      }

      def parseResponse(): Unit = {
        val headersEnd = buffer.indexOfSlice(terminator)
        if (headersEnd != -1) {
          val (keep, drop) = buffer.splitAt(headersEnd + terminator.length)
          buffer = drop
          keep match {
            case HttpResponse((status, headers), _) ⇒
              if (status.code != 200) {
                failStage(new ProxyException(s"HTTP CONNECT failed: ${status.code} ${status.message}"))
              } else {
                setHandler(input, new InHandler {
                  def onPush() = emit(proxyOutput, grab(input), () ⇒ if (!hasBeenPulled(input)) pull(input))
                })
                setHandler(output, new OutHandler {
                  def onPull() = if (!hasBeenPulled(proxyInput)) tryPull(proxyInput)
                })
                setHandler(proxyInput, new InHandler {
                  def onPush() = emit(output, grab(proxyInput), () ⇒ if (!hasBeenPulled(proxyInput)) tryPull(proxyInput))
                  override def onUpstreamFinish() = ()
                })
                setHandler(proxyOutput, new OutHandler {
                  def onPull() = if (!hasBeenPulled(input)) pull(input)
                })
                promise.success(Done)
                if (buffer.nonEmpty) {
                  emit(proxyOutput, buffer, () ⇒ if (!hasBeenPulled(input)) pull(input))
                  buffer = ByteString.empty
                } else {
                  pull(input)
                }
                pull(proxyInput)
              }

            case bs: ByteString ⇒
              failStage(new ProxyException(s"Bad HTTPS proxy response: ${bs.utf8String}"))
          }
        } else {
          pull(input)
        }
      }

      override def preStart() = {
        super.preStart()
        sendRequest()
      }

      override def postStop() = {
        promise.tryFailure(new ProxyException("Proxy connection closed"))
        super.postStop()
      }

      setHandler(input, new InHandler {
        def onPush() = {
          buffer ++= grab(input)
          if (buffer.length > bufferSize) failStage(BufferOverflowException("HTTP proxy headers size limit reached"))
          parseResponse()
        }
      })

      setHandler(proxyInput, eagerTerminateInput)
      setHandler(output, eagerTerminateOutput)
      setHandler(proxyOutput, eagerTerminateOutput)
    }
    (logic, promise.future)
  }
}
