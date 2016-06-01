package com.karasiq.proxy.server

import java.net.InetSocketAddress

import akka.util.ByteString
import com.karasiq.networkutils.http.HttpStatus
import com.karasiq.parsers.http.HttpResponse
import com.karasiq.parsers.socks.SocksClient.SocksVersion.{SocksV4, SocksV5}
import com.karasiq.parsers.socks.SocksServer.{Codes, _}

case class ProxyConnectionRequest(scheme: String, address: InetSocketAddress)

object ProxyConnectionRequest {
  def successResponse(request: ProxyConnectionRequest): ByteString = {
    request.scheme match {
      case "http" ⇒
        HttpResponse((HttpStatus(200, "Connection established"), Nil))

      case "socks" ⇒
        ConnectionStatusResponse(SocksV5, None, Codes.success(SocksV5))

      case "socks4" ⇒
        ConnectionStatusResponse(SocksV4, None, Codes.success(SocksV4))

      case _ ⇒
        throw new IllegalArgumentException(s"Invalid proxy connection request: $request")
    }
  }

  def failureResponse(request: ProxyConnectionRequest): ByteString = {
    request.scheme match {
      case "http" ⇒
        HttpResponse((HttpStatus(400, "Bad Request"), Nil))

      case "socks" ⇒
        ConnectionStatusResponse(SocksV5, None, Codes.failure(SocksV5))

      case "socks4" ⇒
        ConnectionStatusResponse(SocksV4, None, Codes.failure(SocksV4))

      case _ ⇒
        throw new IllegalArgumentException(s"Invalid proxy connection request: $request")
    }
  }
}
