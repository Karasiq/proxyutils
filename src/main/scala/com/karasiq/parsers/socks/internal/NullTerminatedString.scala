package com.karasiq.parsers.socks.internal

import akka.util.ByteString
import com.karasiq.parsers.ByteFragment

/**
 * Null-byte-terminated string extractor
 */
private[socks] object NullTerminatedString extends ByteFragment[String] {
  override def toBytes(s: String): ByteString = {
    ByteString(s) ++ ByteString(0)
  }

  override def fromBytes: Extractor = {
    case bytes if bytes.nonEmpty && bytes.contains(0) ⇒
      val (keep, drop) = bytes.splitAt(bytes.indexOf(0))
      ByteString(keep:_*).utf8String → drop.tail
  }
}
