// Pure decision function for the session.start retry latch (see app-side/index.js `pushLive`).
//
// A lost `session.start` (Zepp app or phone app not up yet when the page's onInit fired) must
// not mean the whole run records nothing. The side service latches "the page wants a session
// open" in `wantSession`, and on every successful `/live` poll checks whether the relay still
// has no session for us — using `sessionId` from the live payload as the source of truth rather
// than trusting the original request's result. If the phone's own `start()` is idempotent, this
// is safe to retry indefinitely without creating duplicate sessions.
export function shouldRequestStart(wantSession, payload) {
  if (!wantSession) return false
  if (!payload || payload.ok === false) return false
  return payload.sessionId === null || payload.sessionId === undefined
}
