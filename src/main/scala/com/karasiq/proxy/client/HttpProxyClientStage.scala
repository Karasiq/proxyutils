package com.karasiq.proxy.client

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import akka.Done
import akka.event.LoggingAdapter
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.http.headers.{HttpHeader, `Proxy-Authorization`}
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpConnect, HttpResponse}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

final class HttpProxyClientStage(log: LoggingAdapter, destination: InetSocketAddress, proxy: Option[Proxy] = None) extends GraphStageWithMaterializedValue[BidiShape[ByteString, ByteString, ByteString, ByteString], Future[Done]] {
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
      val proxyString = proxy.getOrElse("<unknown>")

      def sendRequest(): Unit = {
        val auth: Seq[HttpHeader] = proxy.flatMap(_.userInfo).map(userInfo ⇒ `Proxy-Authorization`.basic(userInfo)).toVector
        val request = HttpConnect(destination, auth)
        log.debug(s"Sending request to HTTP proxy {}: {}", proxyString, request.utf8String)
        emit(output, request, () ⇒ pull(input))
      }

      def parseResponse(): Unit = {
        val headersEnd = buffer.indexOfSlice(terminator)
        if (headersEnd != -1) {
          val (keep, drop) = buffer.splitAt(headersEnd + terminator.length)
          buffer = drop
          keep match {
            case response @ HttpResponse((status, headers), _) ⇒
              log.debug("Received response from HTTP proxy {}: {}", proxyString, response.utf8String)
              if (status.code != 200) {
                failStage(new ProxyException(s"HTTP CONNECT rejected by $proxyString: ${status.code} ${status.message}"))
              } else {
                log.debug("Connection established to {} through HTTP proxy: {}", destination, proxyString)
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

            case bs: ByteString ⇒
              failStage(new ProxyException(s"Bad HTTPS proxy response: ${bs.utf8String}"))
          }
        } else {
          log.debug("Incomplete response from HTTP proxy {}: {}", proxyString, buffer.utf8String)
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
