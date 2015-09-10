package com.karasiq.tls.internal

import com.karasiq.tls.TLS
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.util.{PrivateKeyFactory, PublicKeyFactory}

private[tls] object BCConversions {
  implicit class KeyOps(key: java.security.Key) {
    private def convertPKCS8Key(data: Array[Byte], public: SubjectPublicKeyInfo): AsymmetricCipherKeyPair = {
      new AsymmetricCipherKeyPair(PublicKeyFactory.createKey(public), PrivateKeyFactory.createKey(data))
    }

//    private def convertRsaKey(rsa: RSAPrivateCrtKey): AsymmetricCipherKeyPair = {
//      val publicParameters = new RSAKeyParameters(false, rsa.getModulus, rsa.getPublicExponent)
//      val privateParameters = new RSAPrivateCrtKeyParameters(rsa.getModulus, rsa.getPublicExponent,
//        rsa.getPrivateExponent, rsa.getPrimeP, rsa.getPrimeQ, rsa.getPrimeExponentP, rsa.getPrimeExponentQ, rsa.getCrtCoefficient)
//      new AsymmetricCipherKeyPair(publicParameters, privateParameters)
//    }

    def toAsymmetricCipherKeyPair(public: SubjectPublicKeyInfo): AsymmetricCipherKeyPair = key match {
      // case rsa: java.security.interfaces.RSAPrivateCrtKey ⇒
      //  convertRsaKey(rsa)

      case privateKey: java.security.PrivateKey ⇒
        convertPKCS8Key(privateKey.getEncoded, public)

      case _ ⇒
        throw new IllegalArgumentException("Not supported")
    }
  }

  implicit class CertificateOps(certificate: java.security.cert.Certificate) {
    def toTlsCertificate: TLS.Certificate = {
      org.bouncycastle.asn1.x509.Certificate.getInstance(certificate.getEncoded)
    }
  }
}
