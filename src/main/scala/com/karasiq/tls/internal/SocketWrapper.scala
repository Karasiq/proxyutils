package com.karasiq.tls.internal

import java.io.{InputStream, OutputStream}
import java.net.{InetAddress, Socket, SocketAddress}
import java.nio.channels.SocketChannel

import org.bouncycastle.crypto.tls.TlsProtocol

final private[tls] class SocketWrapper(connection: Socket, protocol: TlsProtocol) extends Socket {
  override def shutdownInput(): Unit = connection.shutdownInput()

  override def getSoLinger: Int = connection.getSoLinger

  override def getRemoteSocketAddress: SocketAddress = connection.getRemoteSocketAddress

  override def setReceiveBufferSize(size: Int): Unit = connection.setReceiveBufferSize(size)

  override def getSoTimeout: Int = connection.getSoTimeout

  override def setOOBInline(on: Boolean): Unit = connection.setOOBInline(on)

  override def getKeepAlive: Boolean = connection.getKeepAlive

  override def getLocalPort: Int = connection.getLocalPort

  override def setSoLinger(on: Boolean, linger: Int): Unit = connection.setSoLinger(on, linger)

  override def getReceiveBufferSize: Int = connection.getReceiveBufferSize

  override def getTcpNoDelay: Boolean = connection.getTcpNoDelay

  override def setTrafficClass(tc: Int): Unit = connection.setTrafficClass(tc)

  override def getLocalSocketAddress: SocketAddress = connection.getLocalSocketAddress

  override def sendUrgentData(data: Int): Unit = connection.sendUrgentData(data)

  override def getLocalAddress: InetAddress = connection.getLocalAddress

  override def setKeepAlive(on: Boolean): Unit = connection.setKeepAlive(on)

  override def isBound: Boolean = connection.isBound

  override def setPerformancePreferences(connectionTime: Int, latency: Int, bandwidth: Int): Unit = connection.setPerformancePreferences(connectionTime, latency, bandwidth)

  override def getTrafficClass: Int = connection.getTrafficClass

  override def setSoTimeout(timeout: Int): Unit = connection.setSoTimeout(timeout)

  override def getOutputStream: OutputStream = protocol.getOutputStream

  override def getChannel: SocketChannel = connection.getChannel

  override def isConnected: Boolean = connection.isConnected

  override def isInputShutdown: Boolean = connection.isInputShutdown

  override def getReuseAddress: Boolean = connection.getReuseAddress

  override def getPort: Int = connection.getPort

  override def close(): Unit = {
    protocol.close()
    connection.close()
  }

  override def setTcpNoDelay(on: Boolean): Unit = connection.setTcpNoDelay(on)

  override def setSendBufferSize(size: Int): Unit = connection.setSendBufferSize(size)

  override def setReuseAddress(on: Boolean): Unit = connection.setReuseAddress(on)

  override def getSendBufferSize: Int = connection.getSendBufferSize

  override def isClosed: Boolean = connection.isClosed

  override def connect(endpoint: SocketAddress): Unit = connection.connect(endpoint)

  override def connect(endpoint: SocketAddress, timeout: Int): Unit = connection.connect(endpoint, timeout)

  override def getInetAddress: InetAddress = connection.getInetAddress

  override def bind(bindpoint: SocketAddress): Unit = connection.bind(bindpoint)

  override def shutdownOutput(): Unit = connection.shutdownOutput()

  override def getOOBInline: Boolean = connection.getOOBInline

  override def isOutputShutdown: Boolean = connection.isOutputShutdown

  override def toString: String = connection.toString

  override def getInputStream: InputStream = protocol.getInputStream
}
