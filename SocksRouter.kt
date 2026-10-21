package com.nexproxy.app

import java.io.*
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.*

/** Локальный SOCKS5-сервер: принимает трафик от tun2socks и гонит его через пул прокси. */
class SocksRouter(val ups: List<Up>, private val parallel: Boolean) {
    class Up(val host: String, val port: Int, val socks: Boolean) {
        @Volatile var failUntil = 0L
        @Volatile var latency = 0L
    }

    private val server = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val up = AtomicLong()
    val down = AtomicLong()
    private val rr = AtomicInteger()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var alive = true

    fun start() = pool.execute {
        while (alive) {
            try { val c = server.accept(); pool.execute { handle(c) } } catch (e: Exception) { if (!alive) break }
        }
    }

    fun stop() {
        alive = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    private fun rf(i: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) throw EOFException(); n += r }
    }

    private fun cl(vararg s: Socket?) = s.forEach { try { it?.close() } catch (_: Exception) {} }

    private fun handle(c: Socket) {
        var s: Socket? = null
        try {
            c.tcpNoDelay = true
            val i = c.getInputStream(); val o = c.getOutputStream()
            if (i.read() != 5) return
            repeat(i.read()) { i.read() }
            o.write(byteArrayOf(5, 0))
            val h = ByteArray(4); rf(i, h)
            if (h[1].toInt() != 1) { o.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return }
            val host = when (h[3].toInt()) {
                1 -> { val a = ByteArray(4); rf(i, a); InetAddress.getByAddress(a).hostAddress!! }
                3 -> { val a = ByteArray(i.read()); rf(i, a); String(a) }
                4 -> { val a = ByteArray(16); rf(i, a); InetAddress.getByAddress(a).hostAddress!! }
                else -> return
            }
            val p = ByteArray(2); rf(i, p)
            val port = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
            s = connectUpstream(host, port)
            if (s == null) { o.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return }
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()
            val up2 = s
            pool.execute {
                copy(c.getInputStream(), up2.getOutputStream(), up)
                try { up2.shutdownOutput() } catch (_: Exception) {}
            }
            copy(up2.getInputStream(), o, down)
        } catch (_: Exception) {
        } finally { cl(c, s) }
    }

    private fun copy(i: InputStream, o: OutputStream, cnt: AtomicLong) {
        val b = ByteArray(16384)
        try {
            while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); o.flush(); cnt.addAndGet(n.toLong()) }
        } catch (_: Exception) {}
    }

    private fun order(): List<Up> {
        val now = System.currentTimeMillis()
        val ok = ups.filter { it.failUntil < now }
        val l = if (ok.isEmpty()) ups else ok
        if (!parallel) return l
        val k = rr.getAndIncrement()
        return l.indices.map { l[(k + it) % l.size] }
    }

    private fun connectUpstream(host: String, port: Int): Socket? {
        for (p in order().take(4)) {
            val t0 = System.currentTimeMillis()
            try {
                val s = if (p.socks) viaSocks(p, host, port) else viaHttp(p, host, port)
                p.latency = System.currentTimeMillis() - t0
                return s
            } catch (e: Exception) { p.failUntil = System.currentTimeMillis() + 45_000 }
        }
        return null
    }

    private fun open(p: Up): Socket {
        val s = Socket(); s.tcpNoDelay = true
        s.connect(InetSocketAddress(p.host, p.port), 5000); s.soTimeout = 8000
        return s
    }

    private fun viaHttp(p: Up, host: String, port: Int): Socket {
        val s = open(p)
        try {
            val hp = if (host.contains(':')) "[$host]:$port" else "$host:$port"
            s.getOutputStream().apply { write("CONNECT $hp HTTP/1.1\r\nHost: $hp\r\n\r\n".toByteArray()); flush() }
            val i = s.getInputStream(); val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) { val b = i.read(); if (b < 0 || sb.length > 8192) throw IOException(); sb.append(b.toChar()) }
            if (sb.length < 12 || sb.substring(9, 12) != "200") throw IOException("bad")
            s.soTimeout = 0; return s
        } catch (e: Exception) { cl(s); throw e }
    }

    private fun viaSocks(p: Up, host: String, port: Int): Socket {
        val s = open(p)
        try {
            val o = s.getOutputStream(); val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val g = ByteArray(2); rf(i, g); if (g[0].toInt() != 5 || g[1].toInt() != 0) throw IOException()
            val hb = host.toByteArray()
            o.write(byteArrayOf(5, 1, 0, 3, hb.size.toByte()) + hb + byteArrayOf((port shr 8).toByte(), port.toByte())); o.flush()
            val r = ByteArray(4); rf(i, r); if (r[1].toInt() != 0) throw IOException()
            val skip = when (r[3].toInt()) { 1 -> 6; 4 -> 18; 3 -> i.read() + 2; else -> throw IOException() }
            rf(i, ByteArray(skip))
            s.soTimeout = 0; return s
        } catch (e: Exception) { cl(s); throw e }
    }
}
