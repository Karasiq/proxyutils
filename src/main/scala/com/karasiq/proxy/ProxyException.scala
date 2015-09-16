package com.karasiq.proxy

import java.io.IOException

/**
 * Generic proxy exception
 */
class ProxyException(message: String = null, cause: Throwable = null) extends IOException(message, cause)
