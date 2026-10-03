# WebKit benchmark (BouclierBench)

Measures how much Bouclier blocks, without touching Safari or the installed app. `BouclierBench`
loads each page in WebKit (the engine inside Safari) with an extension folder loaded through
`WKWebExtension`, the same code Safari uses for web extensions, and records what every page and
every frame of it loaded. Blocked requests never start, so they are simply absent.

- Pages run in off-screen windows, each with its own throw-away website data (no cookies carry over).
- The consent pop-up is accepted the way most visitors do (Didomi, OneTrust, Sourcepoint, Quantcast,
  Funding Choices, Axeptio, Cookiebot...), because most ads only load after consent. `--no-consent`
  measures first visits instead.
- The judge is independent of Bouclier's rules: `compare.py` counts third-party requests to domains in
  the AdGuard DNS filter, HaGeZi's Multi PRO list, 60 big ad networks, and ad-recovery services
  (Ad-Shield), which the DNS lists leave out. `fetch_refs.sh` downloads those lists into `ref/`; they
  are GPL-3.0 and only used here, never shipped.
- Three site sets: `../safari-sites/sites.tsv` (100 sites; Bouclier's own rules were written from what
  got through there), `sites-heldout.tsv` (60 other sites, never used to write rules: they show whether
  an improvement carries over) and `sites-adshield.tsv` (15 sites that use Ad-Shield).

```sh
./build.sh                                   # builds build/BouclierBench.app (once)
./fetch_refs.sh                              # reference lists (once)
./run.sh none-train none                     # no ad blocker
./run.sh v130-train ../../extension          # the extension folder as it is now
./run.sh v130-heldout ../../extension sites-heldout.tsv
./run-all.sh 0928 none=none v130=../../extension   # both site sets, several builds in a row
python3 compare.py results/none-train.jsonl results/v130-train.jsonl
python3 explain.py results/v130-train.jsonl  # why each remaining request got through
```

Copy the extension folder somewhere else (for example `build/bench-ext/<name>`) to measure a build
while you keep editing. Results go to `results/` (not in git).

## The toolbar menu and website access, as Safari shows them

Two small tools check what the harness above cannot: how WebKit answers Bouclier's website-access
questions, and what the toolbar menu looks like and does. Both load the extension the way Safari does,
with the access Safari stores for it (Safari keeps "Always Allow on Every Website" as `*://*/*`, see
Safari's `WebExtensions/Extensions.plist`).

```sh
./popupshot-build.sh                # builds build/PopupShot.app (an app, so WebKit keeps its compiled rules) and build/PermissionProbe
APPEX="/Applications/Bouclier.app/Contents/PlugIns/Bouclier Extension.appex/Contents/Resources"
build/PermissionProbe "$APPEX"      # permissions.contains / request for each access state
build/PopupShot.app/Contents/MacOS/PopupShot --extension "$APPEX" --grant every-website --site https://www.marmiton.org/ \
  --out build/shots/menu --click '#site-pause .chip[data-minutes="120"]'   # a picture of the menu after each click
```

What `PermissionProbe` showed on macOS 27 (2026-09-29): with `*://*/*` granted,
`permissions.contains({ origins: ['<all_urls>'] })` is `false`, and `permissions.request()` for
`<all_urls>` resolves `true` without asking or granting anything, also when only one site is allowed.
WebKit only asks the browser when no website is allowed at all. So Bouclier checks `*://*/*`, still asks
for `<all_urls>` (the only request that reaches Safari's question), and shows Safari's own button names
when nothing changed.

## The weekly efficacy check

`efficacy.sh` loads the independent test page adblock.turtlecute.org (Turtlecute33/adblocktest,
CC BY-NC-SA 4.0: visited, never copied into Bouclier) in WebKit with the installed app's extension and
records its score, plus how many of its addresses still answer at all (the page counts a dead address
as blocked). Below the alert line (93 % by default), or when the last 100-site scan is more than four
weeks old, it scans the 100 sites again, without and with Bouclier, and compares them with the last
scan (`results/scan-<date>.md`: what got through, on which pages, what changed).

```sh
./efficacy.sh                        # the score; exit 0 at or above the line, 2 below it, 1 if the test could not run
./efficacy.sh --scan --alert 95      # always scan the 100 sites too; a stricter line
./efficacy.sh ../../extension        # an extension folder instead of the installed app
./bg.sh /tmp/efficacy.log ./efficacy.sh   # detached: a remote shell that ends or times out cannot stop it
```

Each run writes `results/efficacy-<date>.md` and one line in `results/efficacy-log.tsv`. A scan takes
30 to 60 minutes: start long runs with `bg.sh` and read the log until its `EXIT` line.
