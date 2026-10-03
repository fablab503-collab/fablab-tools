#!/usr/bin/env python3
"""Demo web for App Store screenshots: "The Daily Example" (daily.example) and its French twin
"Le Quotidien Exemple" (quotidien.example), a made-up newspaper with the ad spaces and ad-network
scripts a real news site has. Every host resolves here (Chromium is started with
--host-resolver-rules), so the page is the same everywhere and shows no real brand.

usage: demo_server.py [port]   (default 80; capture.mjs starts it)
"""
import http.server
import sys

ADS = {
    "en": {
        "leader": '''<div class="creative flights"><b>SkyCheap</b><span>Flights from <em>€9</em> · this weekend only</span><i>Book now ›</i></div>''',
        "side": '''<div class="creative watch"><small>SPONSORED</small><b>Smartwatch X</b><em>-70%</em><span>Today only. 214 left in stock.</span><i>Shop ›</i></div>''',
        "inline": '''<div class="creative loan"><small>Advertisement</small><b>Need cash fast?</b><span>Approval in 2 minutes. No questions asked.</span><i>Apply ›</i></div>''',
    },
    "fr": {
        "leader": '''<div class="creative flights"><b>SkyCheap</b><span>Vols dès <em>9&nbsp;€</em> · ce week-end seulement</span><i>Réserver ›</i></div>''',
        "side": '''<div class="creative watch"><small>SPONSORISÉ</small><b>Montre X</b><em>-70&nbsp;%</em><span>Aujourd’hui seulement. Plus que 214 en stock.</span><i>Acheter ›</i></div>''',
        "inline": '''<div class="creative loan"><small>Publicité</small><b>Besoin d’argent vite&nbsp;?</b><span>Réponse en 2 minutes. Sans justificatif.</span><i>Demander ›</i></div>''',
    },
}

# the page's words, in English and French (the markup and the ad spaces are the same)
WORDS = {
    "en": {
        "lang": "en", "host": "daily.example", "title": "The Daily Example", "date": "Wednesday 23 September 2026", "account": "Subscribe · Sign in",
        "motto": "Independent news since 1926",
        "nav": ["World", "Cities", "Science", "Business", "Culture", "Sport", "Weather"],
        "brief": "Get the Morning Briefing", "brief_text": "The day's news in five minutes, every morning at 7.", "brief_button": "Sign up free",
        "kicker": "Cities", "lead": "Bike lanes double as the city goes car-free on Sundays",
        "standfirst": "Traffic fell by a third in the first month, and cafés on the old ring road say business has never been better.",
        "credit": "The ring road on the first car-free Sunday.",
        "p1": "When the council closed the ring road to cars every Sunday, shop owners feared the worst. Six weeks later, the pavements are full and the new cycle lanes carry more than twelve thousand trips a day.",
        "p2": "The plan will be reviewed in the spring, but a survey of residents found broad support, especially among families with young children.",
        "more": ["Night trains are back, and they are full", "A quiet revolution in the school canteen", "Five walks for the first cool weekend"],
        "sponsored": "Sponsored stories you may like",
        "tiles": [("Doctors can't explain this one simple trick", "Sponsored · HealthyToday"),
                  ("This new gadget is selling out everywhere", "Sponsored · GadgetDeals"),
                  ("You won't believe what these stars look like now", "Sponsored · Buzzly")],
        "most_read": "Most read",
        "read": ["Heat pump grants: who can apply", "The bakery that never closes", "Rain returns on Friday", "A library opens in the old station"],
    },
    "fr": {
        "lang": "fr", "host": "quotidien.example", "title": "Le Quotidien Exemple", "date": "Mercredi 23 septembre 2026", "account": "S’abonner · Se connecter",
        "motto": "Un journal indépendant depuis 1926",
        "nav": ["Monde", "Villes", "Sciences", "Économie", "Culture", "Sport", "Météo"],
        "brief": "Recevez le Brief du matin", "brief_text": "L’essentiel de l’actualité en cinq minutes, chaque matin à 7&nbsp;h.", "brief_button": "S’inscrire gratuitement",
        "kicker": "Villes", "lead": "Deux fois plus de pistes cyclables&nbsp;: la ville sans voitures le dimanche",
        "standfirst": "La circulation a baissé d’un tiers dès le premier mois, et les cafés de l’ancien périphérique n’ont jamais eu autant de clients.",
        "credit": "Le périphérique lors du premier dimanche sans voitures.",
        "p1": "Quand la mairie a fermé le périphérique aux voitures chaque dimanche, les commerçants craignaient le pire. Six semaines plus tard, les trottoirs sont pleins et les nouvelles pistes cyclables comptent plus de douze mille trajets par jour.",
        "p2": "Le dispositif sera réexaminé au printemps, mais une enquête auprès des habitants montre un large soutien, surtout chez les familles avec de jeunes enfants.",
        "more": ["Les trains de nuit sont de retour, et ils sont complets", "Une révolution discrète à la cantine", "Cinq balades pour le premier week-end frais"],
        "sponsored": "Contenus sponsorisés qui pourraient vous plaire",
        "tiles": [("Les médecins n’expliquent pas cette astuce toute simple", "Sponsorisé · SantéDuJour"),
                  ("Ce nouveau gadget est en rupture partout", "Sponsorisé · BonsPlansGadgets"),
                  ("Vous ne croirez pas à quoi ressemblent ces stars aujourd’hui", "Sponsorisé · Buzzly")],
        "most_read": "Les plus lus",
        "read": ["Pompes à chaleur&nbsp;: qui a droit aux aides", "La boulangerie qui ne ferme jamais", "Le retour de la pluie vendredi", "Une bibliothèque ouvre dans l’ancienne gare"],
    },
}

