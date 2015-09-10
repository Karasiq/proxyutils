package com.karasiq.tls

object TLS {
  type CertificateChain = org.bouncycastle.crypto.tls.Certificate
  type Certificate = org.bouncycastle.asn1.x509.Certificate
  type CertificateKeyPair = org.bouncycastle.crypto.AsymmetricCipherKeyPair

  case class CertificateKey(certificateChain: CertificateChain, key: CertificateKeyPair)
}
