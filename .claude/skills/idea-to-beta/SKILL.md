---
name: idea-to-beta
description: Use when someone describes an app they want and expects it to end up on real people's phones - the whole arc from a spoken idea through spec, CI-only builds, device verification, store listing and Google Play beta testers, including what to do when there is no local toolchain and the person is not available to answer questions.
---

# Idea to beta: getting an app out of someone's head and onto testers' phones

## Overview

The whole job, in the order it actually happens, learned by doing it once: a voice brief on
2026-09-07 became a public, Play Store app in closed testing on 2026-09-08, built entirely on
GitHub Actions from a Mac with no Java and no Android SDK.

**REQUIRED SUB-SKILLS:** `build-1` for the toolchain and sandbox facts, `android-app-2` for the
Android product recipe. This skill is the map they hang on: what phase you are in, what the phase
is for, and the trap that phase sets.

## When to use

- "Build me an app and put it on the store." "I want people to test it."
- Any project where shipping to strangers is the finish line, not a green build.
- Picking up a half-finished app that has never reached a user.

Not for: a library, a script, a one-off analysis, or anything with no install step.

## The nine phases

### 1. Capture the brief without stalling on it

A spoken brief is vague, self-correcting and half-transcribed. Write down what you heard, then
write down the assumptions you are making *because the person is not there to ask*. State them as
assumptions in the spec, do not silently pick and do not stop and wait.

The trap: treating an ambiguity as a blocker. Most are not. "7000 mAh phone" implies Android
without anyone confirming it. Do everything the answer does not change, and ask at the moment the
answer finally matters.

### 2. Verify every fact you are about to build on

Fan out research agents for versions, library behaviour and format specs, and save the results to
files. Never build on a remembered version number.

The trap: a plausible memory. The default artifact of the map library had gone Vulkan-only and
would not install; the "obvious" tile format was broken by three open bugs. Both were found by
looking, neither by thinking.

### 3. Write contracts, not prose

The spec names exact function signatures, which module owns which file, and the shared resource
names (string ids, intent extras, view ids). That is what lets several agents implement in
parallel against no compiler.

The trap: a reviewer "fixing" a contracted signature instead of the call site. Say in the spec that
the contract wins.

### 4. Treat CI as the only compiler

With no local toolchain, every syntax error costs a five-minute round trip. So: read the code you
are calling before you call it, check the import block, and review the whole change once before
pushing. Batch a feature into one push.

The trap: iterating against CI like it is a REPL. Four rounds is a lost half hour. One careful
read beats three hopeful pushes.

### 5. Verify on a real device, with conditions you control

Install, screenshot every screen, then exercise the feature with inputs you chose so the numbers
are checkable. Lay a test route along the exact line your simulator drives. Hold a position rather
than jumping, if the rule you are testing has a delay in it. Read back a measured value and compare
it to the value you simulated: "159 m away" against a simulated 160 m offset validates the
geometry, not just the alert.

The trap: a feature that looks broken but is starved of data. A place lookup returned "no name"
all over a city until the street-level map was downloaded; the code was right, the data was
coarse. Before debugging, check the feature has something to work with.

### 6. Harden before it goes public

Audit what is committed. A key in git history is compromised the moment the repository opens, so
rotate it, purge the blob, delete old releases, and move the secret into CI. Swap commercial fonts
for open-licensed ones. List every third-party licence.

The trap: thinking "private repo" is a security boundary you can undo. It is not; going public is
one API call and history is forever.

### 7. Make the store assets with what you have

No design tools needed: draw an SVG, render it headless, crop it to the required aspect ratios,
and take screenshots from the emulator. Write the listing text as prose a rider would read, not as
a feature list.

The trap: aspect ratios. Stores reject images by a few pixels; check the exact rule before
rendering fifty of them.

### 8. Get through the console

Every form must be answered honestly: content rating, data safety, target audience, foreground
service justification. A new personal developer account cannot reach production until a closed
test has run with at least twelve testers for fourteen days. Plan for that from day one, because
it is a calendar constraint, not a work item.

The trap: answering a data-safety form optimistically. It is a legal declaration. If the app reads
location, say so, and explain that it never leaves the phone.

### 9. Close the loop so testers get every build

The finish line is not a green build or an approved listing. It is a person opening the app on
their own phone and finding today's fix in it. Wire the build to upload straight to the testing
track: internal testing accepts uploads with no review, so it keeps up with a day of rapid
changes. Then the loop is: they ride, they tell you what broke, you push, they have it in minutes.

The trap: stopping at "it is on the store". An app nobody has opened has been tested by nobody.

## The delivery loop, concretely

| Piece | What it does |
|---|---|
| Push to `main` | CI builds, tests, signs, and publishes the bundle |
| Service-account secret | Lets CI upload to Play without a human |
| Internal testing track | No review, live in minutes, up to 100 testers |
| Closed track | Where the twelve-tester clock runs; upload here too |
| Version code | The CI run number, so every build is installable over the last |

The one step you cannot do for someone: creating the service-account key. It is a credential; the
account owner generates it and pastes it as a repository secret. Everything either side of that is
automatable.

Filter the build trigger so documentation cannot cut a release, or every typo fix burns a version
code and ships a build.

## Judgement calls that came up every time

- **Measure before you optimise.** A claim like "rides up to 1000 km" is checkable. Count the
  operations, do not guess the complexity. The answer was "fine", and knowing that was worth more
  than a speculative rewrite.
- **Answer warnings from the artefact.** Unzip the bundle, read the ELF section headers. A warning
  you cannot usefully fix should be understood and written down, not silenced with a change that
  drags a whole toolchain into the build for nothing.
- **Offer the honest tool.** Asked for address search with no geocoder and no house numbers in the
  map data, the right answer was a draggable map with a crosshair that names the street under it,
  plus a plain statement that typing an address is not possible yet. A fake search would have been
  worse than none.
- **A list of "little changes" usually hides one big one.** Sort them, do the small ones, and say
  clearly which one is not small and why.
- **Stop when the work is done.** Verifying your own verification is not progress. See
  `stop-meta-verification-spirals` in memory.

## Rough shape of the effort

| Phase | Wall clock, first time |
|---|---|
| Brief, research, spec | a few hours |
| Implementation to first green build | half a day |
| Device verification and fixes | a few hours per feature round |
| Public hardening | an hour or two |
| Store assets and listing | an hour or two |
| Console forms and first submission | an hour or two, then up to 7 days of review |
| Twelve testers for fourteen days | fourteen days, unavoidable |

Review came back the same day in practice. The tester clock did not.
