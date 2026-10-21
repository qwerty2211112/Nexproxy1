(function () {
  const N = window.NexNative;
  if (!N) return;
  const PKG = { telegram: 'org.telegram.messenger', youtube: 'com.google.android.youtube', chrome: 'com.android.chrome',
    tiktok: 'com.zhiliaoapp.musically', discord: 'com.discord', termux: 'com.termux', pubg: 'com.tencent.ig' };
  const $ = id => document.getElementById(id);
  const origToggle = window.toggleVPNConnection;
  let busy = false, prev = null;
  const resetBtn = () => { $('power-text').innerText = 'Старт';
    $('power-icon').className = 'fa-solid fa-power-off text-4xl sm:text-5xl text-purple-400'; };
  const log = (t, c) => { const b = $('fetcher-log'); b.innerHTML += `<p class="${c}">${t}</p>`; b.scrollTop = b.scrollHeight; };

  window.toggleVPNConnection = function () {
    if (busy) return;
    if (isConnected) { N.disconnect(); origToggle(); return; }
    const active = proxyPool.filter(p => p.active && (p.proto === 'HTTP' || p.proto === 'SOCKS5'));
    if (!active.length) { showToast('Нет активных HTTP/SOCKS5 прокси. Запусти Парсер & Сканер.', 'info'); return; }
    const apps = appList.filter(a => a.routed && PKG[a.id]).map(a => PKG[a.id]);
    if (!apps.length) { showToast('Включи хотя бы одно приложение во вкладке «Приложения».', 'info'); return; }
    busy = true;
    $('power-text').innerText = 'Подключение...';
    $('power-icon').className = 'fa-solid fa-spinner fa-spin text-4xl sm:text-5xl text-brand-accent';
    N.connect(JSON.stringify({ proxies: active.map(p => ({ ip: p.ip, port: p.port, proto: p.proto })),
      parallel: proxyRoutingMode === 'parallel', apps }));
  };

  window.startSpeedSimulation = function () {
    prev = null;
    speedInterval = setInterval(() => {
      let s; try { s = JSON.parse(N.stats()); } catch (e) { return; }
      if (s.up === undefined) return;
      const t = Date.now();
      if (prev) {
        const dt = (t - prev.t) / 1000;
        $('stat-down').innerText = ((s.down - prev.d) / dt / 1048576).toFixed(2) + ' MB/s';
        $('stat-up').innerText = ((s.up - prev.u) / dt / 1048576).toFixed(2) + ' MB/s';
      }
      prev = { t, d: s.down, u: s.up };
      $('stat-ping').innerText = s.ping > 0 ? s.ping + ' ms' : '-- ms';
    }, 1000);
  };

  window.checkAllProxiesPing = function () {
    showToast('Проверка задержки (пинга) прокси...', 'info');
    N.ping(JSON.stringify(proxyPool.map(p => ({ id: p.id, ip: p.ip, port: p.port }))));
  };

  window.runProxyFetcher = function () {
    const btn = $('start-fetch-btn'); btn.disabled = true; btn.classList.add('opacity-50');
    $('fetcher-log').innerHTML = '<p class="text-purple-400">> Начинаем парсинг публичных репозиториев...</p>';
    $('fetcher-progress-bar').style.width = '0%'; $('fetcher-progress-text').innerText = 'Загрузка: 0%';
    N.fetch();
  };

  window.__native = {
    onConnect(ok, msg) {
      busy = false;
      if (ok) origToggle(); else { resetBtn(); showToast(msg || 'Не удалось подключиться', 'info'); }
    },
    onPing(arr) {
      arr.forEach(r => { const p = proxyPool.find(x => x.id === r.id); if (p) p.ping = r.ping < 0 ? 999 : r.ping; });
      renderDashboardProxies(); renderProxyList(); showToast('Пинг успешно обновлен!', 'success');
    },
    onFetch(e) {
      const btn = $('start-fetch-btn');
      if (e.t === 'downloaded') {
        log(`> Скачано ${e.total} прокси. Проверяю ${e.check} реальным запросом...`, 'text-purple-300');
      } else if (e.t === 'progress') {
        const pc = Math.round(e.done / e.total * 100);
        $('fetcher-progress-bar').style.width = pc + '%'; $('fetcher-progress-text').innerText = `Проверка: ${pc}%`;
      } else if (e.t === 'found') {
        log(`[+] Успех: ${e.ip}:${e.port} (Пинг: ${e.ms}ms)`, 'text-emerald-400');
      } else if (e.t === 'error') {
        log('[-] ' + e.msg, 'text-red-400'); btn.disabled = false; btn.classList.remove('opacity-50');
      } else if (e.t === 'done') {
        $('fetcher-progress-bar').style.width = '100%'; $('fetcher-progress-text').innerText = 'Готово!';
        const list = e.list.sort((a, b) => a.ping - b.ping);
        if (list.length) proxyPool.forEach(p => { if (p.id <= 5) p.active = false; });
        list.forEach((x, i) => {
          if (proxyPool.some(p => p.ip === x.ip && p.port === x.port)) return;
          proxyPool.unshift({ id: Date.now() + i, ip: x.ip, port: x.port, proto: x.proto, country: x.cc, flag: x.flag,
            ping: x.ping, active: i < 8, speed: x.ping < 80 ? 'Ultra' : x.ping < 200 ? 'Fast' : 'Medium' });
        });
        renderDashboardProxies(); renderProxyList(); updateProxyBadge();
        log(`> Парсинг завершен! Рабочих: ${list.length}. Добавлены в пул.`, 'text-brand-accent font-bold');
        showToast(list.length ? 'Пул пополнен рабочими прокси!' : 'Рабочих прокси не найдено, попробуй ещё раз', list.length ? 'success' : 'info');
        btn.disabled = false; btn.classList.remove('opacity-50');
      }
    }
  };

  if (N.running()) origToggle();
})();
