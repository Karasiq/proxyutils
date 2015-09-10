package com.karasiq.tls.internal

import com.karasiq.tls.{TLS, TLSKeyStore}
import com.typesafe.config.{Config, ConfigFactory}
import org.bouncycastle.crypto.tls._

import scala.collection.GenTraversableOnce
import scala.collection.JavaConversions._
import scala.util.Try

object TLSUtils {
  def certificateRequest(protocolVersion: ProtocolVersion, trustStore: TLSKeyStore): CertificateRequest = {
    @inline
    def asJavaVector(data: GenTraversableOnce[AnyRef]): java.util.Vector[AnyRef] = {
      val vector = new java.util.Vector[AnyRef]()
      data.foreach(vector.add)
      vector
    }

    val certificateTypes = Array(ClientCertificateType.rsa_sign, ClientCertificateType.dss_sign, ClientCertificateType.ecdsa_sign)

    val signatureAlgorithms = if (TlsUtils.isSignatureAlgorithmsExtensionAllowed(protocolVersion)) {
      TlsUtils.getDefaultSupportedSignatureAlgorithms
    } else {
      null
    }

    val authorities = asJavaVector(trustStore.iterator().collect {
      case cert: TLSKeyStore.CertificateEntry ⇒
        cert.certificate.getSubject
    })

    new CertificateRequest(certificateTypes, signatureAlgorithms, authorities)
  }

  def isInAuthorities(chain: TLS.CertificateChain, certificateRequest: CertificateRequest): Boolean = {
    chain.getCertificateList.exists { cert ⇒
      certificateRequest.getCertificateAuthorities.contains(cert.getSubject)
    }
  }

  private def openConfig(): Config = ConfigFactory.load().getConfig("karasiq.tls")

  /**
   * Loads cipher suites from config
   * @return BC cipher suites array
   */
  def defaultCipherSuites(): Array[Int] = {
    val config = openConfig()
    val cipherSuites = config.getStringList("cipher-suites")
    cipherSuites.flatMap { cs ⇒
      Try(classOf[CipherSuite].getField(cs).getInt(null)).toOption
    }.toArray
  }

  private def readVersion(v: String): ProtocolVersion = v match {
    case "TLSv1" ⇒
      ProtocolVersion.TLSv10

    case "TLSv1.1" ⇒
      ProtocolVersion.TLSv11

    case "TLSv1.2" ⇒
      ProtocolVersion.TLSv12

    case _ ⇒
      throw new IllegalArgumentException("Invalid TLS version: " + v)
  }

  def minVersion(): ProtocolVersion = {
    val config = openConfig()
    readVersion(config.getString("min-version"))
  }

  def maxVersion(): ProtocolVersion = {
    val config = openConfig()
    readVersion(config.getString("max-version"))
  }
}
