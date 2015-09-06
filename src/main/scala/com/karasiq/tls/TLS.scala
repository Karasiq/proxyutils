package com.karasiq.tls

import java.io.{InputStream, OutputStream}
import java.net._
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.SecureRandom

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.tls._

import scala.collection.GenTraversableOnce
import scala.util.control.Exception

object TLS {
  private val logger = java.util.logging.Logger.getLogger("TLS")

  type CertificateChain = org.bouncycastle.crypto.tls.Certificate
  type Certificate = org.bouncycastle.asn1.x509.Certificate

  private def wrapException[T](message: String)(f: ⇒ T): T = {
    val catcher = Exception.allCatch.withApply { exc ⇒
      logger.severe(s"TLS error: $message (${exc.getMessage})")
      if (exc.isInstanceOf[TLSException]) throw exc
      else throw new TLSException(message, exc)
    }
    catcher(f)
  }

  private def wrapInput(connection: SocketChannel): InputStream = new InputStream {
    override def read(): Int = {
      val buffer = ByteBuffer.allocate(1)
      if (connection.read(buffer) == -1) -1 else {
        buffer.flip()
        buffer.get()
      }
    }

    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      val buffer = ByteBuffer.allocate(len)
      val length = connection.read(buffer)
      buffer.flip()
      buffer.get(b, off, length)
      length
    }
  }

  private def wrapOutput(connection: SocketChannel): OutputStream = new OutputStream {
    override def write(b: Int): Unit = {
      val buffer = ByteBuffer.allocate(1)
      buffer.put(b.toByte)
      buffer.flip()
      connection.write(buffer)
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      val buffer = ByteBuffer.allocate(len)
      buffer.put(b, off, len)
      buffer.flip()
      connection.write(buffer)
    }
  }

  @throws(classOf[TLSException])
  def serverWrapper(connection: SocketChannel, serverCertificate: CertificateChain, serverKey: AsymmetricCipherKeyPair): SocketChannel = {
    val protocol = new TlsServerProtocol(wrapInput(connection), wrapOutput(connection), new SecureRandom())
    val server = new DefaultTlsServer() {
      private val verifier = new TLSCertificateVerifier()

      override def getRSASignerCredentials: TlsSignerCredentials = wrapException("Could not provide server credentials") {
        new DefaultTlsSignerCredentials(context, serverCertificate, serverKey.getPrivate)
      }

      override def getCertificateRequest: CertificateRequest = {
        def asJavaVector(data: GenTraversableOnce[AnyRef]): java.util.Vector[AnyRef] = {
          val vector = new java.util.Vector[AnyRef]()
          data.foreach(vector.add)
          vector
        }

        if (!ConfigFactory.load().getBoolean("karasiq.tls.client-auth")) null else {
          val certificateTypes = Array(ClientCertificateType.rsa_sign, ClientCertificateType.dss_sign, ClientCertificateType.ecdsa_sign)

          val signatureAlgorithms = if (TlsUtils.isSignatureAlgorithmsExtensionAllowed(serverVersion)) {
            TlsUtils.getDefaultSupportedSignatureAlgorithms
          } else {
            null
          }

          val authorities = asJavaVector(verifier.iterator().collect {
            case cert: TLSKeyStore.CertificateEntry ⇒
              cert.certificate.getSubject
          })

          new CertificateRequest(certificateTypes, signatureAlgorithms, authorities)
        }
      }

      override def notifyClientCertificate(clientCertificate: CertificateChain): Unit = wrapException("Client certificate error") {
        val chain = clientCertificate.getCertificateList.toList
        if (chain.nonEmpty) {
          logger.info(s"Client certificate chain: ${chain.map(_.getSubject).mkString("; ")}")
        }

        if (!verifier.isChainValid(chain)) {
          val message: String = s"Invalid client certificate: ${chain.headOption.fold("<none>")(_.getSubject.toString)}"
          throw new TLSException(message)
        }
      }
    }

    wrapException("Error accepting connection") {
      protocol.accept(server)
      new SocketChannelWrapper(connection, protocol)
    }
  }

  @throws(classOf[TLSException])
  def clientWrapper(connection: SocketChannel, address: InetSocketAddress = null, clientCertificate: CertificateChain = null, clientKey: AsymmetricCipherKeyPair = null): SocketChannel = {
    val protocol = new TlsClientProtocol(wrapInput(connection), wrapOutput(connection), new SecureRandom())
    val client = new DefaultTlsClient() {
      override def getAuthentication: TlsAuthentication = new TlsAuthentication {
        override def getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials = wrapException("Could not provide client credentials") {
          if (clientCertificate != null && clientKey != null)
            new DefaultTlsSignerCredentials(context, clientCertificate, clientKey.getPrivate) // Ignores certificateRequest
          else
            null
        }

        override def notifyServerCertificate(serverCertificate: CertificateChain): Unit = wrapException("Server certificate error") {
          val verifier: TLSCertificateVerifier = new TLSCertificateVerifier()
          val chain: List[Certificate] = serverCertificate.getCertificateList.toList

          if (chain.nonEmpty) {
            logger.info(s"Server certificate chain: ${chain.map(_.getSubject).mkString("; ")}")
            if (address != null &&  !verifier.isHostValid(chain.head, address.getHostName)) {
              val message: String = s"Certificate hostname not match: ${address.getHostName}"
              throw new TLSException(message)
            }
          }

          if (!verifier.isChainValid(chain)) {
            val message: String = s"Invalid server certificate: ${chain.headOption.fold("<none>")(_.getSubject.toString)}"
            throw new TLSException(message)
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
