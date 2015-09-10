package com.karasiq.tls

import java.nio.channels.SocketChannel
import java.security.SecureRandom

import com.karasiq.tls.TLS.CertificateChain
import com.karasiq.tls.internal.{SocketChannelWrapper, TLSUtils}
import org.bouncycastle.crypto.tls._

class TLSServerWrapper(certificate: TLS.CertificateKey, clientAuth: Boolean = false, verifier: TLSCertificateVerifier = null) extends TLSConnectionWrapper {
  require(verifier != null || !clientAuth, "No client certificate verifier provided")

  @throws(classOf[TLSException])
  protected def onClientAuth(clientCertificate: CertificateChain): Unit = {
    val chain: List[TLS.Certificate] = clientCertificate.getCertificateList.toList
    if (chain.nonEmpty) {
      onInfo(s"Client certificate chain: ${chain.map(_.getSubject).mkString("; ")}")
    }

    if (clientAuth && !verifier.isChainValid(chain)) {
      throw new TLSException(s"Invalid client certificate: ${chain.headOption.fold("<none>")(_.getSubject.toString)}")
    }
  }

  def apply(connection: SocketChannel): SocketChannel = {
    val protocol = new TlsServerProtocol(SocketChannelWrapper.inputStream(connection), SocketChannelWrapper.outputStream(connection), new SecureRandom())
    val server = new DefaultTlsServer() {
      override def getMinimumVersion: ProtocolVersion = {
        TLSUtils.minVersion()
      }

      override def getMaximumVersion: ProtocolVersion = {
        TLSUtils.maxVersion()
      }

      override def getCipherSuites: Array[Int] = {
        TLSUtils.defaultCipherSuites()
      }

      override def notifyHandshakeComplete(): Unit = {
        onHandshakeFinished()
      }

      override def getRSASignerCredentials: TlsSignerCredentials = wrapException("Could not provide server credentials") {
        new DefaultTlsSignerCredentials(context, certificate.certificateChain, certificate.key.getPrivate)
      }

      override def getCertificateRequest: CertificateRequest = {
        if (clientAuth) {
          TLSUtils.certificateRequest(serverVersion, verifier)
        } else {
          null
        }
      }

      override def notifyClientCertificate(clientCertificate: CertificateChain): Unit = wrapException("Client certificate error") {
        onClientAuth(clientCertificate)
      }
    }

    wrapException("Error accepting connection") {
      protocol.accept(server)
      new SocketChannelWrapper(connection, protocol)
    }
  }
}