PAGE = '''<!doctype html>
<html lang="%(lang)s"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(title)s</title>
<style>
:root { --ink:#1b1b1f; --muted:#5f6068; --line:#e3e1dc; --paper:#fbfaf7; --red:#c4302b; }
* { box-sizing:border-box; }
body { margin:0; background:var(--paper); color:var(--ink); font:16px/1.55 Georgia, "Times New Roman", serif; }
.bar { font:600 12px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; letter-spacing:.06em; text-transform:uppercase; color:var(--muted); display:flex; justify-content:space-between; padding:10px 28px; border-bottom:1px solid var(--line); }
.mast { text-align:center; padding:18px 16px 10px; }
.mast h1 { margin:0; font:700 44px/1 Georgia, serif; letter-spacing:-.01em; }
.mast p { margin:6px 0 0; color:var(--muted); font-style:italic; }
nav { display:flex; justify-content:center; flex-wrap:wrap; gap:6px 22px; padding:10px 16px; border-block:1px solid var(--ink); font:600 14px -apple-system, "Helvetica Neue", Arial, sans-serif; }
.wrap { max-width:1180px; margin:0 auto; padding:0 28px; }
.grid { display:grid; grid-template-columns: 1fr 300px; gap:34px; padding:24px 0; }
.kicker { color:var(--red); font:700 12px -apple-system, "Helvetica Neue", Arial, sans-serif; letter-spacing:.08em; text-transform:uppercase; }
h2.lead { font:700 38px/1.1 Georgia, serif; margin:8px 0 12px; }
.standfirst { font-size:19px; color:#3a3b41; margin:0 0 16px; }
.photo { width:100%; aspect-ratio: 16/9; border-radius:4px; background:
  radial-gradient(circle at 78% 30%, #ffe7a8 0 7%, transparent 7.5%),
  linear-gradient(180deg, #8ec5e8 0 55%, #5f9b6b 55% 70%, #3f6f4c 70%); }
.photo.b { background: linear-gradient(135deg, #2e4a7d, #7d9bd1 60%, #e8d9b0); }
.photo.c { background: linear-gradient(160deg, #c96b4b, #eab676 55%, #f4e3c3); }
.credit { font:12px -apple-system, Arial, sans-serif; color:var(--muted); margin:6px 0 18px; }
article p { margin:0 0 14px; }
.more { display:grid; grid-template-columns:repeat(3, 1fr); gap:20px; padding:20px 0 30px; border-top:1px solid var(--line); }
.more h3 { font:700 18px/1.25 Georgia, serif; margin:10px 0 0; }
aside h4 { font:700 13px -apple-system, Arial, sans-serif; letter-spacing:.08em; text-transform:uppercase; margin:0 0 10px; }
aside ol { margin:0 0 24px; padding-left:20px; }
aside li { margin-bottom:10px; font-weight:700; }
.briefing { display:flex; align-items:center; flex-wrap:wrap; gap:6px 16px; margin:16px 0 0; padding:14px 18px; background:#fff4cf; border:1px solid #f0d98a; border-radius:6px; font:15px/1.35 -apple-system, "Helvetica Neue", Arial, sans-serif; }
.briefing b { font-size:17px; } .briefing i { font-style:normal; font-weight:700; margin-left:auto; background:#1b1b1f; color:#fff; padding:7px 14px; border-radius:999px; }
/* ad spaces, with the markup real ad networks use */
#ad-container-leaderboard { max-width:970px; margin:14px auto; }
#ad-sidebar { margin-bottom:24px; }
.ad-slot--inline { margin:18px 0; }
.creative { display:flex; align-items:center; gap:14px; padding:16px 20px; border-radius:6px; font-family:-apple-system, "Helvetica Neue", Arial, sans-serif; color:#fff; position:relative; }
.creative i { font-style:normal; font-weight:800; background:#fff; color:#111; padding:8px 14px; border-radius:999px; margin-left:auto; white-space:nowrap; }
.creative b { font-size:24px; }
.flights { background:linear-gradient(90deg,#ff5a1f,#ffb000); height:90px; }
.flights em { font-style:normal; font-weight:900; font-size:30px; }
.watch { flex-direction:column; align-items:flex-start; background:linear-gradient(160deg,#6d28d9,#db2777); height:250px; gap:6px; }
.watch em { font-style:normal; font-weight:900; font-size:56px; line-height:1; }
.watch i { margin:auto 0 0; }
.watch small, .loan small { font-size:10px; letter-spacing:.1em; opacity:.8; }
.loan { background:#0f9d58; flex-wrap:wrap; }
.trc_related_container { border-top:1px solid var(--line); padding:18px 0 30px; }
.trc_related_container h4 { font:700 13px -apple-system, Arial, sans-serif; letter-spacing:.06em; text-transform:uppercase; margin:0 0 12px; color:var(--muted); }
.trc_related_container .tiles { display:grid; grid-template-columns:repeat(3,1fr); gap:16px; }
.trc_related_container .tile { font:700 15px/1.3 -apple-system, Arial, sans-serif; }
.trc_related_container .tile div { aspect-ratio:4/3; border-radius:4px; margin-bottom:8px; background:linear-gradient(135deg,#f59e0b,#ef4444); }
.trc_related_container .tile:nth-child(2) div { background:linear-gradient(135deg,#10b981,#3b82f6); }
.trc_related_container .tile:nth-child(3) div { background:linear-gradient(135deg,#ec4899,#8b5cf6); }
.trc_related_container .tile span { display:block; font-weight:400; font-size:12px; color:var(--muted); margin-top:4px; }
@media (max-width: 760px) {
  .bar { padding:8px 16px; } .wrap { padding:0 16px; }
  .mast h1 { font-size:32px; } nav { gap:4px 14px; font-size:13px; }
  .grid { grid-template-columns:1fr; gap:0; padding-top:14px; }
  h2.lead { font-size:28px; } .standfirst { font-size:17px; }
  .more, .trc_related_container .tiles { grid-template-columns:1fr 1fr; }
  #ad-container-leaderboard { margin:10px 16px; }
  .flights { height:auto; flex-wrap:wrap; } .flights b { font-size:20px; } .flights em { font-size:24px; }
}
</style>
<script src="http://securepubads.g.doubleclick.net/tag/js/gpt.js"></script>
<script src="http://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
<script src="http://www.googletagmanager.com/gtm.js?id=GTM-DEMO01"></script>
<script src="http://static.criteo.net/js/ld/publishertag.js"></script>
<script src="http://c.amazon-adsystem.com/aax2/apstag.js"></script>
<script src="http://www.%(host)s/app.js"></script>
</head><body>
<div class="bar"><span>%(date)s</span><span>%(account)s</span></div>
<div id="ad-container-leaderboard">%(leader)s</div>
<header class="mast"><h1>%(title)s</h1><p>%(motto)s</p></header>
<nav>%(nav)s</nav>
<div class="wrap">
  <div class="briefing"><b>%(brief)s</b><span>%(brief_text)s</span><i>%(brief_button)s</i></div>
  <div class="grid">
    <article>
      <div class="kicker">%(kicker)s</div>
      <h2 class="lead">%(lead)s</h2>
      <p class="standfirst">%(standfirst)s</p>
      <div class="photo"></div>
      <div class="credit">%(credit)s</div>
      <p>%(p1)s</p>
      <div class="ad-slot--inline">%(inline)s</div>
      <p>%(p2)s</p>
      <div class="more">
        <div><div class="photo b"></div><h3>%(more0)s</h3></div>
        <div><div class="photo c"></div><h3>%(more1)s</h3></div>
        <div><div class="photo"></div><h3>%(more2)s</h3></div>
      </div>
      <div class="trc_related_container">
        <h4>%(sponsored)s</h4>
        <div class="tiles">
%(tiles)s
        </div>
      </div>
    </article>
    <aside>
      <div id="ad-sidebar">%(side)s</div>
      <h4>%(most_read)s</h4>
      <ol>%(read)s</ol>
    </aside>
  </div>
</div>
<script src="http://cdn.taboola.com/libtrc/%(host)s/loader.js"></script>
<script src="http://sb.scorecardresearch.com/beacon.js"></script>
<script src="http://connect.facebook.net/en_US/fbevents.js"></script>
<script src="http://www.google-analytics.com/analytics.js"></script>
<script src="http://static.hotjar.com/c/hotjar-3412.js?sv=6"></script>
<img src="http://ib.adnxs.com/pixel?id=1" width="1" height="1" alt="">
<img src="http://bat.bing.com/action/0?ti=1&Ver=2" width="1" height="1" alt="">
<img src="http://pixel.quantserve.com/pixel/p-demo.gif" width="1" height="1" alt="">
<img src="http://www.facebook.com/tr?id=1&ev=PageView" width="1" height="1" alt="">
<img src="http://pagead2.googlesyndication.com/pagead/show_ads.gif" width="1" height="1" alt="">
</body></html>'''


