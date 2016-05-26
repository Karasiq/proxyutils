package com.karasiq.parsers.socks.internal

import akka.util.ByteString
import com.karasiq.parsers.ByteFragment

private[socks] object Socks4AInvalidIP extends ByteFragment[Seq[Byte]] {
  def apply(): Seq[Byte] = ByteString(0x00, 0x00, 0x00, 0x01)

  override def toBytes(value: Seq[Byte]): ByteString = {
    ByteString(value:_*)
  }

  override def fromBytes: PartialFunction[Seq[Byte], (Seq[Byte], Seq[Byte])] = {
    case bs @ (0x00 +: 0x00 +: 0x00 +: last +: rest) if last != 0x00 ⇒
      bs.take(4) → rest
  }
}
