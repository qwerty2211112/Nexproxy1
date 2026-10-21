package com.nexproxy.app

class TProxyService {
    companion object {
        init { System.loadLibrary("hev-socks5-tunnel") }
        @JvmStatic external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic external fun TProxyStopService()
    }
}
