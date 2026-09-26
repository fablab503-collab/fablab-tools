#!/usr/bin/env python3
"""Composes Bouclier's App Store screenshots from the real captures in raw/ (capture.mjs):
brand-blue background, a big caption, a device frame drawn in CSS with the capture inside.

Output: out/<lang>/<device>/<n>-<slide>.png at Apple's sizes, RGB without alpha (App Store
Connect refuses transparency): Mac 2880x1800, iPhone 6.9" 1320x2868, iPad 13" 2064x2752.

usage: compose.py [--only iphone|ipad|mac] [--lang en|fr]
Needs Playwright for Python (pip install playwright) and Pillow.
"""
import argparse
import json
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
OUT = HERE / "out"

# final pixels = CSS size x 2
CANVAS = {"iphone": (660, 1434), "ipad": (1032, 1376), "mac": (1440, 900)}

TEXT = {
    "en": {
        1: ("Ads and trackers, blocked", "Pages load faster and stop following you."),
        2: ("See what each page tried to load", "Every blocked request, counted on the spot."),
        3: ("Pause a site in one {tap}", "Or pause Bouclier for an hour."),
        4: ("Hide anything on a page", "{Tapit}, then Hide. It stays gone."),
        5: ("Your statistics, on your device", "Nothing is collected. Nothing leaves your device."),
        "with_ads": "With ads", "with_bouclier": "With Bouclier",
        "ring_site": "Switch off for this site", "ring_pause": "Or just for an hour",
        "settings": "Bouclier Settings",
    },
    "fr": {
        1: ("Pubs et traqueurs bloqués", "Des pages plus rapides, qui ne vous suivent plus."),
        2: ("Voyez ce que chaque page voulait charger", "Chaque requête bloquée, comptée en direct."),
        3: ("Un site en pause {tap}", "Ou tout Bouclier en pause pendant une heure."),
        4: ("Masquez n'importe quel élément", "{Tapit}, puis Hide : il ne revient plus."),
        5: ("Vos statistiques, sur votre appareil", "Rien n'est collecté, rien ne quitte l'appareil."),
        "with_ads": "Avec pubs", "with_bouclier": "Avec Bouclier",
        "ring_site": "Désactiver sur ce site", "ring_pause": "Ou juste une heure",
        "settings": "Réglages de Bouclier",
    },
}
VERBS = {  # ({tap}, {Tapit}) by language and device
    "en": {"mac": ("click", "Click it"), "ipad": ("tap", "Tap it"), "iphone": ("tap", "Tap it")},
    "fr": {"mac": ("en un clic", "Cliquez dessus"), "ipad": ("d'un geste", "Touchez-le"), "iphone": ("d'un geste", "Touchez-le")},
}
SLUG = {1: "blocked", 2: "report", 3: "pause", 4: "hide", 5: "stats"}
BACKGROUND = {
    1: "linear-gradient(160deg, #081537 0%, #163fae 58%, #3b82f6 100%)",
    2: "linear-gradient(160deg, #0a1b4d 0%, #1d4ed8 70%, #4f8ff7 100%)",
    3: "linear-gradient(160deg, #0b1535 0%, #1e3a8a 60%, #2f6fea 100%)",
    4: "linear-gradient(160deg, #081537 0%, #1a47c2 62%, #5b95f8 100%)",
    5: "linear-gradient(160deg, #060f2a 0%, #16338f 65%, #2563eb 100%)",
}

SHIELD = '<svg viewBox="0 0 100 100" aria-hidden="true"><path d="M50 4 L90 18 V46 C90 72 73 88 50 97 C27 88 10 72 10 46 V18 Z" fill="#2f6fea"/><rect x="28" y="42" width="44" height="14" rx="7" fill="#fff"/></svg>'
SHIELD_TB = SHIELD.replace('<svg', '<svg class="shield"')  # the toolbar button
STATUS_ICONS = ('<svg width="54" height="12" viewBox="0 0 54 12"><g fill="#111">'
                '<rect x="0" y="8" width="3" height="4" rx="1"/><rect x="5" y="6" width="3" height="6" rx="1"/>'
                '<rect x="10" y="3" width="3" height="9" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1"/>'
                '<path d="M27 11.5 l-4-4 a6 6 0 0 1 8 0z"/><path d="M21.5 6 a8.5 8.5 0 0 1 11 0 l-1.4 1.4 a6.5 6.5 0 0 0-8.2 0z"/>'
                '<rect x="37" y="1" width="15" height="10" rx="2.5" fill="none" stroke="#111" stroke-width="1.2"/>'
                '<rect x="39" y="3" width="10" height="6" rx="1.2"/><rect x="52.6" y="4" width="1.4" height="4" rx=".7"/></g></svg>')

