package com.karasiq.proxy.client

import java.net.InetSocketAddress

import akka.Done
import akka.stream._
import akka.stream.stage._
import akka.util.ByteString
import com.karasiq.networkutils.http.headers.{HttpHeader, `Proxy-Authorization`}
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpConnect, HttpResponse}
import com.karasiq.proxy.ProxyException

import scala.concurrent.{Future, Promise}

class HttpProxyClientStage(destination: InetSocketAddress, proxy: Option[Proxy] = None) extends GraphStageWithMaterializedValue[BidiShape[ByteString, ByteString, ByteString, ByteString], Future[Done]] {
  val input = Inlet[ByteString]("HttpProxyClient.tcpIn")
  val output = Outlet[ByteString]("HttpProxyClient.tcpOut")
  val proxyInput = Inlet[ByteString]("HttpProxyClient.dataIn")
  val proxyOutput = Outlet[ByteString]("HttpProxyClient.dataOut")

  def shape = BidiShape(input, output, proxyInput, proxyOutput)

  def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
    val promise = Promise[Done]
    val logic = new GraphStageLogic(shape) {
      override def postStop() = {
        promise.tryFailure(new ProxyException("Proxy connection closed"))
        super.postStop()
      }

      val bufferSize = 8192
      val terminator = ByteString("\r\n\r\n", "ASCII")
      var buffer = ByteString.empty
      var connected = false
      var requestSent = false

      def sendRequest(): Unit = {
        requestSent = true
        val auth: Seq[HttpHeader] = proxy.flatMap(_.userInfo).map(userInfo ⇒ `Proxy-Authorization`.basic(userInfo)).toVector
        emit(output, HttpConnect(destination, auth), () ⇒ if (!hasBeenPulled(input)) tryPull(input))
      }

      setHandler(input, new InHandler {
        def onPush() = {
          val data: ByteString = grab(input)
          if (connected) {
            emit(proxyOutput, data, () ⇒ if (!hasBeenPulled(input)) tryPull(input))
          } else {
            buffer ++= data
            if (buffer.length > bufferSize) {
              failStage(BufferOverflowException("HTTP proxy headers size limit reached"))
            }
            val headersEnd = buffer.indexOfSlice(terminator)
            if (headersEnd != -1) {
              val (keep, drop) = buffer.splitAt(headersEnd + terminator.length)
              buffer = ByteString.empty
              keep match {
                case HttpResponse((status, headers), _) ⇒
                  if (status.code != 200) failStage(new ProxyException(s"HTTP CONNECT failed: ${status.code} ${status.message}"))
                  connected = true
                  promise.success(Done)
                  if (!hasBeenPulled(proxyInput)) tryPull(proxyInput)
                  if (drop.nonEmpty) emit(proxyOutput, drop)

                case bs: ByteString ⇒
                  failStage(new ProxyException(s"Bad HTTPS proxy response: ${bs.utf8String}"))
              }
            }

            if (!hasBeenPulled(input)) {
              tryPull(input)
            }
          }
        }
      })

      setHandler(proxyInput, new InHandler {
        def onPush() = {
          val data = grab(proxyInput)
          if (connected) {
            emit(output, data, () ⇒ if (!hasBeenPulled(proxyInput)) tryPull(proxyInput))
          } else {
            failStage(new ProxyException("HTTP proxy is not ready"))
          }
        }

        override def onUpstreamFinish() = ()
      })

      val outHandler = new OutHandler {
        def onPull() = {
          if (!requestSent) {
            sendRequest()
          }

          if (!hasBeenPulled(input)) {
            tryPull(input)
          }

          if (!hasBeenPulled(proxyInput) && connected) {
            tryPull(proxyInput)
          }
        }
      }

      setHandler(output, outHandler)
      setHandler(proxyOutput, outHandler)
    }
    (logic, promise.future)
  }
}
