package com.karasiq.tls

import java.io.IOException

/**
 * Generic TLS exception
 * @param message Error message
 * @param cause Error cause
 */
class TLSException(message: String = null, cause: Throwable = null) extends IOException(message, cause) {
}
