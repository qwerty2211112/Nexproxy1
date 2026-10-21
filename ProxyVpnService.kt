package com.nexproxy.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File

class ProxyVpnService : VpnService() {
    companion object {
        @Volatile var router: SocksRouter? = null
        @Volatile var running = false
        @Volatile var listener: ((Boolean, String) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || i.action == "stop") { stopAll(); return START_NOT_STICKY }
        teardown()
        try {
            start(JSONObject(i.getStringExtra("cfg")!!))
            running = true
            listener?.invoke(true, "")
        } catch (e: Throwable) {
            stopAll()
            listener?.invoke(false, e.message ?: "Ошибка запуска VPN")
        }
        return START_NOT_STICKY
    }

    private fun start(cfg: JSONObject) {
        val arr = cfg.getJSONArray("proxies")
        val ups = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SocksRouter.Up(o.getString("ip"), o.getInt("port"), o.getString("proto").equals("SOCKS5", true))
        }
        require(ups.isNotEmpty()) { "Нет активных прокси" }
        val r = SocksRouter(ups, cfg.getBoolean("parallel")); router = r; r.start()

        val b = Builder().setSession("NexProxy").setMtu(8500)
            .addAddress("10.255.0.1", 30).addAddress("fc00::1", 126)
            .addRoute("0.0.0.0", 0).addRoute("::", 0)
            .addDnsServer("198.18.0.2")
        val apps = cfg.getJSONArray("apps"); var added = 0
        for (k in 0 until apps.length()) try { b.addAllowedApplication(apps.getString(k)); added++ } catch (_: Exception) {}
        check(added > 0) { "Выбранные приложения не установлены на телефоне" }
        val t = b.establish() ?: error("VPN не разрешён")

        val conf = File(filesDir, "tproxy.yml")
        conf.writeText("""
tunnel:
  mtu: 8500
  ipv4: 10.255.0.2
  ipv6: 'fc00::2'
socks5:
  port: ${r.port}
  address: 127.0.0.1
mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000
misc:
  log-level: warn
""".trimStart())
        TProxyService.TProxyStartService(conf.absolutePath, t.fd)
        tun = t
    }

    private fun teardown() {
        running = false
        if (tun != null) try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        router?.stop(); router = null
    }

    private fun stopAll() { teardown(); stopSelf() }
    override fun onRevoke() { stopAll() }
    override fun onDestroy() { teardown(); super.onDestroy() }
}
