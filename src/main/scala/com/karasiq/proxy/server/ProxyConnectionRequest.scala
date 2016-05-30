package com.karasiq.proxy.server

import java.net.InetSocketAddress

case class ProxyConnectionRequest(scheme: String, address: InetSocketAddress)
