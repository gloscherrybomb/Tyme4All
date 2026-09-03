# K-Breathe Run

Tymewear VitalPro breathing data on an Amazfit Cheetah 2 Ultra, recorded on the phone and merged into Intervals.icu.

## Subprojects

- [`phone/`](phone/README.md) — **K-Breathe Run**, the Android phone app. Connects to the strap, records sessions, and syncs breathing streams to Intervals.icu. Complete; see its README for setup, usage, and the first real run checklist.
- [`watch/`](watch/README.md) — **K-Breathe**, the Zepp OS workout extension for the watch. Drives session start/stop and the live watch display; see its README for the build/install commands and [`watch/DAY-ONE.md`](watch/DAY-ONE.md) for the checklist to run through once the watch is in hand.

## Design

See `docs/superpowers/specs/` for the full design, including [`2026-09-03-tymewear-amazfit-design.md`](docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md), and [`docs/superpowers/plans/2026-09-03-phone-app.md`](docs/superpowers/plans/2026-09-03-phone-app.md) for the phone app's implementation plan (the watch extension's plan is at [`docs/superpowers/plans/2026-09-03-watch-extension.md`](docs/superpowers/plans/2026-09-03-watch-extension.md)).
