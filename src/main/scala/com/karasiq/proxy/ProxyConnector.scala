package com.karasiq.proxy

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

import akka.util.ByteString
import com.karasiq.networkutils.SocketChannelWrapper._
import com.karasiq.networkutils.http.headers.{HttpHeader, `Proxy-Authorization`}
import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.parsers.http.{HttpConnect, HttpResponse}
import com.karasiq.parsers.socks.SocksClient.SocksVersion
import com.karasiq.parsers.socks.SocksClient.SocksVersion._
import com.karasiq.parsers.socks.{SocksClient, SocksServer}
import com.karasiq.tls.TLS.CertificateKey
import com.karasiq.tls.internal.TLSUtils
import com.karasiq.tls.{TLS, TLSCertificateVerifier, TLSClientWrapper, TLSKeyStore}
import org.bouncycastle.crypto.tls.CertificateRequest

import scala.language.implicitConversions

abstract class ProxyConnector {
  @throws[ProxyException]("if connection failed")
  def connect(socket: SocketChannel, destination: InetSocketAddress): SocketChannel
}

object ProxyConnector {
  def apply(protocol: String, proxy: Option[Proxy] = None): ProxyConnector = {
    if (protocol.startsWith("tls-")) new TLSProxyConnector(protocol.drop(4), proxy)
    else protocol match {
      case "socks" | "socks5" ⇒ new SocksProxyConnector(SocksV5, proxy)
      case "socks4" ⇒ new SocksProxyConnector(SocksV4, proxy)
      case "http" | "https" | "" ⇒ new HttpProxyConnector(proxy)
      case p ⇒ throw new IllegalArgumentException(s"Proxy protocol not supported: $p")
    }
  }

  def apply(proxy: Proxy): ProxyConnector = {
    require(proxy != null, "Invalid proxy")
    apply(proxy.scheme, Some(proxy))
  }
}

class TLSProxyConnector(protocol: String, proxy: Option[Proxy] = None) extends ProxyConnector {
  private def stripProxy(proxy: Option[Proxy]): Option[Proxy] = {
    proxy.map { proxy ⇒
      new Proxy {
        override def scheme: String = {
          if (proxy.scheme.startsWith("tls-")) {
            proxy.scheme.drop(4)
          } else {
            proxy.scheme
          }
        }

        override def host: String = proxy.host

        override def userInfo: Option[String] = None

        override def port: Int = proxy.port
      }
    }
  }

  @throws[ProxyException]("if connection failed")
  override def connect(socket: SocketChannel, destination: InetSocketAddress): SocketChannel = {
    val certificate: Option[TLS.CertificateKey] = proxy.flatMap(_.userInfo).map(_.split(':').toList) match {
      case Some(keyName :: password :: Nil) ⇒
        val keyStore = new TLSKeyStore()
        keyStore.getEntry(keyName) match {
          case Some(k: TLSKeyStore.KeyEntry) ⇒
            Some(TLS.CertificateKey(k.chain, k.keyPair(password)))

          case _ ⇒
            throw new IllegalArgumentException("Key not found: " + keyName)
        }

      case Some(keyName :: Nil) ⇒
        val keyStore = new TLSKeyStore()
        keyStore.getEntry(keyName) match {
          case Some(k: TLSKeyStore.KeyEntry) ⇒
            Some(TLS.CertificateKey(k.chain, k.keyPair(TLSKeyStore.defaultPassword())))

          case _ ⇒
            throw new IllegalArgumentException("Key not found: " + keyName)
        }

      case _ ⇒
        None
    }

    val tlsSocket = new TLSClientWrapper(new TLSCertificateVerifier(), proxy.map(_.toInetSocketAddress).orNull) {
      override protected def getClientCertificate(certificateRequest: CertificateRequest): Option[CertificateKey] = {
        certificate.collect {
          case cert if TLSUtils.isInAuthorities(cert.certificateChain, certificateRequest) ⇒
            cert
        }
      }
    }

    val connector = ProxyConnector(protocol, stripProxy(proxy))
    connector.connect(tlsSocket(socket), destination)
  }
}

class HttpProxyConnector(proxy: Option[Proxy] = None) extends ProxyConnector {
  @throws[ProxyException]("if connection failed")
  override def connect(socket: SocketChannel, destination: InetSocketAddress): SocketChannel = {
    val auth: Seq[HttpHeader] = proxy.flatMap(_.userInfo).map(userInfo ⇒ `Proxy-Authorization`.basic(userInfo)).toSeq
    socket.writeRead(HttpConnect(destination, auth)) match {
      case HttpResponse((status, headers)) ⇒
        if (status.code != 200) throw new ProxyException(s"HTTP CONNECT failed: ${status.code} ${status.message}")
        socket

      case bs: ByteString ⇒
        throw new ProxyException(s"Bad HTTPS proxy response: ${bs.utf8String}")
    }
  }
}

class SocksProxyConnector(version: SocksVersion, proxy: Option[Proxy] = None) extends ProxyConnector {
  import SocksClient._
  import SocksServer._

  private def authInfo: (String, String) = {
    proxy.flatMap(_.userInfo).map(_.split(":", 2).toList) match {
      case Some(userName :: password :: Nil) ⇒
        userName → password

      case _ ⇒
        "" → ""
    }
  }

  protected def socks5Auth(socket: SocketChannel, authMethod: AuthMethod): Unit = authMethod match {
    case AuthMethod.NoAuth ⇒
      // Pass

    case AuthMethod.UsernamePassword if proxy.exists(_.userInfo.isDefined) ⇒
      val (userName, password) = authInfo
      socket.writeRead(UsernameAuthRequest((userName, password))) match {
        case AuthStatusResponse(0x00) ⇒
          // Success

        case _ ⇒
          throw new ProxyException("SOCKS authentication rejected")
      }

    case m ⇒
      throw new ProxyException(s"SOCKS authentication not supported: $m")
  }

  @throws[ProxyException]("if connection failed")
  override def connect(socket: SocketChannel, destination: InetSocketAddress): SocketChannel = {
    version match {
      case SocksVersion.SocksV5 ⇒
        socket.writeRead(AuthRequest(Seq(AuthMethod.NoAuth))) match {
          case AuthMethodResponse(authMethod) ⇒
            socks5Auth(socket, authMethod)
            socket.writeRead(ConnectionRequest((SocksVersion.SocksV5, Command.TcpConnection, destination, ""))) match {
              case ConnectionStatusResponse((SocksVersion.SocksV5, address, status)) ⇒
                if(status != Codes.Socks5.REQUEST_GRANTED) throw new ProxyException(s"SOCKS request rejected: $status")
                socket

              case bs ⇒
                throw new ProxyException(s"Bad response from SOCKS5 server: $bs")
            }

          case bs ⇒
            throw new ProxyException(s"Bad response from SOCKS5 server: $bs")
        }

      case SocksVersion.SocksV4 ⇒
        socket.writeRead(ConnectionRequest((SocksVersion.SocksV4, Command.TcpConnection, destination, authInfo._1))) match {
          case ConnectionStatusResponse((SocksVersion.SocksV4, address, status)) ⇒
            if(status != Codes.Socks4.REQUEST_GRANTED) throw new ProxyException(s"SOCKS request rejected: $status")
            socket

          case _ ⇒
            throw new ProxyException("Bad response from SOCKS4 server")
        }
    }
  }
}