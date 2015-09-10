package com.karasiq.tls

import java.io.FileInputStream
import java.security.KeyStore

import com.karasiq.tls.TLS.{Certificate, CertificateChain}
import com.karasiq.tls.TLSKeyStore.{CertificateEntry, KeyEntry}
import com.karasiq.tls.internal.BCConversions._
import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

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

  def getKey(alias: String, password: String = TLSKeyStore.defaultPassword()): AsymmetricCipherKeyPair = {
    val key = keyStore.getKey(alias, password.toCharArray)
    key.toAsymmetricCipherKeyPair(getCertificate(alias).getSubjectPublicKeyInfo)
  }

  def getCertificate(alias: String): TLS.Certificate = {
    keyStore.getCertificate(alias).toTlsCertificate
  }

  def getCertificateChain(alias: String): TLS.CertificateChain = {
    new CertificateChain(keyStore.getCertificateChain(alias).map(_.toTlsCertificate))
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
