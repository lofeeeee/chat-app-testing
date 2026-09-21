# Audit — UI/UX and Systems

Results of a full pass over client and server after the Twemoji emoji swap.
All 10 UI/UX items and all 10 systems items are **implemented**; both modules
compile clean (`BUILD SUCCESSFUL`) and the client's desktop test suite passes.

## Emoji change (context)

`noto_color_emoji.ttf` (10.67 MB, high-saturation gradients, exaggerated
expressions) was replaced with **Twemoji** (Mozilla COLR build, v0.7.0,
`composeResources/font/twemoji.ttf`, 1.47 MB — 86% smaller). Flat fills, muted
palette, restrained expressions; the set Discord used for years, so it matches
this client's layout language. The emoji data (`EmojiData.kt`) is font-agnostic
codepoints + shortcodes and needed only one edit: dropped `🛜` (wifi,
U+1F6DC) — a Unicode 15 glyph the Twemoji build (Unicode 14) doesn't cover;
it would render as tofu. `📶` signal covers the intent.

---

## UI/UX — all 10 implemented

1. **Emoji hit targets 34dp → 44dp** (`EmojiPicker.kt`). Material's minimum
   touch target is 48dp; the picker mis-tapped constantly on touch. The
   recents row keeps slightly smaller cells (38dp) to stay on one line.

2. **Reaction chips wrap** (`MessageList.kt` — `ReactionChips` is now a
   `FlowRow`). Messages with more distinct reactions than fit the bubble
   width wrap to a second line instead of overflowing.

3. **Bundled font everywhere** — the hover cluster's react face *and* the
   composer's emoji button drew `😀` from the OS font (Segoe/Apple/Noto),
   visibly clashing with the Twemoji picker they open. Both now use
   `emojiFontFamily()`.

4. **Quick reactions directly on hover** (`MessageHoverActions`). The hover
   cluster shows the full `QUICK_REACTIONS` row — one tap to 👍, no
   open-sheet → pick → close. Same set the long-press sheet leads with.

5. **Hover/press feedback on emoji cells** — soft primary wash at 10% alpha
   plus a 1.12x scale via `graphicsLayer` (draw-only, no re-layout), spring
   at 400 stiffness.

6. **Picker layout stops jumping while searching** — the recents row and
   category tabs collapse via `AnimatedVisibility` (expand/shrink + fade)
   instead of vanishing instantly per keystroke.

7. **Day dividers** (`MessageList.kt`). A `startsDay` flag on
   `RenderedMessage`; a centered label row ("Today" / "Yesterday" /
   "18 September 2024" for other years) between messages on different
   calendar days. Label computed from the message's own date via a
   `todayDateIso()` expect/actual pair (JVM `LocalDate` on both platforms).

