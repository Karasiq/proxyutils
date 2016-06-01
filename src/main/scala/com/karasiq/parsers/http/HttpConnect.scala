package com.karasiq.parsers.http

import java.net.InetSocketAddress

import akka.util.ByteString
import com.karasiq.networkutils.http.headers.{Host, HttpHeader}
import com.karasiq.networkutils.url.URLParser


object HttpConnect {
  private def portOption(port: Int) = Some(port).filter(_ != -1)

  def addressOf(u: String): InetSocketAddress = {
    val url = URLParser.withDefaultProtocol(u)
    val (host, port) = url.getHost → portOption(url.getPort).orElse(portOption(url.getDefaultPort)).getOrElse(80)
    InetSocketAddress.createUnresolved(host, port)
  }

  private def withHostHeader(address: InetSocketAddress, headers: Seq[HttpHeader]) = {
    if (headers.exists(_.name == Host.name)) headers else headers :+ Host(address)
  }

  def apply(address: InetSocketAddress, headers: Seq[HttpHeader]): ByteString = {
    HttpRequest((HttpMethod.CONNECT, s"${address.getHostString}:${address.getPort}", withHostHeader(address, headers)))
  }
}
