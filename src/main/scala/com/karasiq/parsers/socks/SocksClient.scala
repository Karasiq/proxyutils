package com.karasiq.parsers.socks

import java.net.InetSocketAddress

import akka.util.ByteString
import com.karasiq.parsers._
import com.karasiq.parsers.socks.internal.{NullTerminatedString, _}

/**
 * Serializers for SOCKS client
 */
object SocksClient {
  sealed trait SocksVersion {
    def code: Byte
  }

  object SocksVersion extends ByteRange[SocksVersion] {
    def of(bs: ByteString): SocksVersion = apply(bs.head) // Extract from first byte

    case object SocksV4 extends SocksVersion {
      override def code: Byte = 0x04
    }

    case object SocksV5 extends SocksVersion {
      override def code: Byte = 0x05
    }

    override def fromByte: PartialFunction[Byte, SocksVersion] = {
      case 0x04 ⇒ SocksV4
      case 0x05 ⇒ SocksV5
    }

    override def toByte: PartialFunction[SocksVersion, Byte] = {
      case v ⇒ v.code
    }
  }

  sealed trait AuthMethod {
    def code: Byte
  }

  object AuthMethod extends ByteRange[AuthMethod] {
    case object NoAuth extends AuthMethod {
      override def code: Byte = 0
      override def toString: String = "No authentication"
    }

    case object GSSAPI extends AuthMethod {
      override def code: Byte = 1
      override def toString: String = "GSSAPI"
    }

    case object UsernamePassword extends AuthMethod {
      override def code: Byte = 2
      override def toString: String = "Username/Password"
    }

    case class IANA(override val code: Byte) extends AuthMethod {
      override def toString: String = s"Method assigned by IANA: $code"
    }
    case class PrivateUse(override val code: Byte) extends AuthMethod {
      override def toString: String = s"Method reserved for private use: $code"
    }

    override def fromByte: PartialFunction[Byte, AuthMethod] = {
      case 0x00 ⇒ NoAuth
      case 0x01 ⇒ GSSAPI
      case 0x02 ⇒ UsernamePassword
      case r if (0x03 to 0x7F).contains(r) ⇒ IANA(r)
      case r if (0x80 to 0xFE).contains(r) ⇒ PrivateUse(r)
    }

    override def toByte: PartialFunction[AuthMethod, Byte] = {
      case authMethod ⇒
        authMethod.code
    }
  }

  object AuthRequest extends ByteFragment[Seq[AuthMethod]] {
    override def fromBytes: Extractor = {
      case SocksVersion(SocksVersion.SocksV5) +: authMethodsCount +: bytes ⇒
        bytes.take(authMethodsCount).collect {
          case AuthMethod(method) ⇒
            method
        } → bytes.drop(authMethodsCount)
    }

    override def toBytes(authMethods: Seq[AuthMethod]): ByteString = {
      ByteString(SocksVersion(SocksVersion.SocksV5), authMethods.length.toByte) ++ authMethods.map(_.code)
    }
  }

  sealed trait Command {
    def code: Byte
  }

  object Command {
    case object TcpConnection extends Command {
      override def code: Byte = 0x01
    }
    case object TcpBind extends Command {
      override def code: Byte = 0x02
    }
    case object UdpAssociate extends Command {
      override def code: Byte = 0x03
    }

    def apply(b: Byte): Command = {
      unapply(b).getOrElse(throw new ParserException("Invalid SOCKS command: " + b))
    }

    def unapply(b: Byte): Option[Command] = {
      val pf: PartialFunction[Byte, Command] = {
        case 0x01 ⇒ TcpConnection
        case 0x02 ⇒ TcpBind
        case 0x03 ⇒ UdpAssociate
      }
      pf.lift.apply(b)
    }
  }

  object ConnectionRequest extends ByteFragment[(SocksVersion, Command, InetSocketAddress, String)] {
    override def fromBytes: Extractor = {
      case SocksVersion(SocksVersion.SocksV4) +: Command(command) +: (Address.V4(address, NullTerminatedString(userId, rest))) if command != Command.UdpAssociate ⇒
        (SocksVersion.SocksV4, command, address, userId) → rest

      case SocksVersion(SocksVersion.SocksV5) +: Command(command) +: 0x00 +: (Address.V5(address, rest)) ⇒
        (SocksVersion.SocksV5, command, address, "") → rest
    }

    private def socks4a(address: InetSocketAddress, userId: String): ByteString = {
      Port(address.getPort) ++ Socks4AInvalidIP() ++ NullTerminatedString(userId) ++ NullTerminatedString(address.getHostString)
    }

    override def toBytes(value: (SocksVersion, Command, InetSocketAddress, String)): ByteString = value match {
      case (socksVersion, command, address, userId) ⇒
        val head = ByteString(SocksVersion(socksVersion), command.code, 0x00)
        val parameters = socksVersion match {
          case SocksVersion.SocksV4 if address.isUnresolved ⇒ // SOCKS4A
            socks4a(address, userId)

          case v @ SocksVersion.SocksV4 ⇒ // SOCKS4
            Address(v, address) ++ NullTerminatedString(userId)

          case v @ SocksVersion.SocksV5 ⇒ // SOCKS5
            Address(v, address)
        }
        head ++ parameters
    }
  }

  object UsernameAuthRequest extends ByteFragment[(String, String)] {
    override def fromBytes: Extractor = {
      case 0x01 +: (LengthString(username, LengthString(password, rest))) ⇒
        (username, password) → rest
    }

    override def toBytes(value: (String, String)): ByteString = value match {
      case (username, password) ⇒
        ByteString(0x01) ++ LengthString(username) ++ LengthString(password)
    }
  }
}
