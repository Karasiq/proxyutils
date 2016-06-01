package com.karasiq.parsers.http

import akka.util.ByteString
import com.karasiq.networkutils.http.headers.HttpHeader

private[http] object HttpHeaders {
  private def asByteString(b: Seq[Byte]) = ByteString(b:_*)

  private def headersEnd: String = "\r\n\r\n"

  private def asHeader: PartialFunction[String, HttpHeader] = {
    case HttpHeader(header) ⇒ header
  }

  def unapply(b: Seq[Byte]): Option[(Seq[HttpHeader], Seq[Byte])] = {
    asByteString(b).utf8String.split(headersEnd, 2).toSeq match {
      case h +: rest ⇒
        val headers = h.split("\r\n").collect(asHeader).toVector
        Some(headers → ByteString(rest.mkString))

      case _ ⇒
        None
    }
  }
}
