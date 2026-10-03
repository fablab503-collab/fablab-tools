# Bouclier

A Safari ad blocker for Mac, iPhone and iPad: blocks ads, trackers, malware sites and
pop-ups, hides empty ad spaces, removes YouTube ads, cleans tracking tags from
links, and can be paused per site from the Safari toolbar. Built on 2026-09-23 as
a Safari **web extension** using `declarativeNetRequest`, the same approach as
uBlock Origin Lite for Safari. Version 1.1 (2026-09-23) added the features below;
version 1.2 (2026-09-23) adds iPhone and iPad and prepares the App Store release
(see `appstore/README.md`). Version 1.3 (2026-09-28) blocks more, measured: Bouclier's own
additions to the lists (`tools/extra-*.txt`), 15,700 dead domains left out of Safari's rule
budget, and a benchmark in WebKit (`tests/webkit-bench/`, results below).

**Runs on** Safari 16.4 or later: iPhone 8, 8 Plus, X and newer with iOS 16.4+, iPads with
iPadOS 16.4+, and Macs with macOS 12 or later, Apple silicon or Intel (one universal app).
Older iPhones (6s, 7, SE 1st generation) stop at iOS 15, which lacks what Bouclier is built on
(`declarativeNetRequest` requestDomains, `scripting.registerContentScripts`, `storage.session`);
the iPhone 6 and older cannot run Safari extensions at all. The QA pass of 2026-09-28 lowered the minimum from iOS / macOS 26.

## Features

