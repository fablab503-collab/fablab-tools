#!/usr/bin/env python3
"""The screens of the official page's device images: Bouclier as it looks in Safari on a MacBook Pro
14" (full screen, "looks like" 1352 x 878), an iPad Pro 13" and an iPhone 17 Pro, in English and
French, from the real captures in raw/<lang>/site-* (capture.mjs). Each screen is made at the
device's native resolution, to go inside Apple's product bezel (frame.sh does that on the Mac,
with the bezels from Daniel's Design Assets folder; Apple's rules: the app as it appears when
running, full Wi-Fi and battery icons, nothing drawn outside the screen).

Output: out/site/<lang>/<device>.png  (mac 3024x1964, ipad 2064x2752, iphone 1206x2622)
usage: site_screens.py [--lang en|fr]   Needs Playwright for Python and Pillow.
"""
import argparse
import json
from pathlib import Path

from PIL import Image
from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
RAW = HERE / "raw"
OUT = HERE / "out" / "site"

# CSS size, pixel ratio and native pixels of each screen
SCREENS = {
    "mac": {"css": (1352, 878), "scale": 3024 / 1352, "px": (3024, 1964)},
    "ipad": {"css": (1032, 1376), "scale": 2, "px": (2064, 2752)},
    "iphone": {"css": (402, 874), "scale": 3, "px": (1206, 2622)},
}
WORDS = {
    "en": {"host": "daily.example", "date": "Wed 23 Sep"},
    "fr": {"host": "quotidien.example", "date": "mer. 23 sept."},
}

# Safari's toolbar glyphs, drawn simply (grey strokes)
G = 'fill="none" stroke="#6b6b70" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"'
SIDEBAR = f'<svg width="22" height="18" viewBox="0 0 22 18"><rect x="1" y="1.5" width="20" height="15" rx="3.5" {G}/><path d="M8 2v14" {G}/></svg>'
BACK = f'<svg width="12" height="18" viewBox="0 0 12 18"><path d="M9 2 2 9l7 7" {G} stroke-width="2"/></svg>'
FORWARD = f'<svg width="12" height="18" viewBox="0 0 12 18" style="opacity:.35"><path d="M3 2l7 7-7 7" {G} stroke-width="2"/></svg>'
SHARE = f'<svg width="18" height="22" viewBox="0 0 18 22"><path d="M6 8H3.5A1.5 1.5 0 0 0 2 9.5v9A1.5 1.5 0 0 0 3.5 20h11a1.5 1.5 0 0 0 1.5-1.5v-9A1.5 1.5 0 0 0 14.5 8H12M9 13V1.5M5.5 5 9 1.5 12.5 5" {G}/></svg>'
PLUS = f'<svg width="18" height="18" viewBox="0 0 18 18"><path d="M9 2v14M2 9h14" {G} stroke-width="1.9"/></svg>'
TABS = f'<svg width="20" height="20" viewBox="0 0 20 20"><rect x="1.5" y="5.5" width="13" height="13" rx="3" {G}/><path d="M5.5 3.2A2.5 2.5 0 0 1 7.5 2h8A2.5 2.5 0 0 1 18 4.5v8a2.5 2.5 0 0 1-1.2 2.1" {G}/></svg>'
RELOAD = f'<svg width="14" height="14" viewBox="0 0 14 14"><path d="M12 7a5 5 0 1 1-1.6-3.7M12 1.5v3.2H8.8" {G} stroke-width="1.6"/></svg>'
PAGEMENU = f'<svg width="18" height="14" viewBox="0 0 18 14"><rect x="1" y="1" width="16" height="12" rx="3" {G} stroke-width="1.5"/><path d="M5 5h8M5 9h5" {G} stroke-width="1.5"/></svg>'
ICON = HERE.parent.parent / "extension" / "icons" / "toolbar-48.png"


