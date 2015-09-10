package com.karasiq.tls

import java.nio.channels.SocketChannel

import scala.util.control.Exception

trait TLSConnectionWrapper {
  protected def onHandshakeFinished(): Unit  = { }

  protected def onError(message: String, exc: Throwable): Unit = { }

  protected def onInfo(message: String): Unit = { }

  protected def wrapException[T](message: String)(f: ⇒ T): T = {
    val catcher = Exception.allCatch.withApply { exc ⇒
      onError(message, exc)
      if (exc.isInstanceOf[TLSException]) throw exc
      else throw new TLSException(message, exc)
    }
    catcher(f)
  }

  def apply(connection: SocketChannel): SocketChannel
}
