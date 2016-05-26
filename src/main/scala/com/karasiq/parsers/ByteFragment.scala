package com.karasiq.parsers

import java.nio.ByteBuffer

import akka.util.ByteString

import scala.language.implicitConversions

/**
 * Generic bytes serializer/deserializer
 * @tparam T Result type
 */
trait ByteFragment[T] {
  final type Extractor = PartialFunction[Seq[Byte], (T, Seq[Byte])]

  def fromBytes: Extractor

  def toBytes(value: T): ByteString

  final def unapply(bytes: Seq[Byte]): Option[(T, Seq[Byte])] = {
    bytes match {
      case bs if fromBytes.isDefinedAt(bs) ⇒
        Some(fromBytes(bs))

      case _ ⇒
        None
    }
  }

  final def apply(t: T): ByteString = toBytes(t)
}

object ByteFragment {
  def wrap(f: ⇒ ByteBuffer): ByteString = {
    val buffer = f
    buffer.flip()
    ByteString(buffer)
  }

  def create(size: Int)(f: ByteBuffer ⇒ ByteBuffer): ByteString = {
    wrap(f(ByteBuffer.allocate(size)))
  }

  implicit def bytesToByteString(b: Seq[Byte]): ByteString = ByteString(b.toArray)
}
