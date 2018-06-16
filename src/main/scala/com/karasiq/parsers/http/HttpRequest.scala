package com.karasiq.parsers.http

import akka.util.ByteString
import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.parsers.{ByteFragment, RegexByteExtractor}

object HttpMethod extends Enumeration {
  val GET, HEAD, POST, PUT, PATCH, DELETE, CONNECT, OPTIONS, TRACE = Value
}

object HttpRequest extends ByteFragment[(HttpMethod.Value, String, Seq[HttpHeader])] {
  private val regex = new RegexByteExtractor("""^([A-Z]+) ((?:https?://|)[^\s]+) HTTP/1\.[01]\r\n""".r)

  override def fromBytes: Extractor = {
    case regex(result, HttpHeaders(headers, rest)) ⇒
      (HttpMethod.withName(result.group(1)), result.group(2), headers) → rest
  }

  override def toBytes(value: (HttpMethod.Value, String, Seq[HttpHeader])): ByteString = value match {
    case (method, address, headers) ⇒
      val connect = s"$method $address HTTP/1.1\r\n"
      ByteString(connect + HttpHeader.formatHeaders(headers) + "\r\n")
  }
}
