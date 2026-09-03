# K-Breathe Run

Tymewear VitalPro breathing data on an Amazfit Cheetah 2 Ultra, recorded on the phone and merged into Intervals.icu.

## Subprojects

- [`phone/`](phone/README.md) — **K-Breathe Run**, the Android phone app. Connects to the strap, records sessions, and syncs breathing streams to Intervals.icu. Complete; see its README for setup, usage, and the first real run checklist.
- `watch/` — **K-Breathe**, the Zepp OS workout extension for the watch. Not built yet; it's the next step, and drives session start/stop and the live watch display once it exists.

## Design

See `docs/superpowers/specs/` for the full design, including [`2026-09-03-tymewear-amazfit-design.md`](docs/superpowers/specs/2026-09-03-tymewear-amazfit-design.md), and `.superpowers/sdd/2026-09-03-phone-app/` for the phone app's implementation plan.
