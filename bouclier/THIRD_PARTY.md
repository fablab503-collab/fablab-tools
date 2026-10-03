# Third-party material in Bouclier

Bouclier's own code is MIT licensed (see the repository [LICENSE](../LICENSE)).

## Filter lists (downloaded at build time, not stored in this repository)

The compiled rules that ship inside a built app are adapted from these lists and stay under the
list's licence. `tools/lists.json` is the authoritative list with URLs.

| Toggle | List | Licence |
|---|---|---|
| Ads | [EasyList](https://easylist.to/) | CC BY-SA 3.0 (dual-licensed with GPL-3.0-or-later; used under CC BY-SA 3.0) |
| Trackers | [EasyPrivacy](https://easylist.to/) | CC BY-SA 3.0 (same) |
| French sites | [Liste FR](https://github.com/easylist/listefr) | CC BY-SA 3.0 |
| Malware & phishing | [Online Malicious URL Blocklist](https://gitlab.com/malware-filter/urlhaus-filter) (abuse.ch URLhaus data) | CC0 1.0 |
| Anti-adblock walls | [Adblock Warning Removal List](https://easylist.to/) | CC BY-SA 3.0 (dual with GPL-3.0-or-later) |
| Cookie banners | [EasyList Cookie List](https://secure.fanboy.co.nz/) | CC BY 3.0 |
| Social widgets | [EasyList Social Widgets](https://easylist.to/) | CC BY-SA 3.0 (dual with GPL-3.0-or-later) |
| Annoyances | [EasyList Other Annoyances](https://easylist.to/) | CC BY-SA 3.0 (dual with GPL-3.0-or-later) |

Credits appear in the extension's settings page, the app window and `appstore/support.html`.
The YouTube ad removal (`extension/content/youtube-main.js`) is Bouclier's own code; its approach
follows uBlock Origin's public filters, whose code is not used.

`tools/extra-*.txt` are Bouclier's own additions to the lists (MIT). `tools/deadhosts.py` writes
`tools/dead-hosts.json`, the lists' domains that no longer exist; it is made from the lists, so it
is not stored here either (`convert.py` works without it).

## Test material (downloaded or opened at test time, not stored here)

- `tests/webkit-bench/fetch_refs.sh` downloads the lists the benchmark judges with: the AdGuard DNS
  filter and HaGeZi's Pro list (GPL-3.0) and the Public Suffix List (MPL-2.0).
- The independent test page adblock.turtlecute.org (Turtlecute33/adblocktest, CC BY-NC-SA 4.0) is
  only linked from Bouclier and read by the test scripts; none of its code is copied.

## Fonts (screenshot tool only)

Familjen Grotesk and Source Sans 3 in `tools/screenshots/fonts/`, SIL Open Font License 1.1
(licence texts next to the font files). They are used to draw the App Store screenshots, not in the app.