8. **Real upload progress** — `SingularClient.putBytes` takes an `onUpload`
   byte callback (Ktor's `onUpload` hook) and all five upload paths
   (attachment, voice note, avatar, guild icon, story) map it into the
   10–80% band, so the bar tracks the actual PUT instead of sitting pinned
   at 80% on slow networks.

9. **Animated reaction counts** — `AnimatedContent` slide-and-fade on the
   count, so a ticking number reads as live activity.

10. **Voice notes are scrubbable** — `AudioPlayer.seekTo()` expect/actual
    (javax `Clip` frame seek on desktop, `MediaPlayer.seekTo` on Android),
    `VoiceNotePlayer.seekToFraction`, and a `detectTapGestures` on the
    waveform `Canvas`: tap → fraction → seek.

## Systems — all 10 implemented

1. **One multiplexed WebSocket instead of six+** (`SingularClient.kt`). The
   old `subscribe()` opened a socket per watcher (message, typing, reaction,
   update, presence, notification — each with its own handshake, ping loop
   and reconnect timer). The `graphql-transport-ws` protocol always supported
   per-operation ids; the client just hardcoded `"1"` and ignored them. A
   `SharedSocket` transport now holds one live socket per URL, registers each
   operation under its own id, fans `next` frames out by id, and fails every
   registration when the socket dies so callers' reconnect loops wake.
   Flows are cold (`channelFlow`/`awaitClose`), unregistering on cancel, and
   a 10s ack-timeout turns an un-acking server into a failed registration
   rather than a collector parked forever.

2. **Reconnect backoff jitter** — all six watcher loops wait
   `jitter(backoff)` (±40%), so a server restart doesn't produce a
   synchronized thundering herd on identical 1-2-4s schedules.

3. **Client WebSocket keepalive** — `pingInterval = 20s` on both the main
   client and the shared-socket transport, so NATs can't silently kill idle
   sockets and death is detected within one interval.

4. **Reconnect gap recovery** — `watch()` refetches the newest page and
   merges it after any socket drop; `appendIfNew`'s id-set dedupe makes the
   merge free. The full resume protocol remains phase 2; this closes the gap
   the cheap way.

5. **O(1) dedupe** — a `messageIds` HashSet backs the list (was
   `messages.none { … }`, O(n) per inbound message), maintained at every
   mutation site; `replaceMessages()` is the one door for page swaps so the
   list and set can never drift.

6. **Scroll-up pagination + bounded window** — `loadOlder()` pages via the
   server's `before` cursor (suspend, so the caller pins scroll position
   across the prepend), `hasOlder`/`loadingOlder` state, and a 250-row
   `MAX_WINDOW` cap that evicts from the end (the newest messages are the
   ones the subscription actively updates). RAM is now constant no matter
   how long a channel runs or how far back someone scrolls.

7. **Shift-tracking hoisted** — one pointer coroutine at the picker root
   publishes `LocalShiftHeld`; cells read the composition local. Previously
   every emoji cell ran its own `while(true) awaitPointerEvent` coroutine —
   dozens alive while the picker was open, all to answer one question.

8. **Pick-time size guard** (both platform pickers) — files over the 100 MB
   server ceiling are refused *before* being read into memory (desktop:
   `file.length()`; Android: the document's `OpenableColumns.SIZE`),
   killing the double-buffer OOM path on low-end devices.

9. **Session revocation evicts live sockets** (server) — a
   `WebSocketSessionRegistry` tracks live GraphQL WS sessions by user;
   `logout` and token-reuse family revocation close every socket that user
   authenticated, in the same request that revoked the session.
   Mechanism: `WebSocketSessionInfo` is a read-only view with no close
   method, so a `WebSocketHandlerDecoratorFactory`
   (`WebSocketCaptureConfig`) captures the closable raw session into the
   shared attribute map at `afterConnectionEstablished`, and the registry
   reads it back and closes with status 4401. `SessionController`'s
   user-driven revokes deliberately don't close sockets — the registry is
   user-keyed, so they'd take the caller's own connection down too;
   documented in code for when it learns per-session keys.

10. **`searchEmoji` fast path** — prefix hits collected with an early bail
    once `limit` is reached, contains-hits capped, and only the candidate
    set is sorted (was: a full-table rank-pair sort per keystroke).

### Notes for review

- One behavioral change to know about: the hover reaction cluster now
  applies the reaction directly instead of opening the long-press sheet —
  that was the point of UI #4.
- `SessionController.revokeSession`/`revokeOtherSessions` still don't close
  sockets (user-keyed registry limitation, commented in code). Logout and
  theft-revocation — the two paths where closing everything is correct — do.
- Android source set compiles only when an SDK is present (see
  `build.gradle.kts`); the desktop target is the one the CI build verifies.

### Honorable mentions (not implemented)

- Fold the 25s heartbeat POST into the WS ping (one fewer HTTP request per
  interval).
- `encodeDefaults = true` on the shared GraphQL codec inflates request
  payloads.
- The node-local rate limiter's N× multiplier is honestly documented but worth
  revisiting before deploying more than one instance (see
  `TokenBucketRateLimiter` header comment).
