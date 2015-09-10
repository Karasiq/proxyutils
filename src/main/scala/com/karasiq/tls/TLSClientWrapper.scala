package com.karasiq.tls

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.security.SecureRandom

import com.karasiq.tls.internal.{SocketChannelWrapper, TLSUtils}
import org.bouncycastle.crypto.tls._

import scala.util.control.Exception

class TLSClientWrapper(verifier: TLSCertificateVerifier, address: InetSocketAddress = null) extends TLSConnectionWrapper {
  protected def getClientCertificate(certificateRequest: CertificateRequest): Option[TLS.CertificateKey] = None

  override def apply(connection: SocketChannel): SocketChannel = {
    val protocol = new TlsClientProtocol(SocketChannelWrapper.inputStream(connection), SocketChannelWrapper.outputStream(connection), new SecureRandom())
    val client = new DefaultTlsClient() {
      override def getMinimumVersion: ProtocolVersion = {
        TLSUtils.minVersion()
      }

      override def getCipherSuites: Array[Int] = {
        TLSUtils.defaultCipherSuites()
      }

      override def notifyHandshakeComplete(): Unit = {
        onHandshakeFinished()
      }

      override def getAuthentication: TlsAuthentication = new TlsAuthentication {
        override def getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials = wrapException("Could not provide client credentials") {
          getClientCertificate(certificateRequest)
            .map(ck ⇒ new DefaultTlsSignerCredentials(context, ck.certificateChain, ck.key.getPrivate)) // Ignores certificateRequest data
            .orNull
        }

        override def notifyServerCertificate(serverCertificate: TLS.CertificateChain): Unit = wrapException("Server certificate error") {
          val verifier: TLSCertificateVerifier = new TLSCertificateVerifier()
          val chain: List[TLS.Certificate] = serverCertificate.getCertificateList.toList

          if (chain.nonEmpty) {
            onInfo(s"Server certificate chain: ${chain.map(_.getSubject).mkString("; ")}")
            if (address != null &&  !verifier.isHostValid(chain.head, address.getHostName)) {
              throw new TLSException(s"Certificate hostname not match: ${address.getHostName}")
            }
          }

          if (!verifier.isChainValid(chain)) {
            throw new TLSException(s"Invalid server certificate: ${chain.headOption.fold("<none>")(_.getSubject.toString)}")
          }
        }
      }
    }

    Exception.allCatch.withApply(exc ⇒ throw new TLSException(s"Error connecting to server: $address", exc)) {
      protocol.connect(client)
      new SocketChannelWrapper(connection, protocol)
    }
  }
}
