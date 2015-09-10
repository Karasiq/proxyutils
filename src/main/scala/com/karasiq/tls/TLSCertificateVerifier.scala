package com.karasiq.tls

import java.io.FileInputStream
import java.security.KeyStore
import java.util.Date

import com.typesafe.config.ConfigFactory
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder

import scala.annotation.tailrec
import scala.util.control.Exception

object TLSCertificateVerifier {
  def trustStore(path: String): KeyStore = {
    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)

    val inputStream = new FileInputStream(path)
    Exception.allCatch.andFinally(inputStream.close()) {
      trustStore.load(inputStream, null)
      trustStore
    }
  }

  def defaultTrustStore(): KeyStore = {
    val config = ConfigFactory.load().getConfig("karasiq.tls")
    this.trustStore(config.getString("trust-store"))
  }
}

class TLSCertificateVerifier(trustStore: KeyStore = TLSCertificateVerifier.defaultTrustStore()) extends TLSKeyStore(trustStore) {
  protected def isCertificateValid(certificate: x509.Certificate, issuer: x509.Certificate): Boolean = {
    val contentVerifierProvider = new JcaContentVerifierProviderBuilder()
      .setProvider("BC")
      .build(new X509CertificateHolder(issuer))

    val certHolder = new X509CertificateHolder(certificate)
    certHolder.isValidOn(new Date()) && certHolder.isSignatureValid(contentVerifierProvider)
  }

  protected def isCAValid(certificate: x509.Certificate): Boolean = {
    // CA authority
    val trusted = iterator().collect {
      case e: TLSKeyStore.CertificateEntry ⇒
        e.certificate
    }
    trusted.find(_.getSubject == certificate.getIssuer).fold(false) { ca ⇒
      isCertificateValid(certificate, ca) // Verify with stored root CA certificate
    }
  }

  @tailrec
  final def isChainValid(chain: List[x509.Certificate]): Boolean = {
    chain match {
      case cert :: issuer :: Nil ⇒
        isCertificateValid(cert, issuer) && isCAValid(issuer)

      case cert :: issuer :: rest ⇒
        isCertificateValid(cert, issuer) && isChainValid(issuer :: rest)

      case cert :: Nil ⇒
        isCAValid(cert)

      case _ ⇒
        false
    }
  }

  def isHostValid(certificate: x509.Certificate, hostName: String): Boolean = {
    val certHost: String = certificate.getSubject.getRDNs(BCStyle.CN).head.getFirst.getValue.toString

    @tailrec
    def check(actual: List[String], cert: List[String]): Boolean = {
      (actual, cert) match {
        case (_, "*" :: _) ⇒
          true

        case (actualPart :: actualRest, certPart :: certRest) ⇒
          actualPart == certPart && check(actualRest, certRest)

        case _ ⇒
          false
      }
    }

    hostName == certHost || check(hostName.split('.').toList.reverse, certHost.split('.').toList.reverse)
  }
}