def status_icons(signal=True, color="#111"):
    """Full signal (iPhone), full Wi-Fi and a full battery, as Apple's rules ask."""
    bars = (f'<g fill="{color}"><rect x="0" y="8" width="3.2" height="4" rx="1"/><rect x="5" y="5.5" width="3.2" height="6.5" rx="1"/>'
            f'<rect x="10" y="3" width="3.2" height="9" rx="1"/><rect x="15" y="0" width="3.2" height="12" rx="1"/></g>') if signal else ""
    x = 24 if signal else 0
    wifi = (f'<g transform="translate({x} 0)" fill="{color}"><path d="M8 12.2 5.2 9.3a4 4 0 0 1 5.6 0z"/>'
            f'<path d="M2.6 6.8a7.7 7.7 0 0 1 10.8 0l-1.5 1.5a5.6 5.6 0 0 0-7.8 0z"/>'
            f'<path d="M0 4.2a11.4 11.4 0 0 1 16 0l-1.5 1.5a9.3 9.3 0 0 0-13 0z"/></g>')
    bx = x + 23
    battery = (f'<g transform="translate({bx} 0)"><rect x=".6" y=".6" width="23.8" height="11.3" rx="3.4" fill="none" stroke="{color}" stroke-opacity=".4" stroke-width="1.1"/>'
               f'<rect x="2.3" y="2.3" width="20.4" height="7.9" rx="2" fill="{color}"/><path d="M26 4.2v4.1a2.2 2.2 0 0 0 0-4.1z" fill="{color}" fill-opacity=".45"/></g>')
    w = bx + 27
    return f'<svg width="{w}" height="13" viewBox="0 0 {w} 13">{bars}{wifi}{battery}</svg>'


CSS = """
* { box-sizing: border-box; margin: 0; }
html, body { width: var(--w); height: var(--h); overflow: hidden; }
body { position: relative; background: #fbfaf7; font-family: -apple-system, "Helvetica Neue", Helvetica, Arial, sans-serif; -webkit-font-smoothing: antialiased; }
.abs { position: absolute; }
.web { position: absolute; left: 0; right: 0; overflow: hidden; }
.web img { display: block; width: 100%; }
.toolbar { position: absolute; left: 0; right: 0; display: flex; align-items: center; gap: 18px; padding: 0 18px;
  background: #f4f4f5; border-bottom: 1px solid #dcdcdf; }
.toolbar .sp { flex: 1; }
.addr { display: flex; align-items: center; gap: 10px; height: 34px; padding: 0 12px; border-radius: 10px; background: #e7e7ea;
  font-size: 14px; font-weight: 500; color: #1d1d1f; }
.addr .host { flex: 1; text-align: center; }
.ext { position: relative; width: 26px; height: 26px; display: grid; place-items: center; border-radius: 7px; background: rgba(0,0,0,.07); }
.ext img { width: 19px; height: 19px; }
.badge { position: absolute; right: -8px; bottom: -5px; min-width: 17px; padding: 0 4px; border-radius: 6px; background: #6e6e73; color: #fff;
  font: 700 10px/14px -apple-system, "Helvetica Neue", Arial, sans-serif; text-align: center; box-shadow: 0 0 0 1.5px #f4f4f5; }
.popover { position: absolute; z-index: 5; border-radius: 12px; background: #f2f2f7; overflow: hidden;
  box-shadow: 0 0 0 .5px rgba(0,0,0,.28), 0 10px 36px rgba(0,0,0,.28); }
.popover img { display: block; }
.arrow { position: absolute; z-index: 6; width: 18px; height: 18px; background: #f2f2f7; transform: rotate(45deg); border-radius: 3px;
  box-shadow: -.5px -.5px 0 0 rgba(0,0,0,.2); }
.statusbar { position: absolute; left: 0; right: 0; top: 0; display: flex; align-items: center; justify-content: space-between; color: #111;
  font: 600 16px/1 -apple-system, "Helvetica Neue", Arial, sans-serif; }
.dim { position: absolute; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,.22); z-index: 3; }
.sheet { position: absolute; left: 0; right: 0; bottom: 0; z-index: 4; background: #f2f2f7; border-radius: 34px 34px 0 0; overflow: hidden; }
.sheet .grab { position: absolute; top: 6px; left: 50%; width: 36px; height: 5px; margin-left: -18px; border-radius: 3px; background: #c4c4c8; z-index: 2; }
.sheet img { display: block; width: 100%; }
.home { position: absolute; left: 50%; bottom: 8px; width: 138px; height: 5px; margin-left: -69px; border-radius: 3px; background: #111; z-index: 6; }
"""


def raw(lang, name):
    return (RAW / lang / f"{name}.png").as_uri()


def spots(lang, name):
    return json.loads((RAW / lang / f"{name}.json").read_text())


def toolbar(lang, top, height, badge="15"):
    host = WORDS[lang]["host"]
    return (f'<div class="toolbar" style="top:{top}px;height:{height}px">{SIDEBAR}{BACK}{FORWARD}'
            f'<div class="sp"></div><div class="ext" id="ext"><img src="{ICON.as_uri()}" alt=""><span class="badge">{badge}</span></div>'
            f'<div class="addr" style="width:{min(620, round(height * 12))}px">{PAGEMENU}<span class="host">{host}</span>{RELOAD}</div>'
            f'<div class="sp"></div>{SHARE}{PLUS}{TABS}</div>')


