# K-Breathe Zepp OS watch extension

## Overview

_(written in Task 8)_

## Development

_(written in Task 8)_

## Testing

_(written in Task 8)_

## Simulator notes

The Zeus simulator run described in the task brief's Step 2 has **not** been
performed in this environment. `zeus dev` requires an interactive Zepp
account login, which is not available here. The mock relay itself has been
verified end to end with Node (`curl` against `/health`, `/live`,
`/session/start`, `/session/stop`, and a 404 route, plus the real
`RelayClient` from `shared/relay-client.js` exercised against the running
mock) and is covered by `watch/test/mock-relay.test.js`.

To complete the simulator run, on a machine with a logged-in Zeus CLI:

```bash
# Terminal 1
cd watch && npm run mock-relay

# Terminal 2
cd watch && zeus dev
# choose the Cheetah 2 Ultra 480x480 round target (or the closest available
# round 480 target, such as the Cheetah Pro, if the simulator lacks the new
# device)
```

In the simulator, open the Workout app, add the K-Breathe data widget to a
run page, and start a run.

Expected observations:

- Within 2 s the page shows VE climbing, with the background colour moving
  through teal, blue, amber, orange, red.
- Every 90 s the page goes grey with dashes for 10 s and the status reads
  `stale`.
- The mock relay terminal logs `session start` when the run starts and
  `session stop` when it ends.
- Stopping the mock relay while a run is active: after 5 s the page reads
  `phone?`.
- Restarting the mock relay: values return.

Record any deviation from the above and the fix applied in this section.
