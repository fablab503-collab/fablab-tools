---
name: google-play-listing
description: Use when writing or changing a Google Play store listing, setting or changing an app's price, preparing screenshots and feature graphics, planning promotional events, or working out how a free open-source app can take money. Especially when someone proposes charging for an app that has already shipped free, or adding a donate button.
---

# Google Play listing and commercial work

## Overview

The store listing is the product for everyone who has not installed the app yet. Most of the cost
here is not writing — it is the handful of Play rules that are **one-way doors**, where getting it
wrong means a new package name and starting the listing over.

Check the one-way doors before writing a word.

## One-way doors — check these first

| Rule | Consequence |
|---|---|
| **A free app can never become paid.** "Once your app has been offered for free, the app can't be changed to paid." | The only route to charging is a **new app with a new package name**. Paid → free is fine; free → paid is not. Verify at [support.google.com/googleplay/android-developer/answer/6334373](https://support.google.com/googleplay/android-developer/answer/6334373) — this is the single most expensive thing to get wrong. |
| **Package name is permanent.** | Cannot be changed after the first upload, ever. |
| **Deleting an app does not free its package name.** | Choose it as if it is forever. |

Ask "will this ever cost money?" **before** the first upload, not after.

## Donations and open source

Google Play forbids in-app billing for donations *and* forbids linking out to Patreon, Ko-fi,
GitHub Sponsors, Liberapay or PayPal from inside the app. StreetComplete — an OpenStreetMap app —
was made to strip exactly those links, including a link to a project home page that merely
*contained* donation information.

**Safe pattern for a free open-source app:**
- Support links live on GitHub only. Not in the APK, not in the About screen, not in the listing.
- Do not add a "Support us" button later; it is the same violation.
- A registered charity is the exception, and only through the approved flow.

If the goal is revenue rather than tips, the honest options are a paid listing decided *before*
first upload, or a separate paid app with a different package name.

## Limits, checked at write time

| Field | Limit |
|---|---|
| App name | 30 characters |
| Short description | 80 characters |
| Full description | 4000 characters |
| Release notes / What's new | 500 characters, per language, keep the `<en-GB>` tags |

Count them programmatically before pasting. An 82-character short description is rejected in the
console after you have already lost the context you wrote it in.

## Graphics

| Asset | Requirement |
|---|---|
| Icon | 512 x 512 PNG |
| Feature graphic | 1024 x 500 |
| Phone screenshots | 2 to 8, **aspect ratio must not exceed 2:1** |

Modern phone and emulator screens are taller than 2:1 (1080 x 2424 is 2.24:1) so raw captures are
rejected. Crop, do not scale:

```bash
sips --cropToHeightWidth 2160 1080 shot.png --out shot.png   # 1080x2424 -> exactly 2:1
```

No design tools needed for the rest: `qlmanage -t -s 1024 -o . file.svg` renders an SVG, then
`sips --cropToHeightWidth` to the shape you need.

**Screenshots must show the build you are shipping.** A listing whose shots predate the last six
features is a listing that undersells the app and misleads the reader. Check the numbers *inside*
the screenshots too — a statistics screen reading "3.08 km ridden in total" is test data, not a
store asset.

## Writing the listing

Structure that works, in order: one sentence on the problem → why this app and not the obvious
alternative → capability blocks under plain headings → what it does not do → privacy and
requirements → licence and attribution.

- **Lead with the constraint the app removes**, not the feature list. "Works where your phone
  doesn't" beats "offline vector maps".
- **Name the alternatives honestly.** Saying what the app is *not* (no training platform, no
  segments, no leaderboards) filters out the one-star reviews from people who wanted that.
- **Every claim must be in the shipping build.** If a feature slips, cut the line. A listing is a
  promise.
- Keep a **positioning section** in the listing source file even though it does not go in the
  console — it is what you paste into a README, a press note or an app-store reply.

## Promotional content ("Events")

Under Grow → Store presence → Promotional content. Two things people get wrong:

- **They require the app to be live in production.** Nothing to do while in closed testing.
- **An event with no hook is worse than no event.** It needs a real reason to open the app this
  week — a launch, a season, a deadline.

## Driving the console

Direct URLs work and the collapsible side nav does not: `app-content/<page>`, `main-store-listing`,
`store-settings`, `tracks/internal-testing`, `closed-testing`, `publishing`.

- The console is Angular: setting a field programmatically does **not** trigger its validation and
  can silently fill hidden duplicate inputs. Click, type, Tab — then reload if a field goes invalid.
- Graphics: **Add assets** opens an asset library. Upload through the hidden `input[type=file]`,
  then hover the asset row → arrow → **Add**.
- New personal accounts: setup → internal test → closed test (all countries + tester list). "Send
  changes for review" only unlocks after the closed-test release is saved. Production needs 12
  testers for 14 days.

## Common mistakes

- Writing the listing before checking whether the app will ever be paid.
- Adding a donate link "just in the About screen".
- Shipping screenshots from an old build, or with test data visible.
- Letting a documentation commit trigger the release workflow and burn a version code — exclude
  `**.md` and `store/**` in the workflow `paths` filter.
- Claiming a feature that is built but not yet shipped in the artifact users receive.

## Status

Written 2026-09-10 from facts verified against Google's own documentation that day. The pricing,
donation and aspect-ratio rules were each confirmed against a primary source rather than recalled.
This skill has **not** been through the subagent baseline testing that `superpowers:writing-skills`
requires — it is a reference skill, and the retrieval scenarios have not been run against it.