def render(lang):
    """The demo page in English or French (same markup, same ad spaces)."""
    w = WORDS[lang]
    fill = {k: v for k, v in w.items() if isinstance(v, str)}
    fill.update(ADS[lang])
    fill["nav"] = "".join(f"<span>{x}</span>" for x in w["nav"])
    fill.update({f"more{i}": x for i, x in enumerate(w["more"])})
    fill["tiles"] = "\n".join(f'          <div class="tile"><div></div>{t}<span>{by}</span></div>' for t, by in w["tiles"])
    fill["read"] = "".join(f"<li>{x}</li>" for x in w["read"])
    html = PAGE
    for key, value in fill.items():
        html = html.replace("%(" + key + ")s", value)
    assert "%(" not in html, "a placeholder was left"
    return html


PAGES = {WORDS[lang]["host"]: render(lang) for lang in WORDS}

GIF = bytes.fromhex("47494638396101000100800000000000ffffff21f90401000000002c00000000010001000002024401003b")


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        path = self.path.split("?")[0]
        site = host[4:] if host.startswith("www.") else host
        if site in PAGES and path in ("/", "/index.html"):
            body, kind = PAGES[site].encode(), "text/html; charset=utf-8"
        elif path.endswith(".js"):
            body, kind = b"/* demo */", "application/javascript"
        elif path.endswith((".gif", ".png")) or "pixel" in path or "/tr" in path or "action" in path:
            body, kind = GIF, "image/gif"
        else:
            body, kind = b"", "text/plain"
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 80
    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
