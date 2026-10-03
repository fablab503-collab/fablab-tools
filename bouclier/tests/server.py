#!/usr/bin/env python3
"""Tiny test web server: every host resolves here (Chromium --host-resolver-rules).
Logs each request so tests can see what reached the network."""
import http.server, json, sys, threading
LOG = []
PAGES = {
 ('news.test', '/'): ('text/html', '''<!doctype html><html><head>
<style>@font-face { font-family: TestFont; src: url("http://fonts.test/font.woff2") format("woff2"); } #fonty { font-family: TestFont, sans-serif; }</style>
</head><body>
<h1 id="real">Real article</h1>
<p id="fonty">Font test</p>
<div id="comments">comment section</div>
<script src="http://widgets.test/widget.js"></script>
<div class="sponsored-post" id="generic-hide">generic ad box</div>
<div class="keep-me" id="keep">keep</div>
<script src="http://www.googletagservices.com/tag/js/gpt.js"></script>
<script src="http://www.google-analytics.com/analytics.js"></script>
<img src="http://pagead2.googlesyndication.com/pagead/show_ads.gif">
<script src="http://cdn.news.test/app.js"></script>
<img src="http://news.test/logo.png">
</body></html>'''),
 ('news.test', '/popup'): ('text/html', '''<!doctype html><html><body>
<h1>Pop-up test</h1>
<script>
window.openAd = () => { window.__ad = window.open('http://11x11.com/landing'); return 'opened'; };
window.openOk = () => { window.__ok = window.open('http://docs.test/page'); return 'opened'; };
</script></body></html>'''),
 ('www.billetreduc.com', '/'): ('text/html', '''<!doctype html><html><body>
<div class="page-content">
<div class="maxget initialized" emp="7:1" region="1" page="home" id="br-top"><div class="desktop"><img src="http://www.billetreduc.com/zi/max/3016/22848/banner.jpg"><span class="pub__notifier">Sponsoris\u00e9</span></div></div>
<div class="home-hero"><div id="br-slider">slider</div><div class="home-pub-square" id="br-square"><div class="maxget"><img src="http://www.billetreduc.com/zi/max/838/22895/square.jpg"></div></div></div>
<div class="rail-with-ads"><div id="br-rail">shows</div><div class="rail-ads-slot ads-desktop-only" id="br-tile"><div class="maxget"><img src="http://www.billetreduc.com/cgi/max.aspx?p=home&amp;b=1"></div></div></div>
<img id="br-show" src="http://www.billetreduc.com/zg/n250/show.jpeg">
</div></body></html>'''),
 ('1001games.com', '/'): ('text/html', '''<!doctype html><html><body>
<div class="gc-leaderboard" id="specific-hide">specific ad</div><div class="game" id="game">game</div>
</body></html>'''),
 ('www.youtube.com', '/watch'): ('text/html', '''<!doctype html><html><body>
<ytd-reel-shelf-renderer id="shorts-shelf">Shorts shelf</ytd-reel-shelf-renderer>
<script>var ytInitialPlayerResponse = {"adPlacements":[{"x":1}],"playerAds":[1],"adSlots":[2],"videoDetails":{"videoId":"abc"}};
window.__initial = JSON.stringify(ytInitialPlayerResponse);</script>
<script>
(async () => {
  const r = await fetch('/youtubei/v1/player?key=1', {method:'POST', body:'{}'});
  const j = await r.json();
  window.__fetchJson = JSON.stringify(j);
  const t = await (await fetch('/youtubei/v1/player?key=2', {method:'POST', body:'{}'})).text();
  window.__fetchText = t;
  const parsed = JSON.parse('{"playerResponse":{"adPlacements":[1],"videoDetails":{}},"other":1}');
  window.__parse = JSON.stringify(parsed);
  const x = new XMLHttpRequest(); x.open('POST', '/youtubei/v1/player?key=3');
  x.onload = async () => {
    window.__xhr = x.responseText;
    const f = document.createElement('iframe');
    document.body.appendChild(f);
    try {
      window.__iframeFetch = await (await f.contentWindow.fetch('/youtubei/v1/player?key=4', {method:'POST', body:'{}'})).text();
      window.__iframeParse = JSON.stringify(f.contentWindow.JSON.parse('{"adPlacements":[1],"x":1}'));
    } catch (e) { window.__iframeFetch = 'ERR ' + e; }
    window.ytcfg = {data_: {}};
    window.ytcfg.data_.EXPERIMENT_FLAGS = {all_web_enable_network_machine: true, all_web_network_machine_raw_request: true, keep: 7};
    window.__flags = JSON.stringify([window.ytcfg.data_.EXPERIMENT_FLAGS.all_web_enable_network_machine, window.ytcfg.data_.EXPERIMENT_FLAGS.all_web_network_machine_raw_request, window.ytcfg.data_.EXPERIMENT_FLAGS.keep]);
    const t0 = performance.now();
    setTimeout(Function.prototype.bind.call(function(){ window.__timer = performance.now() - t0; window.__done = true; }, null), 17000);
  };
  x.send('{}');
})();
</script></body></html>'''),
}
PLAYER_JSON = json.dumps({"responseContext": {}, "adPlacements": [{"ad": 1}], "adSlots": [{"s": 1}], "playerAds": [1], "videoDetails": {"videoId": "abc"}})

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _serve(self):
        host = (self.headers.get('Host') or '').split(':')[0]
        path = self.path.split('?')[0]
        LOG.append(f"{self.command} {host}{self.path}")
        if path == '/__log':
            body = json.dumps(LOG).encode(); ctype = 'application/json'
        elif path == '/__reset':
            LOG.clear(); body = b'ok'; ctype = 'text/plain'
        elif (host, path) in PAGES:
            ctype, text = PAGES[(host, path)]; body = text.encode()
        elif path.startswith('/youtubei/v1/player'):
            body = PLAYER_JSON.encode(); ctype = 'application/json'
        elif path.endswith('.js'):
            body = b'window.__loaded = (window.__loaded||[]).concat(document.currentScript && document.currentScript.src);'; ctype = 'application/javascript'
        elif path.endswith('.woff2'):
            body = b'wOF2'; ctype = 'font/woff2'
        elif path.endswith(('.png', '.gif')):
            body = bytes.fromhex('47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b'); ctype = 'image/gif'
        else:
            body = b'<html><body>ok</body></html>'; ctype = 'text/html'
        self.send_response(200)
        self.send_header('Content-Type', ctype)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.end_headers()
        self.wfile.write(body)
    do_GET = _serve
    do_POST = _serve

http.server.ThreadingHTTPServer(('127.0.0.1', 80), H).serve_forever()
