package com.nexproxy.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.webkit.*
import org.json.*
import java.net.*
import java.util.concurrent.Executors
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var pending: String? = null

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = 0xFF06030E.toInt()
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "NexNative")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) {
                v.evaluateJavascript(assets.open("bridge.js").bufferedReader().readText(), null)
            }
        }
        ProxyVpnService.listener = { ok, msg ->
            js("window.__native.onConnect($ok, ${JSONObject.quote(msg)})")
        }
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun launch() {
        startService(Intent(this, ProxyVpnService::class.java).putExtra("cfg", pending))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        if (req == 1) {
            if (res == RESULT_OK) launch()
            else js("window.__native.onConnect(false, 'Разрешение VPN отклонено')")
        }
    }

    inner class Bridge {
        @JavascriptInterface fun running() = ProxyVpnService.running

        @JavascriptInterface fun connect(cfg: String) = runOnUiThread {
            pending = cfg
            val i = VpnService.prepare(this@MainActivity)
            if (i != null) startActivityForResult(i, 1) else launch()
        }

        @JavascriptInterface fun disconnect() {
            startService(Intent(this@MainActivity, ProxyVpnService::class.java).setAction("stop"))
        }

        @JavascriptInterface fun stats(): String {
            val r = ProxyVpnService.router ?: return "{}"
            val l = r.ups.filter { it.latency > 0 }.map { it.latency }
            return JSONObject().put("up", r.up.get()).put("down", r.down.get())
                .put("ping", if (l.isEmpty()) 0 else l.average().toInt()).toString()
        }

        @JavascriptInterface fun ping(json: String) { thread {
            val arr = JSONArray(json); val ex = Executors.newFixedThreadPool(16)
            val fs = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                ex.submit<JSONObject> {
                    val t = System.currentTimeMillis()
                    val ms = try {
                        Socket().use { s -> s.connect(InetSocketAddress(o.getString("ip"), o.getInt("port")), 3000) }
                        (System.currentTimeMillis() - t).toInt()
                    } catch (e: Exception) { -1 }
                    JSONObject().put("id", o.getLong("id")).put("ping", ms)
                }
            }
            val res = JSONArray(); fs.forEach { res.put(it.get()) }; ex.shutdown()
            js("window.__native.onPing($res)")
        } }

        @JavascriptInterface fun fetch() { thread {
            try { Fetcher.run { js("window.__native.onFetch($it)") } }
            catch (e: Exception) { js("window.__native.onFetch(${JSONObject().put("t", "error").put("msg", e.message ?: "ошибка")})") }
        } }
    }
}