CSS = """
@font-face { font-family: Display; font-weight: 700; src: url(fonts/familjen-grotesk-latin-700-normal.woff2); }
@font-face { font-family: Display; font-weight: 600; src: url(fonts/familjen-grotesk-latin-600-normal.woff2); }
@font-face { font-family: Body; font-weight: 400; src: url(fonts/source-sans-3-latin-400-normal.woff2); }
@font-face { font-family: Body; font-weight: 600; src: url(fonts/source-sans-3-latin-600-normal.woff2); }
@font-face { font-family: Body; font-weight: 700; src: url(fonts/source-sans-3-latin-700-normal.woff2); }
* { box-sizing: border-box; margin: 0; }
html, body { width: var(--w); height: var(--h); overflow: hidden; }
body { position: relative; background: var(--bg); color: #fff; font-family: Body, sans-serif; -webkit-font-smoothing: antialiased; }
.glow { position: absolute; inset: 0; background: radial-gradient(60% 45% at 18% 8%, rgba(255,255,255,.16), transparent 70%); }
.mark { position: absolute; width: var(--mark); right: calc(var(--mark) * -0.22); bottom: calc(var(--mark) * -0.12); opacity: .07; transform: rotate(-10deg); }
.mark path { fill: none; stroke: #fff; stroke-width: 3; } .mark rect { fill: #fff; }
.text { position: absolute; }
.brand { display: flex; align-items: center; gap: 12px; font: 600 var(--brand)/1 Display, sans-serif; letter-spacing: -.01em; opacity: .95; }
.brand img { width: calc(var(--brand) * 1.7); height: calc(var(--brand) * 1.7); border-radius: 24%; box-shadow: 0 4px 14px rgba(0,0,0,.25); }
h1 { font: 700 var(--h1)/1.02 Display, sans-serif; letter-spacing: -.025em; margin-top: calc(var(--h1) * .45); text-wrap: balance; }
.sub { font: 600 var(--sub)/1.3 Body, sans-serif; color: rgba(255,255,255,.82); margin-top: calc(var(--sub) * .6); text-wrap: balance; }

/* devices */
.device { position: absolute; background: #0d0e12; box-shadow: 0 0 0 2px rgba(255,255,255,.14), 0 40px 90px rgba(3,10,35,.55); }
.screen { position: absolute; overflow: hidden; background: #fbfaf7; display: flex; flex-direction: column; }
.web { position: relative; overflow: hidden; flex: none; }
.web > img { position: absolute; left: 0; top: 0; width: 100%; }
.statusbar { flex: none; display: flex; align-items: center; justify-content: space-between; font: 700 15px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #111; background: #fbfaf7; }
.island { position: absolute; left: 50%; transform: translateX(-50%); background: #000; border-radius: 99px; z-index: 5; }
.safari-bottom { flex: none; display: flex; align-items: center; justify-content: center; background: #f4f3f0; border-top: 1px solid #e2e0da; }
.pill { display: flex; align-items: center; justify-content: center; gap: 8px; background: #fff; border-radius: 99px; box-shadow: 0 1px 4px rgba(0,0,0,.12); font: 600 15px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #222; }
.pill svg, .tb svg.shield { width: 18px; height: 18px; }
.toolbar { flex: none; position: relative; display: flex; align-items: center; gap: 14px; padding: 0 16px; background: #f4f3f0; border-bottom: 1px solid #e2e0da; }
.lights { display: flex; gap: 8px; } .lights i { width: 12px; height: 12px; border-radius: 50%; background: #ff5f57; } .lights i:nth-child(2) { background: #febc2e; } .lights i:nth-child(3) { background: #28c840; }
.glyph { width: 18px; height: 14px; border: 2px solid #8a8a8e; border-radius: 4px; opacity: .8; }
.addr { flex: 1; display: flex; justify-content: center; }
.addr span { display: inline-flex; align-items: center; gap: 8px; min-width: 46%; justify-content: center; padding: 7px 14px; border-radius: 9px; background: rgba(0,0,0,.055); font: 500 14px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #333; }
.tb { position: relative; display: flex; align-items: center; }
.badge { position: absolute; right: -9px; top: -8px; min-width: 18px; padding: 2px 5px; border-radius: 9px; background: #e5484d; color: #fff; font: 700 10.5px/14px -apple-system, Arial, sans-serif; text-align: center; }

/* before / after */
.split .off { clip-path: inset(0 50% 0 0); }
.divider { position: absolute; top: 0; bottom: 0; left: 50%; width: 3px; margin-left: -1.5px; background: #fff; box-shadow: 0 0 0 1px rgba(0,0,0,.12); z-index: 3; }
.handle { position: absolute; left: 50%; top: 50%; width: 46px; height: 46px; margin: -23px 0 0 -23px; border-radius: 50%; background: #fff; box-shadow: 0 6px 18px rgba(0,0,0,.3); z-index: 4; display: grid; place-items: center; }
.handle svg { width: 26px; height: 26px; }
.tag { position: absolute; top: 14px; z-index: 4; padding: 7px 12px; border-radius: 99px; font: 700 14px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; color: #fff; box-shadow: 0 4px 12px rgba(0,0,0,.2); }
.tag.l { left: 12px; background: #e5484d; } .tag.r { right: 12px; background: #2f6fea; }

/* menu overlays */
.dim { position: absolute; inset: 0; background: rgba(10,14,30,.28); z-index: 2; }
.popover { position: absolute; z-index: 6; border-radius: 14px; overflow: hidden; background: #f2f2f7; box-shadow: 0 0 0 1px rgba(0,0,0,.08), 0 24px 60px rgba(0,0,0,.35); }
.popover img, .sheet img, .card img { display: block; width: 100%; }
.sheet { position: absolute; left: 0; right: 0; bottom: 0; z-index: 6; background: #f2f2f7; border-radius: 26px 26px 0 0; overflow: hidden; box-shadow: 0 -10px 40px rgba(0,0,0,.25); padding-top: 18px; }
.sheet::before { content: ""; position: absolute; top: 7px; left: 50%; width: 40px; height: 5px; margin-left: -20px; border-radius: 3px; background: #c7c7cc; }
.clip { overflow: hidden; }

/* big card with rings */
.card { position: absolute; border-radius: 28px; background: #f2f2f7; box-shadow: 0 0 0 2px rgba(255,255,255,.18), 0 40px 90px rgba(3,10,35,.55); }
.card .clip { border-radius: 28px; }
.ring { position: absolute; border: 4px solid #ffd33d; border-radius: 999px; box-shadow: 0 0 0 8px rgba(255,211,61,.28); z-index: 3; }
.ring.soft { border-color: #fff; box-shadow: 0 0 0 8px rgba(255,255,255,.22); border-radius: 18px; }
.note { position: absolute; z-index: 4; padding: 10px 16px; border-radius: 14px; font: 700 var(--note)/1.15 Body, sans-serif; color: #0b1535; background: #ffd33d; box-shadow: 0 10px 24px rgba(0,0,0,.25); white-space: nowrap; }
.note.soft { background: #fff; }
"""


