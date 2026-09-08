# Build NN — <short title>

Date: <D Month YYYY>. Commit: `<sha>`. Tag: `velotrack-vNN`.

## What a rider notices

One paragraph, in the words a rider would use. If a build changes nothing visible, say that
plainly and explain what it changes instead.

## Why

The symptom that started it, quoted from the report where there is one, then the actual cause.
This is the part that pays for itself later: a fix whose cause is written down is a fix that
cannot quietly come back under a different name.

## What changed

| File | Change |
|---|---|
| `path/to/File.kt` | one line |

## How it was checked

Name the evidence, not the intention. A screenshot, a log line, a test, a device. If something was
only reasoned about and not run, put it under *Not checked* instead — that is the difference
between this log being useful and being decoration.

- Emulator (`Pixel_10a`, Android 16): …
- Device (Xiaomi 25062RN2DE, HyperOS): …

## Not checked / known risks

What was left, what could still be wrong, and what would show it up. Delete the section only when
it is genuinely empty.

## Rolling back

`git revert <sha>` and the build number that restores. Note anything that does not revert cleanly
— a database migration, a preference whose meaning changed, a file written in a new place.
