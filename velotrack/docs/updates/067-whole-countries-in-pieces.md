# Build 67 — A whole country, in pieces

Date: 10 September 2026. Commit: `<sha>`. Tag: `velotrack-v67`.

## What a rider notices

Picking a country and asking for **Detailed** used to answer *"Could not calculate the size"* and
leave the Download button dead. Now it offers the country as parts:

> **France** — At this detail 9 parts are needed. Each one downloads separately, so you can take the
> whole country or just the part you ride in.
> **Whole country (9 parts)** · **Choose a part** · Cancel

**Whole country** downloads all nine, one after another. **Choose a part** lists them — *France ·
north-west*, *France · centre*, and so on — and downloads the one you pick.

## Why

Reported: *"when i select a country life france and i want to donwload the fully detailes it says
that it couldn calculate the size and it doesen tallowes me to do it."*

The refusal was correct. `PmTilesRemote.plan()` will not plan more than 300,000 grid cells in one
pass, and its comment says why: a whole country at z13–15 *"literally exhausted the download
process's heap (OutOfMemoryError in PmDirectory.decode, first hit downloading all of Germany 'Fully
detailed')"*. The guard replaced a crash.

But "correct" is not the same as "useful". France at Detailed needs 2,274,696 cells — 7.6× the
limit — and **102 of the 195 countries** in the picker are over it. Refusing half the list is a poor
answer to a fair question.

The limit is not raised. Each piece stays under it, so the memory problem it guards against cannot
come back.

## How the split is chosen

Two rules, in order:

1. **Every** piece must fit, not the average. A country is not evenly covered, and a split that fits
   on average still fails on its densest piece.
2. Pieces should be roughly square. Nine horizontal strips across France also satisfies rule 1 and
   is useless — a rider picks the piece they ride in by pointing at it, and nobody thinks of home as
   "the fourth horizontal band of France".

Latitude is split evenly in **Mercator Y**, not in degrees. Degrees would hand a piece near the pole
far more tiles than one near the equator, and equal work per piece is the entire point. Norway's
rows come out within 1% of each other; in degrees they would not.

| Country, Detailed | Split | Largest piece |
|---|---|---|
| France | 3 × 3 = 9 | 237k cells |
| Germany | 2 × 3 = 6 | 214k |
| Spain | 3 × 2 = 6 | 241k |
| Italy | 3 × 3 = 9 | 238k |
| Russia | 38 × 21 = 798 | under budget |

## A bug the tests caught

Russia first came back as **one** piece — a "split" that had not split, which would have sent the
rider round the same refusal one level deeper.

`Mercator.lonToTileX` **clamps** to the last column of the world. The `east = true value + 360`
convention describes a box that *crosses* the antimeridian; a piece cut entirely out of Russia's far
east is not such a box, so `west = 185` collapsed onto x = 8191 and no grid could ever fit. Pieces
that land wholly beyond 180 are now shifted back by 360; a piece that genuinely straddles it keeps
the convention, because that is what the convention is for.

The test that caught it checks **every** piece against the budget rather than the total.

## What changed

| File | Change |
|---|---|
| `download/AreaSplit.kt` | New. `splitForBudget()` and `AreaPart`; no text, because a compass direction is a presentation decision and a bounding box is not |
| `download/AreaSplitTest.kt` | New. 9 tests |
| `download/MapDownloadService.kt` | A start command can carry several pieces; `runQueue` runs them in turn and owns the foreground notification for the whole run |
| `ui/DownloadMapActivity.kt` | `Area.Parts`, the whole-or-part dialog, and the piece names |

The service keeps the foreground notification up **between** pieces on purpose: dropping it would
stop the service, and on Android 12 and later starting the next piece from the background is then
not allowed.

Each piece is stored as its own region under its own name, so a run that stops half way leaves
behind exactly the pieces that finished rather than nothing.

## Deliberately not done

**No size estimate for a whole-country download.** Estimating means planning, and planning nine
pieces of France before fetching a byte is nine full passes over the archive — minutes of waiting to
be told a number the rider already expects to be large. The screen says how many parts there are and
each part reports its real size as it runs.

**A failed piece ends the run.** The pieces share an archive and a build, so whatever stopped one —
no network, no disk, a withdrawn build — stops the rest, and grinding through eight more failures to
say so eight more times helps nobody.

## Size, since it is worth knowing

Thirty-six z15 tiles sampled on an even lattice across France: median 1.2 KB, mean 2.4 KB — and that
lattice missed the big cities (Paris centre alone is 160 KB). France at Detailed is roughly **4–8 GB**
across the nine pieces. Simple across the whole country is 36k cells and has always worked.

## How it was checked

- `splitForBudget` compiled and **run** against 13 assertions covering France, Russia, Norway and a
  city-sized box: all pass, whole suite in 0.14 s including Russia's 798-piece search.
- Every changed file parses against `android.jar`; every new string resource resolves.
- CI: unit tests and a release build.

## Not checked

Not seen on a screen. Unproven: that the two-step dialog reads clearly, that "France · north-west"
is enough for a rider to know which piece they want without a map to look at, and that a nine-part
run actually survives from part 1 to part 9 on a real phone — the queue is new code and the
between-pieces foreground handling is the part most likely to be wrong.

## Rolling back

`git revert <sha>` restores build 65. Countries over the limit go back to refusing. Pieces already
downloaded stay: each is an ordinary region and the older code reads them the same way.
