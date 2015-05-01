package com.karasiq.parsers.socks.internal

import akka.util.ByteString
import com.karasiq.parsers.BytePacketFragment

private[socks] object Socks4AInvalidIP extends BytePacketFragment[Seq[Byte]] {
  def apply(): Seq[Byte] = ByteString(0x00, 0x00, 0x00, 0x01)

  override def toBytes: PartialFunction[Seq[Byte], Seq[Byte]] = {
    case bs if bs.length == 4 ⇒
      bs
  }

  override def fromBytes: PartialFunction[Seq[Byte], (Seq[Byte], Seq[Byte])] = {
    case bs @ (0x00 :: 0x00 :: 0x00 :: last :: rest) if last != 0x00 ⇒
      bs.take(4) → rest
  }
}
