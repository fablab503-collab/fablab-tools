# Bouclier on simulated iPhones

`run.sh` installs Bouclier's simulator build on each simulator given and runs one UI test
(`UITests/BouclierOnIPhone.swift`) that does what a person does:

1. opens Bouclier's app window;
2. turns Bouclier on in Settings › Apps › Safari › Extensions › Bouclier (iOS 16 and 17: Settings › Safari ›
   Extensions › Bouclier) and sets All Websites to Allow;
3. opens the independent test page (adblock.turtlecute.org, only linked: its licence excludes a paid
   app) in Safari, waits for Safari to compile the rules, runs the test again and reads the score;
4. opens Bouclier's menu from Safari's page menu.

Every step leaves a screenshot and the screen's accessibility tree in `out/<simulator name>/`.

```sh
SIM_ID=<udid> ./build.sh --sim                  # Bouclier for the simulator (once per change)
tests/iphone-ui/run.sh <udid> [<udid> …]         # one simulator after the other
FRESH=1 TAG=" - fix" tests/iphone-ui/run.sh <udid>  # remove Bouclier first; out/<name> - fix/
```

It needs XcodeGen (`brew install xcodegen`); the project is generated on the first run.

`expand-rules.py` and `expanded-app.sh` are the experiment of 3 October 2026 that found why iOS 18
blocked so little (see `compat_rules` in `tools/convert.py`).

Results, 3 October 2026 (1.3.0 build 202610031922, then the fix: commits "Safari before 26: the lists'
compat rulesets" and "Tap an address"):

| iPhone | iOS | Build | Test page | Notes |
|---|---|---|---|---|
| iPhone 16e | 26.5 | 202610031922 | 100 % (132/132) | menu: 130 requests blocked on this page |
| iPhone 17 Pro | 26.5 | 202610031922 | 100 % (132/132) | menu OK |
| iPhone SE (2nd generation) | 18.5 | 202610031922 | 16 % (21/132) | 7-8 % without Bouclier: Safari 18 does not apply requestDomains |
| iPhone SE, one domain per rule | 18.5 | experiment | did not compile | "Too many rules in JSON array" |
| iPhone SE, the same with block rules limited to non-frame types | 18.5 | experiment | 100 % (132/132) | became the compat rulesets |
| iPhone SE (2nd generation) | 18.5 | fix | 100 % (132/132) | menu: 130 requests blocked on this page |
| iPhone 16e | 26.5 | fix | 100 % (132/132) | menu: 130 |
| iPhone 18 Pro Max | 27.0 | fix | 100 % (132/132) | menu: 130 |
| iPhone 18 Pro | 27.0 | fix | 100 % (132/132) | menu: 130, "Tap an address" (the test's step 3 tapped Safari's stop button; fixed, step 4 shows the score) |
| iPhone 13 mini | 18.5 | both (first runs) | not measured | Settings did not open Safari's page when the test tapped it (twice, and the same iOS works on the SE): Bouclier stayed off, 7 % |
| iPhone 17 Pro | 26.5 | fix | not run | |
| iPhone 15 | 17.5 | 202610032138 code | 100 % (132/132) | menu: "Could not load" (Safari 17 sends the menu's messages without an address, and drops answers sent with sendResponse) |
| iPhone 15 | 17.5 | both menu fixes | 100 % (132/132) | menu: adblock.turtlecute.org, 130 on this page, 962 blocked today |
| iPhone 13 mini | 18.5 | fixes + robot fix | 100 % (132/132) | menu works (130 blocked today); the robot now moves a row up from the bottom edge before tapping it |
| iPhone SE (2nd generation) | 18.5 | fixes + frame rules | 100 % (132/132) | menu works; the frame rules compile within Safari 18's limit |
| iPhone 16e | 26.5 | fixes + frame rules | 100 % (132/132) | menu works |

Not run: iOS 16 (Apple's iOS 16 simulators do not run on macOS 27), iPads, real devices. iOS 17.5 came from Apple's developer downloads with Daniel's sign-in (3 October 2026, night). The test follows both Settings paths (iOS 18+: Settings > Apps > Safari; iOS 16 and 17: Settings > Safari), starts from Settings' first page, and `ONLY=<test>` runs one step.