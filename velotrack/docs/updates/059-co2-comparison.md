# Build 59 — what a car would have emitted, on the map

Date: 9 September 2026. Commit: pending. Tag: `velotrack-v59`.

## What a rider notices

A small leaf chip above the speed: **0.4 kg vs car**. It is the CO₂e an average car would have
emitted over the distance you have ridden, all-time. Tapping it opens a screen with the working:
the equivalent in petrol and phone charges, where every number comes from, and an honest account of
what the figure does and does not mean.

## Why the wording is what it is

Asked for: an emissions figure on the home screen, and a screen explaining how much to cycle to
compensate a carbon footprint. Research changed both.

**"You saved X kg" is not a claim this app can defend.** Cycling does not remove CO₂; it avoids
emissions, and only when it replaces a journey that would have been driven. No study establishes a
car-substitution rate for recreational riding, and riders often drive to the start of a ride, which
cuts the other way. A meta-analysis restricted to controlled designs (Chevance et al., 2025) found
no statistically significant car reduction at all. Strava, Love to Ride and Ride with GPS all
assume 1:1 substitution; that is the norm and it is wrong.

**"Cycle X km to offset your footprint" is worse.** At a generous quarter substitution it would take
roughly 9,500 km a year to match just the car share of an average European footprint, and it still
offsets nothing, because avoided emissions are never netted against an inventory (WBCSD 2025,
SBTi). Directive (EU) 2024/825 Annex I point 4c bans offsetting-based claims of neutral or reduced
climate impact outright, with no substantiation defence, and it applies from **27 September 2026**.

So the headline is a statement about cars, not a credit to the rider, and the avoided figure only
appears once the rider says how much of their riding replaced a drive.

## What changed

| File | Change |
|---|---|
| `core/.../green/CarbonEstimate.kt` | New. Every constant carries its source and year in the doc comment. Car 209.9 g/km (DESNZ 2026, average car, well-to-wheel — the tailpipe-only 165.91 would understate by 21%). Bicycle 5.1 g/km (Brand et al. 2021; food excluded, and the 21 g/km food-inclusive figure is offered rather than averaged in). Substitution 0.24 (Bigazzi and Wong 2020). Petrol and phone-charge equivalences from the US EPA. |
| `core/src/test/.../CarbonEstimateTest.kt` | 11 tests. They pin the published constants as much as the arithmetic: swapping the well-to-wheel factor for the tailpipe one, or rounding the car figure, moves a rider's number away from the citation printed beside it. |
| `app/.../ui/CarbonActivity.kt` + `activity_carbon.xml` | The detail screen: headline, the honesty card **before** anything congratulatory, a substitution slider defaulting to 0, and the sources. |
| `activity_main.xml`, `layout-land/activity_main.xml` | The chip, above the speed in both orientations. |
| `MainActivity.kt` | Reads all-time totals on resume, not per GPS fix — the figure moves by grams between fixes and a database read each time would cost more than it is worth. Hidden below 0.1 kg. |

## What is deliberately not there

- No tree equivalence. It converts an avoided flow into an implied removal, which is what the new
  rules target.
- No "green", "eco" or "climate friendly" labelling.
- The figure is never subtracted from anything, and there is no offset framing.
- The bicycle is not called zero-emission. It is 5.1 g/km, and the screen says so.

## How it was checked

- 11 unit tests pass, including that the car factor is the well-to-wheel one and that a ride which
  replaced nothing avoids nothing.
- On the emulator: recorded a real 2.09 km ride, finished it, and the chip appeared reading
  "0.4 kg vs car". Tapping it opened the detail screen showing 0.4 kg, "about 0.2 litres of petrol
  burned, or 35 phone charges", the honesty card and the sources card with the DESNZ figure quoted.
  Crash buffer empty.
- A status-bar overlap on the new screen's toolbar was found by screenshot and fixed with
  `fitsSystemWindows`.

## Not checked

- The substitution slider's avoided figure was not exercised on a device; only its zero state was
  seen. The arithmetic behind it is unit-tested.
- No real-device run. The emulator was the only device available with ride history.

## Rolling back

`git revert <sha>`. Nothing persistent is written; the figure is derived from existing ride rows.
