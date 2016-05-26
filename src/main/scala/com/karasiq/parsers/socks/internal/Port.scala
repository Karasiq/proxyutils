package com.karasiq.parsers.socks.internal

import akka.util.ByteString
import com.karasiq.parsers.ByteFragment

private[socks] object Port extends ByteFragment[Int] {
  override def toBytes(port: Int): ByteString = {
    ByteFragment.create(2)(_.putShort(port.toShort))
  }

  override def fromBytes: Extractor = {
    case bytes if bytes.length >= 2 ⇒
      readPort(bytes) → bytes.drop(2)
  }

  @inline
  private def readPort(b: Seq[Byte]): Int = {
    val array: Array[Byte] = (ByteString(0x00, 0x00) ++ b.take(2)).toArray
    BigInt(array).intValue()
  }
}
