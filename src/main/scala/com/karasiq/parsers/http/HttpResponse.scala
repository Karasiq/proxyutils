package com.karasiq.parsers.http

import akka.util.ByteString
import com.karasiq.networkutils.http.HttpStatus
import com.karasiq.networkutils.http.headers.HttpHeader
import com.karasiq.parsers.{ByteFragment, RegexByteExtractor}

object HttpResponse extends ByteFragment[(HttpStatus, Seq[HttpHeader])] {
  private val regex = new RegexByteExtractor("""^HTTP/1\.[01] (\d+) ([^\r\n]+)(?=\r\n)""".r)

  override def fromBytes: Extractor = {
    case regex(result, HttpHeaders(headers, rest)) ⇒
      (HttpStatus(result.group(1).toInt, result.group(2)), headers) → rest
  }

  override def toBytes(value: (HttpStatus, Seq[HttpHeader])): ByteString = value match {
    case (status, headers) ⇒
      val statusString = s"HTTP/1.1 ${status.code} ${status.message}\r\n"
      ByteString(statusString + HttpHeader.formatHeaders(headers) + "\r\n")
  }
}