| Area | What it does |
|---|---|
| Network blocking | EasyList, EasyPrivacy, Liste FR, URLhaus malware list, anti-adblock walls; optional cookie banners, social widgets, annoyances |
| Element hiding | Hides the empty spaces ads leave, generic and per-site |
| YouTube | Removes video ads and ad banners, disarms the anti-adblock penalty; optional "Hide YouTube Shorts" |
| Pop-up ads (1.1) | Closes tabs a page opens to known pop-up/pop-under ad addresses (the lists' `$popup` filters) and shows a notice |
| Clean links (1.1) | Removes utm_, fbclid, gclid and ~40 other tracking tags from page addresses before the page loads |
| Toolbar popup | Per-site switch, requests blocked on the page and **which addresses** (Safari), "Pause on this site for 1 hour · 2 hours · Until tomorrow" (1.3; the page reloads by itself once Safari has recompiled its rules), hide an element, hidden-element count with "Show them again", filters, pause everywhere for 30 min / 1 h / until tomorrow |
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
| English and French (1.3) | English by default, French on devices set to French: the extension's pages, menus and notices (`extension/i18n/*.json` → `_locales/`, `tools/i18n.py`), the app window and the App Store texts |
| Test it (1.3) | "Test Bouclier" in the settings and in the app window opens the independent test adblock.turtlecute.org in Safari (linked, not copied: its licence, CC BY-NC-SA 4.0, excludes a paid app); `tests/webkit-bench/efficacy.sh` runs it every week in WebKit |
| Stronger blocking (1.3) | Bouclier's own rules on top of the lists: Google's ad domains in full, ad and tracking servers the lists miss (found by measuring 160 sites), phone-maker telemetry; dead domains left out (134,413 → 118,687 Safari rules with the defaults) |

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
| `tools/extra-ads.txt`, `extra-privacy.txt`, `extra-french.txt` | Bouclier's own additions (1.3), compiled into the Ads, Trackers and French sites rulesets |
| `tools/frame-hosts.txt` | Hosts that served ad frames on the 100 test sites; Safari before 26 blocks their frames too |
| `tools/deadhosts.py`, `tools/dead-hosts.json` | Finds blocked domains that no longer exist (NXDOMAIN from two resolvers); `convert.py` leaves them out, and ignores the list once it is 90 days old |
| `tests/webkit-bench/` | Benchmark in WebKit with the extension loaded (no Safari window needed): what gets through, judged by lists Bouclier does not ship |
| `tests/safari-sites/` | The same in the real Safari with the installed app (`run.sh`, `bench.sh` for the public test pages, `compare.py`) |
| `tools/fetch_lists.py` | Downloads the lists (official host first, GitHub mirror second) |
| `tools/convert.py` | Compiles Adblock Plus syntax into Safari rules and CSS |
| `build.sh` | Download → compile → build the signed Mac app → install in /Applications |
| `design/` | Icon sources (SVG) and rendered app icon sizes |
| `tests/` | Headless-Chromium test suite, 91 checks (`node tests/run-tests.mjs` with `tests/server.py` running as root on port 80) |
| `tests/qa-tests.mjs` | QA regression tests (same server): invalid input, 50-100 simultaneous changes, Safari 16.4 fallbacks, iPhone 8 layout, accessibility |
| `tests/test_xcode_setup.py` | Unit tests for the Xcode project changes (`python3 tests/test_xcode_setup.py`) |
| `tests/test_convert_compat.py` | Unit tests for the rules Safari before 26 gets (`python3 -m unittest tests/test_convert_compat.py`) |
| `tests/iphone-ui/` | Bouclier on simulated iPhones (`run.sh <simulator id>…`): the app window, turning it on in Settings, the independent test page and Bouclier's menu in Safari, with a screenshot of every step |
| `tests/compat/` | Lint for Safari 16.4: every JS API and CSS feature must exist there (`npm --prefix tests/compat install && npm --prefix tests/compat run lint`) |
| `tests/i18n-tests.mjs` | English and French: every text translated, French layout on an iPhone 8, English by default |
| `tests/buttons-tests.mjs` | Every button, switch, link and field of the menu, settings, setup page, picker and app window: named, reachable with the keyboard, 44-point touch targets, inside the screen, and each one clicked (English and French, Mac, iPad, iPhone, 320 points) |
| `tests/test_site.py` | The official page (`site/`): links, images, both languages, French typography, nothing private |
| `tests/webkit-bench/efficacy.sh` | The weekly efficacy check: adblock.turtlecute.org's score in WebKit with the installed app, and below 93 % (or every 4 weeks) a new 100-site scan compared with the last one; `bg.sh` starts it (or any long run) detached |

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
* **Safari before 26 (iOS 16.4–18, macOS 13–15)** – it does not apply `requestDomains`, which
  holds most blocked domains, and a rule that covers frames and other loads costs it two WebKit
  rules. So every list also comes as `rules/<id>_compat.json`: each domain its own `urlFilter`
  rule, block rules without types limited to every type but frames. `background.js` turns these
  on instead when Safari is older than 26. Measured on simulated iPhones (`tests/iphone-ui`,
  3 October 2026): the independent test page went from 16 % to 100 % on iOS 18.5, and is 100 %
  on iOS 17.5. Frames come back for the hosts that served ad frames on the 100 test sites
  (`tools/frame-hosts.txt`, about 100 more rules). Safari 16.4–17 also sends the menu's messages
  without an address, and drops an answer sent later with `sendResponse`: `background.js` answers
  messages that come from no tab (the menu) or from a tab showing one of Bouclier's pages, and on
  Safari 17 and older it answers with the listener's promise (before, the menu said "Could not load").
* **Element hiding** – generic selectors are registered as user stylesheets
  (`cssOrigin: user`), site-specific selectors are injected on demand by the background.
* **YouTube** – `content/youtube-main.js` runs in the page's world before YouTube's code,
  strips `adPlacements` / `adSlots` / `playerAds` from player data (initial data,
  `JSON.parse`, `fetch`, XHR), removes Shorts ads, disarms the "abnormality detected"
  penalty and skips any ad that still plays. YouTube changes often; this file is what
  needs updating when ads come back.
* **Per-site pause** – a dynamic `allowAllRequests` rule for the site plus
  `excludeMatches` on the content scripts.
* **Dead domains (1.3)** – about 15% of the domains in the big lists no longer exist. Each one
  costs a Safari rule and blocks nothing, so `tools/deadhosts.py` checks them (a name only counts
  as dead when two resolvers both answer NXDOMAIN) and `convert.py` leaves them out. Run it again
  before a release (it needs `pip install dnspython`); a list older than 90 days is ignored.
* **What Bouclier leaves alone on purpose (1.3)** – two "ad-recovery" services put ads back once
  they see an ad blocker and punish blocking: Ad-Shield (Prisma Media and Future plc sites,
  bild.de…) blanks the whole page when its servers are blocked or its re-inserted ads are hidden,
  and Admiral (weather.com) puts a "disable your ad blocker" wall over the page. Beating them
  needs page scripts rather than blocking rules; see `tools/extra-ads.txt`.

## Filter lists and licences

EasyList, EasyPrivacy, EasyList Social/Annoyances and the Adblock Warning Removal List are
dual-licensed GPL-3.0-or-later / CC BY-SA 3.0, and Liste FR is CC BY-SA 3.0: Bouclier uses
them under **CC BY-SA 3.0**, which allows a paid app as long as the authors are credited and
the adapted rules stay under the same licence (https://easylist.to/pages/licence.html, read
2026-09-23). The EasyList Cookie List is CC BY 3.0; the Online Malicious URL Blocklist
(malware-filter, abuse.ch URLhaus data) is CC0. Credits are in the settings page, the app
window and `appstore/support.html`. The YouTube ad removal (`content/youtube-main.js`) is
Bouclier's own code; its approach follows uBlock Origin's public filters, whose code is not
used. `tools/extra-ads.txt`, `tools/extra-privacy.txt` and `tools/extra-french.txt` are Bouclier's
own additions (MIT), compiled into the Ads, Trackers and French sites rulesets: ad and tracking
hosts the lists do not block by host, found with the public ad-block tests and
`tests/safari-sites/` (measure with `bench.sh`, `run.sh` then `compare.py`).

## Safari notes (from WebKit source, 2026-09)

* `getMatchedRules()` in Safari returns `{ request: { url }, timeStamp, tabId }` for each
  blocked load and no rule ids; Chromium returns rule ids and no URL, and limits the call
  to ~20 per 10 minutes. Statistics handle both and use one coalesced call for all tabs.
* A `regexFilter` rule ignores `requestDomains` in Safari's translator, so link-cleaning
  rules never combine the two.
* Link-cleaning redirects cannot loop: each rule only matches when its tag is a real
  query parameter, and matching is case-sensitive like `removeParams`.
* Older Safari (MDN compatibility data, 2026-09): `initiatorDomains` / `excludedInitiatorDomains`
  only exist from Safari 26, so every rule Safari gets uses `domains` / `excludedDomains`
  (Safari 15+; `tools/xcode_setup.py` for the lists, `forSafari()` in `background.js` for the
  user's own rules). `requestDomains`, `registerContentScripts`, `storage.session` and the
  `MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES` constant need Safari 16.4, hence the minimum.
  `cssOrigin: 'user'` and `insertCSS({ origin: 'USER' })` need Safari 18: before that Bouclier
  retries without them (page-level style sheets, still `!important`).
* Dynamic rules are capped by the browser (Safari reports its cap in
  `MAX_NUMBER_OF_DYNAMIC_AND_SESSION_RULES`); `updateDynamicRules()` fails as a whole when a
  call goes over, so `buildDynamicRules()` keeps paused sites first and drops the rest with a
  message shown next to "My filters".
