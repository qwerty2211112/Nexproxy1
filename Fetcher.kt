package com.nexproxy.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.*
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.*

object Fetcher {
    private val SRC = listOf(
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt" to false,
        "https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/socks5.txt" to true,
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt" to false,
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt" to true
    )

    private class Hit(val ip: String, val port: Int, val socks: Boolean, val ms: Int)

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Реальная проверка: HTTPS-запрос через прокси (для HTTP-прокси это CONNECT-туннель). */
    private fun check(ip: String, port: Int, socks: Boolean): Int? = try {
        val px = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(ip, port))
        val c = URL("https://www.gstatic.com/generate_204").openConnection(px) as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 4000
        val t = System.currentTimeMillis()
        val code = c.responseCode; c.disconnect()
        if (code == 204) (System.currentTimeMillis() - t).toInt() else null
    } catch (_: Exception) { null }

    private fun flag(cc: String) =
        if (cc.length == 2 && cc.all { it.isLetter() })
            cc.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("") else "🌐"

    fun run(emit: (JSONObject) -> Unit) {
        val all = ArrayList<Triple<String, Int, Boolean>>()
        for ((u, s) in SRC) try {
            get(u).lineSequence().forEach { l ->
                val p = l.trim().split(":")
                if (p.size == 2 && p[1].toIntOrNull() != null) all.add(Triple(p[0], p[1].toInt(), s))
            }
        } catch (_: Exception) {}
        if (all.isEmpty()) { emit(JSONObject().put("t", "error").put("msg", "Не удалось скачать списки (нет интернета?)")); return }
        val list = all.distinctBy { "${it.first}:${it.second}" }.shuffled().take(300)
        emit(JSONObject().put("t", "downloaded").put("total", all.size).put("check", list.size))

        val ex = Executors.newFixedThreadPool(50)
        val found = Collections.synchronizedList(ArrayList<Hit>())
        val done = AtomicInteger(); val stop = AtomicBoolean(false)
        val fs = list.map { (ip, port, socks) ->
            ex.submit {
                if (!stop.get()) {
                    val ms = check(ip, port, socks)
                    if (ms != null && found.size < 20) {
                        found.add(Hit(ip, port, socks, ms))
                        emit(JSONObject().put("t", "found").put("ip", ip).put("port", port).put("ms", ms))
                    }
                    val d = done.incrementAndGet()
                    if (d % 10 == 0) emit(JSONObject().put("t", "progress").put("done", d).put("total", list.size))
                    if (found.size >= 20) stop.set(true)
                }
            }
        }
        fs.forEach { try { it.get() } catch (_: Exception) {} }
        ex.shutdown()

        val cc = HashMap<String, String>()
        try {
            val c = URL("http://ip-api.com/batch?fields=query,countryCode").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 6000; c.readTimeout = 6000
            c.outputStream.use { it.write(JSONArray(found.map { h -> h.ip }).toString().toByteArray()) }
            val a = JSONArray(c.inputStream.bufferedReader().readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { cc[it.optString("query")] = it.optString("countryCode") }
        } catch (_: Exception) {}

        val out = JSONArray()
        found.forEach { h ->
            val code = cc[h.ip] ?: "??"
            out.put(JSONObject().put("ip", h.ip).put("port", h.port).put("proto", if (h.socks) "SOCKS5" else "HTTP")
                .put("ping", h.ms).put("cc", code).put("flag", flag(code)))
        }
        emit(JSONObject().put("t", "done").put("list", out))
    }
}
