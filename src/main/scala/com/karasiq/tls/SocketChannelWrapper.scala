package com.karasiq.tls

import java.io.FileDescriptor
import java.net.{Socket, SocketAddress, SocketOption}
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.channels.spi.AbstractSelectableChannel
import java.util

import org.bouncycastle.crypto.tls.TlsProtocol
import sun.nio.ch.{SelChImpl, SelectionKeyImpl}

final private[tls] class SocketChannelWrapper(connection: SocketChannel, protocol: TlsProtocol) extends SocketChannel(connection.provider()) with SelChImpl {
  @inline
  private def selChOp[T](f: SelChImpl ⇒ T): T = connection match {
    case sc: SelChImpl ⇒
      f(sc)

    case _ ⇒
      throw new IllegalArgumentException("Not selectable channel")
  }

  override def getFD: FileDescriptor = selChOp(_.getFD)

  override def kill(): Unit = selChOp(_.kill())

  override def translateAndSetInterestOps(i: Int, selectionKey: SelectionKeyImpl): Unit = selChOp(_.translateAndSetInterestOps(i, selectionKey))

  override def translateAndUpdateReadyOps(i: Int, selectionKey: SelectionKeyImpl): Boolean = selChOp(_.translateAndUpdateReadyOps(i, selectionKey))

  override def getFDVal: Int = selChOp(_.getFDVal)

  override def translateAndSetReadyOps(i: Int, selectionKey: SelectionKeyImpl): Boolean = selChOp(_.translateAndSetReadyOps(i, selectionKey))

  override def shutdownInput(): SocketChannel = connection.shutdownInput()

  override def isConnectionPending: Boolean = connection.isConnectionPending

  override def socket(): Socket = new SocketWrapper(connection.socket(), protocol)

  override def setOption[T](name: SocketOption[T], value: T): SocketChannel = connection.setOption(name, value)

  override def getLocalAddress: SocketAddress = connection.getLocalAddress

  override def write(src: ByteBuffer): Int = {
    val array = new Array[Byte](src.remaining())
    src.get(array)
    protocol.getOutputStream.write(array)
    array.length
  }

  override def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long = ???

  override def isConnected: Boolean = connection.isConnected

  override def getRemoteAddress: SocketAddress = connection.getRemoteAddress

  override def finishConnect(): Boolean = connection.finishConnect()

  override def read(dst: ByteBuffer): Int = {
    val array = new Array[Byte](dst.remaining())
    val read = protocol.getInputStream.read(array)
    if (read > 0) dst.put(array, 0, read)
    read
  }

  override def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long = ???

  override def connect(remote: SocketAddress): Boolean = connection.connect(remote)

  override def bind(local: SocketAddress): SocketChannel = connection.bind(local)

  override def shutdownOutput(): SocketChannel = connection.shutdownOutput()

  override def implConfigureBlocking(block: Boolean): Unit = {
    connection.configureBlocking(block)
  }

  override def implCloseSelectableChannel(): Unit = {
    protocol.close() // TLS close
    classOf[AbstractSelectableChannel].getMethods.find(_.getName == "implCloseSelectableChannel").foreach { method ⇒
      method.setAccessible(true)
      method.invoke(connection)
    }
  }

  override def getOption[T](name: SocketOption[T]): T = connection.getOption(name)

  override def supportedOptions(): util.Set[SocketOption[_]] = connection.supportedOptions()


}
