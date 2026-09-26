# Bouclier

A Safari ad blocker for Mac, iPhone and iPad: blocks ads, trackers, malware sites and
pop-ups, hides empty ad spaces, removes YouTube ads, cleans tracking tags from
links, and can be paused per site from the Safari toolbar. Built on 2026-09-23 as
a Safari **web extension** using `declarativeNetRequest`, the same approach as
uBlock Origin Lite for Safari. Version 1.1 (2026-09-23) added the features below;
version 1.2 (2026-09-23) adds iPhone and iPad and prepares the App Store release
(see `appstore/README.md`).

## Features

| Area | What it does |
|---|---|
| Network blocking | EasyList, EasyPrivacy, Liste FR, URLhaus malware list, anti-adblock walls; optional cookie banners, social widgets, annoyances |
| Element hiding | Hides the empty spaces ads leave, generic and per-site |
| YouTube | Removes video ads and ad banners, disarms the anti-adblock penalty; optional "Hide YouTube Shorts" |
| Pop-up ads (1.1) | Closes tabs a page opens to known pop-up/pop-under ad addresses (the lists' `$popup` filters) and shows a notice |
| Clean links (1.1) | Removes utm_, fbclid, gclid and ~40 other tracking tags from page addresses before the page loads |
| Toolbar popup | Per-site switch, requests blocked on the page and **which addresses** (Safari), "Pause 1 hour", hide an element, hidden-element count with "Show them again", filters, pause everywhere for 30 min / 1 h / until tomorrow |
| Fix a broken page (1.1) | Click a blocked address in the popup to allow it on that site only |
| Site controls (1.1) | Per site: block web fonts, block scripts from other sites, hide comments |
| Right-click menu (1.1) | "Hide This Element…" (opens the picker on what you clicked), "Pause/Resume Bouclier on this site" |
| Keyboard shortcuts (1.1) | ⌥⇧B open Bouclier, ⌥⇧P pause/resume the site, ⌥⇧H hide an element, pause everywhere (no default key) |
| Statistics (1.1) | Today / 7 days / all time, 14-day chart with table view, most blocked addresses, sites with most blocking, data-saved estimate; stays on the Mac |
| Setup check (1.1) | Opens on first install and from the popup: website access, active lists, Private Browsing, list age |
| Backup (1.1) | Export / import all settings as a JSON file (validated on import) |
| Settings | Toolbar count on/off, list table with Safari rule budget, paused sites, site controls, hidden elements, own filters, shortcuts |
| Updates | `./build.sh` refreshes lists; App Store users get new lists with each app update; settings warn when lists are older than 45 days |
| iPhone and iPad (1.2) | Menu sized for the iPhone sheet with iOS-size controls, iOS instructions and Settings paths, element picker works with taps |

## Layout

| Path | What it is |
|---|---|
| `extension/` | The Safari web extension (MV3): `manifest.json`, `background.js`, toolbar popup, settings page, content scripts |
| `extension/rules/*.json` | Generated network rules, one ruleset per filter list |
| `extension/cosmetic/*` | Generated element-hiding data (`*.generic.css`, `*.json`) plus `builtin-youtube.json` |
| `extension/filters.json` | Generated catalogue (rule counts, versions, allow-rule ranges) used by the popup and settings |
| `extension/data/*.json` | Generated: `popups.json` (pop-up filters per list), `tracking-params.json` |
| `extension/pages/welcome.*` | Welcome and setup-check page |
| `tools/tracking-params.json` | Tracking tags removed by "Clean links" |
| `tools/auto-update.sh` | `install` / `uninstall` / `run` / `status` for the weekly LaunchAgent (personal builds) |
| `tools/xcode_setup.py` | Turns the converter's Xcode project into the App Store project: app window, icon, Info.plist keys, sandbox check |
| `extension/common/platform.js` | Tells Mac, iPhone and iPad apart for the extension pages |
| `appstore/` | App Store release: `README.md` (runbook), `listing.json` + `check_listing.py`, `support.html` (support + privacy policy), `AppIcon.icon`, `app/` (the app's window) |
| `tools/lists.json` | Which filter lists are used, their URLs, licences and defaults |
| `tools/fetch_lists.py` | Downloads the lists (official host first, GitHub mirror second) |
| `tools/convert.py` | Compiles Adblock Plus syntax into Safari rules and CSS |
| `build.sh` | Download → compile → build the signed Mac app → install in /Applications |
| `design/` | Icon sources (SVG) and rendered app icon sizes |
| `tests/` | Headless-Chromium test suite, 90 checks (`node tests/run-tests.mjs` with `tests/server.py` running as root on port 80) |

## Building

```sh
./build.sh           # everything: fresh lists, rules, Mac app, install
./build.sh --lists   # only refresh the rules in extension/ (no Xcode needed)
./build.sh --app     # rebuild and reinstall the Mac app with the current rules
./build.sh --sim     # build the iPhone/iPad app and run it in the iOS Simulator
./build.sh --release # fresh lists, archive Mac + iOS, upload both to App Store Connect
```

The app builds need Xcode 26+ signed in with the Apple ID of the developer membership
(Xcode › Settings › Accounts). The App Store steps are in `appstore/README.md`.
Without Xcode, load `extension/` with Safari › Settings › Developer › *Add Temporary
Extension…* (Safari removes it again when it quits).

After installing: Safari › Settings › Extensions › turn on **Bouclier** and allow it on
every website (needed for element hiding and YouTube; network blocking works without it).

## How it works

* **Network blocking** – filter lists become `declarativeNetRequest` static rulesets.
  Safari compiles every enabled rule of the extension into one WebKit content rule list
  with a hard cap of 150,000 rules. `convert.py` counts rules exactly the way Safari
  expands them and `background.js` refuses to enable a list that would go over.
* **Element hiding** – generic selectors are registered as user stylesheets
  (`cssOrigin: user`), site-specific selectors are injected on demand by the background.
* **YouTube** – `content/youtube-main.js` runs in the page's world before YouTube's code,
  strips `adPlacements` / `adSlots` / `playerAds` from player data (initial data,
  `JSON.parse`, `fetch`, XHR), removes Shorts ads, disarms the "abnormality detected"
  penalty and skips any ad that still plays. YouTube changes often; this file is what
  needs updating when ads come back.
* **Per-site pause** – a dynamic `allowAllRequests` rule for the site plus
  `excludeMatches` on the content scripts.

## Filter lists and licences

EasyList, EasyPrivacy, EasyList Social/Annoyances and the Adblock Warning Removal List are
dual-licensed GPL-3.0-or-later / CC BY-SA 3.0, and Liste FR is CC BY-SA 3.0: Bouclier uses
them under **CC BY-SA 3.0**, which allows a paid app as long as the authors are credited and
the adapted rules stay under the same licence (https://easylist.to/pages/licence.html, read
2026-09-23). The EasyList Cookie List is CC BY 3.0; the Online Malicious URL Blocklist
(malware-filter, abuse.ch URLhaus data) is CC0. Credits are in the settings page, the app
window and `appstore/support.html`. The YouTube ad removal (`content/youtube-main.js`) is
Bouclier's own code; its approach follows uBlock Origin's public filters, whose code is not
used.

## Safari notes (from WebKit source, 2026-09)

* `getMatchedRules()` in Safari returns `{ request: { url }, timeStamp, tabId }` for each
  blocked load and no rule ids; Chromium returns rule ids and no URL, and limits the call
  to ~20 per 10 minutes. Statistics handle both and use one coalesced call for all tabs.
* A `regexFilter` rule ignores `requestDomains` in Safari's translator, so link-cleaning
  rules never combine the two.
* Link-cleaning redirects cannot loop: each rule only matches when its tag is a real
  query parameter, and matching is case-sensitive like `removeParams`.
