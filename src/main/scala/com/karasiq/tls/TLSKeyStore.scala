package com.karasiq.tls

import java.io.FileInputStream
import java.security.interfaces.RSAPrivateCrtKey
import java.security.{KeyStore, PrivateKey}

import com.karasiq.tls.TLS.{Certificate, CertificateChain}
import com.karasiq.tls.TLSKeyStore.{CertificateEntry, KeyEntry}
import com.typesafe.config.ConfigFactory
import org.bouncycastle.asn1.x509
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params._
import org.bouncycastle.crypto.util.{PrivateKeyFactory, PublicKeyFactory}

import scala.collection.JavaConversions._
import scala.util.control.Exception

object TLSKeyStore {
  sealed trait Entry {
    def alias: String
  }

  sealed trait CertificateEntry extends Entry {
    def certificate: TLS.Certificate
    def chain: TLS.CertificateChain
  }

  sealed trait KeyEntry extends Entry with CertificateEntry {
    def keyPair(password: String = defaultPassword()): AsymmetricCipherKeyPair
  }

  def keyStore(path: String, password: String): KeyStore = {
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)

    val inputStream = new FileInputStream(path)
    Exception.allCatch.andFinally(inputStream.close()) {
      keyStore.load(inputStream, password.toCharArray)
      keyStore
    }
  }

  def defaultKeyStore(): KeyStore = {
    val config = ConfigFactory.load().getConfig("karasiq.tls")
    this.keyStore(config.getString("key-store"), this.defaultPassword())
  }

  def defaultPassword(): String = {
    val config = ConfigFactory.load().getConfig("karasiq.tls")
    config.getString("key-store-pass")
  }
}

class TLSKeyStore(keyStore: KeyStore = TLSKeyStore.defaultKeyStore()) {
  def contains(alias: String): Boolean = {
    keyStore.containsAlias(alias)
  }

  private def convertPKCS8Key(data: Array[Byte], public: SubjectPublicKeyInfo): AsymmetricCipherKeyPair = {
    new AsymmetricCipherKeyPair(PublicKeyFactory.createKey(public), PrivateKeyFactory.createKey(data))
  }

  private def convertRsaKey(rsa: RSAPrivateCrtKey): AsymmetricCipherKeyPair = {
    val publicParameters = new RSAKeyParameters(false, rsa.getModulus, rsa.getPublicExponent)
    val privateParameters = new RSAPrivateCrtKeyParameters(rsa.getModulus, rsa.getPublicExponent,
      rsa.getPrivateExponent, rsa.getPrimeP, rsa.getPrimeQ, rsa.getPrimeExponentP, rsa.getPrimeExponentQ, rsa.getCrtCoefficient)
    new AsymmetricCipherKeyPair(publicParameters, privateParameters)
  }

  def getKey(alias: String, password: String = TLSKeyStore.defaultPassword()): AsymmetricCipherKeyPair = {
    val key = keyStore.getKey(alias, password.toCharArray)
    key match {
      case rsa: RSAPrivateCrtKey ⇒
        convertRsaKey(rsa)

      case privateKey: PrivateKey ⇒
        convertPKCS8Key(privateKey.getEncoded, getCertificate(alias).getSubjectPublicKeyInfo)

      case _ ⇒
        throw new IllegalArgumentException("Not supported")
    }
  }

  private def convertCertificate(javaCert: java.security.cert.Certificate): TLS.Certificate = {
    x509.Certificate.getInstance(javaCert.getEncoded)
  }

  def getCertificate(alias: String): TLS.Certificate = {
    convertCertificate(keyStore.getCertificate(alias))
  }

  def getCertificateChain(alias: String): TLS.CertificateChain = {
    new CertificateChain(keyStore.getCertificateChain(alias).map(convertCertificate))
  }

  def getEntry(alias: String): Option[TLSKeyStore.Entry] = {
    val pf: PartialFunction[String, TLSKeyStore.Entry] = {
      case a if keyStore.isKeyEntry(a) ⇒
        new KeyEntry {
          override def chain: CertificateChain = getCertificateChain(a)

          override def certificate: Certificate = getCertificate(a)

          override def keyPair(password: String): AsymmetricCipherKeyPair = getKey(a, password)

          override def alias: String = a
        }

      case a if keyStore.isCertificateEntry(a) ⇒
        new CertificateEntry {
          override def chain: CertificateChain = getCertificateChain(a)

          override def certificate: Certificate = getCertificate(a)

          override def alias: String = a
        }
    }
    pf.lift(alias)
  }

  def iterator(): Iterator[TLSKeyStore.Entry] = {
    keyStore.aliases().toIterator.flatMap(getEntry)
  }
}
