package com.karasiq.parsers.socks.internal

import akka.util.ByteString
import com.karasiq.parsers.ByteFragment

private[socks] object LengthString extends ByteFragment[String] {
  override def toBytes(s: String): ByteString = {
    ByteString(s.length.toByte) ++ ByteString(s)
  }

  override def fromBytes: Extractor = {
    case length +: string if string.length >= length ⇒
      ByteString(string.take(length).toArray).utf8String → string.drop(length)
  }
}