def popover(lang, device, top, max_h, width):
    """Bouclier's menu under its toolbar button (placed by PLACE once the toolbar is laid out)."""
    s = spots(lang, f"site-{device}-menu")
    # a long menu scrolls: the window ends after "More filters", where the list of filters ends
    h = min(s["height"], max_h, s["more"]["y"] + s["more"]["h"] + 3) if s.get("more") else min(s["height"], max_h)
    return (f'<div class="arrow" id="arrow" style="top:{top - 8}px"></div>'
            f'<div class="popover" id="pop" style="top:{top}px;width:{width}px;height:{h:.0f}px">'
            f'<img src="{raw(lang, f"site-{device}-menu")}" style="width:{width}px"></div>')


PLACE = """<script>
  const b = document.getElementById('ext').getBoundingClientRect(), cx = b.left + b.width / 2;
  document.getElementById('arrow').style.left = (cx - 9) + 'px';
  document.getElementById('pop').style.left = Math.max(10, cx - 46) + 'px';
</script>"""


def mac(lang):
    w, h = SCREENS["mac"]["css"]
    safe, tb = 33, 52                       # the camera housing's strip (full screen), Safari's toolbar
    top = safe + tb
    body = (f'<div class="abs" style="left:0;right:0;top:0;height:{safe}px;background:#000"></div>'
            + toolbar(lang, safe, tb)
            + f'<div class="web" style="top:{top}px;height:{h - top}px"><img src="{raw(lang, "site-mac-page")}"></div>'
            + popover(lang, "mac", top + 9, h - top - 30, 330) + PLACE)
    return w, h, body


def ipad(lang):
    w, h = SCREENS["ipad"]["css"]
    sb, tb = 24, 50
    top = sb + tb
    status = (f'<div class="statusbar" style="height:{sb}px;padding:2px 22px 0;font-size:13px;background:#f4f4f5">'
              f'<span>9:41&nbsp;&nbsp;{WORDS[lang]["date"]}</span>{status_icons(signal=False)}</div>')
    body = (status + toolbar(lang, sb, tb)
            + f'<div class="web" style="top:{top}px;height:{h - top}px"><img src="{raw(lang, "site-ipad-page")}"></div>'
            + popover(lang, "ipad", top + 9, h - top - 300, 360) + PLACE)
    return w, h, body


def iphone(lang):
    w, h = SCREENS["iphone"]["css"]
    sb = 62                                 # below the Dynamic Island
    s = spots(lang, "site-iphone-menu")
    card = s["bottom"] - 2                  # the menu's top and this site's card, then the home indicator
    sheet_h = round(card + 8 + 30)
    status = (f'<div class="statusbar" style="height:54px;padding:6px 34px 0 52px">'
              f'<span style="font-size:17px">9:41</span>{status_icons()}</div>')
    body = (status
            + f'<div class="web" style="top:{sb}px;height:{h - sb}px"><img src="{raw(lang, "site-iphone-page")}"></div>'
            + f'<div class="dim" style="top:{sb}px"></div>'
            + f'<div class="sheet" style="height:{sheet_h}px"><div class="grab"></div>'
            + f'<div style="margin-top:8px;height:{card:.0f}px;overflow:hidden"><img src="{raw(lang, "site-iphone-menu")}"></div>'
            + '</div><div class="home"></div>')
    return w, h, body


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", choices=list(WORDS))
    args = ap.parse_args()
    work = HERE / "_site.html"
    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        for lang in [args.lang] if args.lang else WORDS:
            for device, make in (("mac", mac), ("ipad", ipad), ("iphone", iphone)):
                w, h, body = make(lang)
                spec = SCREENS[device]
                work.write_text(f'<!doctype html><html lang="{lang}"><head><meta charset="utf-8"><style>{CSS}</style></head>'
                                f'<body style="--w:{w}px;--h:{h}px">{body}</body></html>')
                pg = browser.new_page(viewport={"width": w, "height": h}, device_scale_factor=spec["scale"])
                pg.goto(work.as_uri())
                pg.wait_for_timeout(200)
                out = OUT / lang / f"{device}.png"
                out.parent.mkdir(parents=True, exist_ok=True)
                pg.screenshot(path=str(out))
                pg.close()
                im = Image.open(out).convert("RGB")
                if im.size != spec["px"]:           # a fractional pixel ratio can be one pixel off
                    im = im.resize(spec["px"], Image.LANCZOS)
                im.save(out, optimize=True)
                print(f"  {out.relative_to(HERE)}  {im.size}")
        browser.close()
    work.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