def img(name):
    return f"raw/{name}.png"


def menu_crop(dev):
    spots = json.loads((RAW / f"{dev}-menu.json").read_text())
    return spots


def text_block(t, n, device, x, y, width):
    head, sub = t[n]
    tap, tapit = VERBS[t["lang"]][device]
    head, sub = head.format(tap=tap, Tapit=tapit), sub.format(tap=tap, Tapit=tapit)
    where = "top:50%;transform:translateY(-50%)" if y is None else f"top:{y}px"  # None: centred (Mac)
    return (f'<div class="text" style="left:{x}px;{where};width:{width}px">'
            f'<div class="brand"><img src="icon.png" alt="">Bouclier</div>'
            f'<h1>{head}</h1><div class="sub">{sub}</div></div>')


def top_blank(name, scale):
    """CSS pixels of empty page above the first card in a settings capture."""
    im = Image.open(RAW / f"{name}.png").convert("RGB")
    bg = im.getpixel((im.width // 2, 2))
    for y in range(0, im.height, 2):
        if im.getpixel((im.width // 2, y)) != bg:
            return y / scale
    return 0


def web_content(dev, n, t, web_w, web_h, variant=None):
    """What the web area of the device shows for slide n."""
    if variant:
        return f'<div class="web" style="width:{web_w}px;height:{web_h}px"><img src="{img(dev + "-page-" + variant)}"></div>'
    if n == 1:
        return (f'<div class="web split" style="width:{web_w}px;height:{web_h}px">'
                f'<img src="{img(dev + "-page-on")}"><img class="off" src="{img(dev + "-page-off")}">'
                f'<div class="divider"></div><div class="handle">{SHIELD}</div>'
                f'<div class="tag l">{t["with_ads"]}</div><div class="tag r">{t["with_bouclier"]}</div></div>')
    if n == 4:
        return f'<div class="web" style="width:{web_w}px;height:{web_h}px"><img src="{img(dev + "-picker")}"></div>'
    if n == 5:
        name = "stats" if dev == "mac" else f"{dev}-stats"
        if dev == "mac":  # the settings page is wider than the window: show the statistics card, centred
            return (f'<div class="web" style="width:{web_w}px;height:{web_h}px;background:#f2f2f7">'
                    f'<img src="{img(name)}" style="width:{web_w * 0.94:.0f}px;left:{web_w * 0.03:.0f}px;top:14px"></div>')
        scale = 3 if dev == "iphone" else 2
        css_w = 440 if dev == "iphone" else 1032
        shift = max(0, top_blank(name, scale) - 14) * web_w / css_w
        cover = ""
        if dev == "ipad":
            r = json.loads((RAW / f"{name}.json").read_text())
            bottom = (r["y"] + r["h"] + 12) * web_w / css_w - shift
            cover = f'<div style="position:absolute;left:0;right:0;top:{bottom:.0f}px;bottom:0;background:#f2f2f7"></div>'
        return (f'<div class="web" style="width:{web_w}px;height:{web_h}px;background:#f2f2f7">'
                f'<img src="{img(name)}" style="top:{-shift:.0f}px">{cover}</div>')
    return (f'<div class="web" style="width:{web_w}px;height:{web_h}px"><img src="{img(dev + "-page-on")}">'
            + ('<div class="dim"></div>' if n == 2 else '') + '</div>')


def phone(n, t, x, y, variant=None, extra=''):
    sw, sh, bez = 420, 912, 12
    web_h = 808
    address = t["settings"] if n == 5 else "daily.example"
    sheet = ""
    if n == 2:
        s = menu_crop("iphone")
        h = s["bottom"] * sw / s["width"]
        sheet = f'<div class="sheet"><div class="clip" style="height:{h:.0f}px"><img src="{img("iphone-menu")}"></div></div>'
    return (f'<div class="device" style="left:{x}px;top:{y}px;width:{sw + 2 * bez}px;height:{sh + 2 * bez}px;border-radius:64px">'
            f'<div class="screen" style="left:{bez}px;top:{bez}px;width:{sw}px;height:{sh}px;border-radius:52px">'
            f'<div class="island" style="top:11px;width:118px;height:34px"></div>'
            f'<div class="statusbar" style="height:44px;padding:6px 30px 0 44px"><span>9:41</span>{STATUS_ICONS}</div>'
            + web_content("iphone", n, t, sw, web_h, variant) +
            f'<div class="safari-bottom" style="height:60px"><div class="pill" style="width:78%;height:40px">{SHIELD}{address}</div></div>'
            + sheet + '</div>' + extra + '</div>')


def tablet(n, t, x, y, variant=None, extra=''):
    sw, bez = 720, 18
    web_h = round(1276 * sw / 1032)
    sh = 30 + 54 + web_h
    address = t["settings"] if n == 5 else "daily.example"
    pop = ""
    if n == 2:
        s = menu_crop("mac")
        w = 330
        h = s["bottom"] * w / s["width"]
        pop = f'<div class="popover" style="right:14px;top:{30 + 54 + 6}px;width:{w}px"><div class="clip" style="height:{h:.0f}px"><img src="{img("mac-menu")}"></div></div>'
    return (f'<div class="device" style="left:{x}px;top:{y}px;width:{sw + 2 * bez}px;height:{sh + 2 * bez}px;border-radius:44px">'
            f'<div class="screen" style="left:{bez}px;top:{bez}px;width:{sw}px;height:{sh}px;border-radius:28px">'
            f'<div class="statusbar" style="height:30px;padding:2px 26px 0;font-size:13px"><span>9:41&nbsp; Wed 23 Sep</span>{STATUS_ICONS}</div>'
            f'<div class="toolbar" style="height:54px"><div class="glyph"></div><div class="addr"><span>{address}</span></div>'
            f'<div class="tb">{SHIELD_TB}<span class="badge">14</span></div></div>'
            + web_content("ipad", n, t, sw, web_h, variant) + pop + '</div>' + extra + '</div>')


def window(n, t, x, y, variant=None, extra=''):
    ww = 860
    web_h = round(1000 * ww / 1440)
    address = t["settings"] if n == 5 else "daily.example"
    pop = ""
    if n == 2:
        s = menu_crop("mac")
        w = 330
        h = s["bottom"] * w / s["width"]
        pop = f'<div class="popover" style="right:16px;top:58px;width:{w}px"><div class="clip" style="height:{h:.0f}px"><img src="{img("mac-menu")}"></div></div>'
    return (f'<div class="device" style="left:{x}px;top:{y}px;width:{ww}px;height:{52 + web_h}px;border-radius:14px;background:#f4f3f0">'
            f'<div class="screen" style="left:0;top:0;width:{ww}px;height:{52 + web_h}px;border-radius:14px">'
            f'<div class="toolbar" style="height:52px"><div class="lights"><i></i><i></i><i></i></div><div class="glyph"></div>'
            f'<div class="addr"><span>{address}</span></div>'
            f'<div class="tb">{SHIELD_TB}<span class="badge">14</span></div></div>'
            + web_content("mac", n, t, ww, web_h, variant) + pop + '</div>' + extra + '</div>')


def pair(device, t, front, back):
    """Slide 1: two devices side by side, the page with ads (left, tilted) and with Bouclier (right)."""
    make = {"iphone": phone, "ipad": tablet, "mac": window}[device]
    (fx, fy, fscale), (bx, by, bscale, brot) = front, back
    tag = 'top:-50px;left:50%;right:auto;transform:translateX(-50%) scale({inv:.3f});transform-origin:50% 100%;font-size:19px;padding:10px 18px'
    tag_l = f'<div class="tag l" style="{tag.format(inv=1 / bscale)}">{t["with_ads"]}</div>'
    tag_r = f'<div class="tag r" style="{tag.format(inv=1 / fscale)}">{t["with_bouclier"]}</div>'
    back_html = make(1, t, bx, by, "off", tag_l).replace(
        'class="device" style="', f'class="device" style="transform:rotate({brot}deg) scale({bscale});transform-origin:0% 100%;', 1)
    front_html = make(1, t, fx, fy, "on", tag_r).replace(
        'class="device" style="', f'class="device" style="transform:scale({fscale});transform-origin:100% 100%;z-index:2;', 1)
    return back_html + front_html


def pause_card(dev, t, x, y, width, note_px):
    """Slide 3: Bouclier's menu, large, with rings on the site switch and the pause button."""
    src = "iphone" if dev == "iphone" else "mac"
    s = menu_crop(src)
    k = width / s["width"]
    h = s["bottom"] * k
    tg, pz = s["toggle"], s["pause"]
    pad = 10
    ring1 = (f'<div class="ring" style="left:{tg["x"] * k - pad:.0f}px;top:{tg["y"] * k - pad:.0f}px;'
             f'width:{tg["w"] * k + 2 * pad:.0f}px;height:{tg["h"] * k + 2 * pad:.0f}px"></div>')
    ring2 = (f'<div class="ring soft" style="left:{pz["x"] * k - pad:.0f}px;top:{pz["y"] * k - pad:.0f}px;'
             f'width:{pz["w"] * k + 2 * pad:.0f}px;height:{pz["h"] * k + 2 * pad:.0f}px"></div>')
    note1 = (f'<div class="note" style="right:{-8:.0f}px;top:{tg["y"] * k - note_px * 3.1:.0f}px">{t["ring_site"]}</div>')
    note2 = (f'<div class="note soft" style="right:{-8:.0f}px;top:{(pz["y"] + pz["h"]) * k + pad + 18:.0f}px">{t["ring_pause"]}</div>')
    note2 = (f'<div class="note soft" style="left:{pz["x"] * k:.0f}px;top:{(pz["y"] + pz["h"]) * k + pad + 14:.0f}px">{t["ring_pause"]}</div>')
    pc = json.loads((RAW / f"{src}-pausecard.json").read_text())
    ph = (pc["h"] + 24) * k   # the card with the menu's own margins, like the card above
    gap = 92
    card2 = (f'<div class="card" style="left:{x}px;top:{y + h + gap:.0f}px;width:{width:.0f}px;height:{ph:.0f}px">'
             f'<div class="clip" style="height:{ph:.0f}px;border-radius:28px"><img src="{img(src + "-pausecard")}" '
             f'style="width:{width:.0f}px;margin-top:{-(pc["y"] - 12) * k:.0f}px"></div></div>')
    return (f'<div class="card" style="left:{x}px;top:{y}px;width:{width:.0f}px;height:{h:.0f}px">'
            f'<div class="clip" style="height:{h:.0f}px"><img src="{img(src + "-menu")}" style="width:{width:.0f}px"></div>'
            + ring1 + ring2 + note1 + note2 + '</div>' + card2)


def pause_height(dev, width):
    src = "iphone" if dev == "iphone" else "mac"
    s = menu_crop(src)
    pc = json.loads((RAW / f"{src}-pausecard.json").read_text())
    k = width / s["width"]
    return s["bottom"] * k + 92 + (pc["h"] + 24) * k


def page(device, n, lang):
    t = dict(TEXT[lang], lang=lang)
    w, h = CANVAS[device]
    if device == "iphone":
        vars_ = "--h1:54px;--sub:25px;--brand:22px;--note:22px;--mark:560px"
        text = text_block(t, n, device, 48, 58, 564)
        if n == 1:
            body = pair("iphone", t, (w - 444 - 8, h - 936 - 34, 0.84), (8, h - 936 - 64, 0.8, -4))
        elif n == 3:
            body = pause_card("iphone", t, 50, 430 + (h - 430 - pause_height("iphone", 560)) / 2, 560, 22)
        else:
            body = phone(n, t, (w - 444) // 2, h - 936 - 38)
    elif device == "ipad":
        vars_ = "--h1:58px;--sub:27px;--brand:22px;--note:24px;--mark:760px"
        text = text_block(t, n, device, 64, 54, 900)
        dev_h = 30 + 54 + round(1276 * 720 / 1032) + 36
        if n == 1:
            body = pair("ipad", t, (w - 756 - 20, h - dev_h - 36, 0.7), (20, h - dev_h - 70, 0.68, -3))
        elif n == 3:
            body = pause_card("ipad", t, 196, 330 + (h - 330 - pause_height("ipad", 640)) / 2, 640, 24)
        else:
            body = tablet(n, t, (w - 756) // 2, h - dev_h - 36)
    else:
        vars_ = "--h1:58px;--sub:25px;--brand:22px;--note:22px;--mark:720px"
        text = text_block(t, n, device, 72, None, 390 if n == 1 else 440)
        win_h = 52 + round(1000 * 860 / 1440)
        if n == 1:
            body = pair("mac", t, (1440 - 40 - 860, 900 - win_h - 36, 0.8), (462, 150 - round(win_h * 0.3), 0.7, -2))
        elif n == 3:
            body = pause_card("mac", t, 720, (900 - pause_height("mac", 560)) / 2, 560, 22)
        else:
            body = window(n, t, 1440 - 56 - 860, (900 - win_h) // 2)
    mark = SHIELD.replace('<svg', '<svg class="mark"')
    return (f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><style>{CSS}</style></head>'
            f'<body style="--w:{w}px;--h:{h}px;--bg:{BACKGROUND[n]};{vars_}">'
            f'<div class="glow"></div>{mark}{text}{body}</body></html>')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=list(CANVAS))
    ap.add_argument("--lang", choices=list(TEXT))
    args = ap.parse_args()
    work = HERE / "_slide.html"
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        for lang in [args.lang] if args.lang else TEXT:
            for device, (w, h) in CANVAS.items():
                if args.only and device != args.only:
                    continue
                pg = browser.new_page(viewport={"width": w, "height": h}, device_scale_factor=2)
                for n in range(1, 6):
                    work.write_text(page(device, n, lang))
                    pg.goto(work.as_uri())
                    pg.evaluate("document.fonts.ready")
                    pg.wait_for_timeout(150)
                    out = OUT / lang / device / f"{n}-{SLUG[n]}.png"
                    out.parent.mkdir(parents=True, exist_ok=True)
                    pg.screenshot(path=str(out))
                    Image.open(out).convert("RGB").save(out, optimize=True)  # no alpha channel
                    print(f"  {out.relative_to(HERE)}  {Image.open(out).size}")
                pg.close()
        browser.close()
    work.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
