package com.karasiq.parsers.http

import akka.util.ByteString
import com.karasiq.networkutils.http.headers.HttpHeader

private[http] object HttpHeaders {
  private def asByteString(b: Seq[Byte]) = ByteString(b:_*)

  private def headersEnd: String = "\r\n\r\n"

  def unapply(b: Seq[Byte]): Option[(Seq[HttpHeader], Seq[Byte])] = {
    val regex = """(?s)(?:\r\n)?([\w-]+): ((?:(?!\r\n[\w-]+: )(?!\r\n\r\n).)+)""".r
    asByteString(b).utf8String.split(headersEnd, 2).toSeq match {
      case h +: rest ⇒
        val headers = regex.findAllMatchIn(h).collect {
          case regex(name, value) ⇒
            HttpHeader(name, value.lines.map(_.dropWhile(_ == ' ')).mkString("\r\n"))
        }
        Some(headers.toVector → ByteString(rest.mkString))

      case _ ⇒
        None
    }
  }
}
