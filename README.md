# Singular

Working name. A multiplatform chat platform — Discord's server/role model crossed with WhatsApp's
DM and status model.

**Architecture blueprint:** https://claude.ai/code/artifact/f38b3300-1766-447d-8bd2-2a236f86fef0

---

## Ground rules

1. **No remote runtime dependencies.** Nothing is fetched at startup or at runtime from a host you
   don't control. Fonts, icons and emoji ship inside the binary. Every backing service
   (Postgres, Valkey, MinIO) is self-hosted. The only unavoidable external edges are FCM and APNs,
   because Apple and Google own the only sockets to a sleeping phone — those sit behind a single
   `PushTransport` interface and the app degrades to in-app notifications when they're unreachable.
2. **No webview.** The client renders with Skia straight to the GPU via Compose Multiplatform.
3. **Postgres is the source of truth.** Valkey holds only disposable state. MinIO holds only bytes.

## Layout

```
singular/
├── server/          Kotlin + Spring Boot + Spring for GraphQL.  Builds with just a JDK.
├── client/          Kotlin Multiplatform + Compose.  Desktop needs a JDK; Android needs the SDK.
├── docker-compose.yml   Postgres 17, Valkey, MinIO
└── docs/            Design notes
```

The two Gradle builds are deliberately independent — a missing Android SDK must never block the
backend build.

## Feature status

Numbered against your original list.

| # | Feature | State |
|---|---|---|
| 1 | Private DM | ✅ |
| 2 | Group DM | 🟡 schema + channel type; no group-creation UI |
| 3 | Servers | ✅ create, channels, categories, invites, leave, delete |
| 4 | Online / Away / DND / Offline | ✅ + Invisible, with heartbeat |
| 5 | Stories (WhatsApp/Insta style) | ✅ tray, composer, viewer, overlay compositor |
| 6 | Emoji / files / audio / location | ✅ end to end — pick, upload, render |
| 7 | Push notifications | 🟡 registration + mute/DND filter done; delivery needs your FCM/APNs keys |
| 8 | IP/device/agent + action logging | ✅ per-session, not per-message |
| 9 | "Hashing" | ✅ split into hash vs encrypt |
| 10 | Server roles | ✅ 128-bit bitfield, hierarchy, channel overwrites |
| 11 | Blocking + muting | ✅ separate tables, separate semantics |
| 12 | Tagging / mentions | ✅ parsed on send, mentions inbox |
| 13 | Custom pfp / border / banner / about me / handle | ✅ fields, editor, and an upload path |
| 14 | Per-server nickname | ✅ |
| 15 | Blocked-but-shared collapse | ✅ both layouts |
| 16 | Custom primary + secondary colour | ✅ in Settings, with a contrast floor |
| 17 | Rich presence | ✅ volatile, with a staleness ceiling |
| 18 | Server folders | ✅ server-side; no drag-and-drop UI yet |
| 19 | Custom server icon | ✅ |
| 20 | Music on stories | 🟡 overlay model supports it; needs a licensed source |

Plus, not on your list: QR sign-in with a 20s rotating code, session/device management,
typing indicators, two chat layouts, Enter-to-send.

### What's left

- **Push delivery.** Registration, the mute/DND filter and fanout are all real. Only the final
  hop is a `LoggingPushTransport` stub — Apple and Google own the only sockets to a sleeping
  phone, and that needs your credentials.
- **A licensing decision for #20.** The overlay model already carries a music widget, but
  Instagram streams commercial clips because Meta holds label deals, and Spotify killed
  `preview_url` for new apps permanently in 2024. Realistic options: user-uploaded audio, or
  metadata plus a deep link into the listener's own music app.
- **Voice recording.** Voice notes are modelled, uploaded and rendered from precomputed peaks;
  capturing audio needs a platform recorder (`AudioRecord` on Android, `javax.sound` on desktop)
  plus Opus encoding.
- **Direct manipulation of story overlays.** The composer places a caption and the viewer draws
  every overlay type; dragging, pinching and rotating them on the image is not wired. The data
  model already carries `x`, `y`, `rot` and `scale`, so this is a gesture handler rather than a
  redesign.

## Media pipeline

Uploads go **straight to storage** — bytes never pass through the application:

```
1. createUpload      -> server mints an id + object key, returns a presigned PUT.
                        Content type and length are SIGNED INTO the URL, so the client
                        cannot upload something other than what it declared.
2. PUT to MinIO      -> direct. A 100 MB video never occupies a request thread.
3. finalizeUpload    -> server HEADs the object, checks the size matches, strips EXIF from
                        images, builds a thumbnail, and flips the row to READY.
```

Step 3 is what makes step 2 safe. Until it runs the row is only a *claim*; a client cannot
move an attachment to READY by asserting it.

**EXIF stripping is not cosmetic.** Phone cameras embed GPS in JPEGs by default, so an
unprocessed photo posted to a channel publishes where it was taken — and one taken at home
publishes an address. It happens server-side, because a client can simply not do it, and the
client that doesn't is the one leaking.

Orphaned uploads (composed, never sent) are reaped after 6 hours.

On the client: the paperclip opens the platform picker (Swing on desktop, `ACTION_OPEN_DOCUMENT`
on Android), uploads with a progress bar, and sends. Waveforms come down with the message as
0-100 peaks, so a voice note draws instantly instead of downloading and decoding audio first —
the difference between a list that scrolls and one that stalls on every voice message.

Images are loaded with Coil 3 and cached under the **attachment's snowflake, never its URL**.
Presigned URLs get a fresh signature on every fetch, so keying the cache on one would miss every
single time and re-download the same picture forever. The frame is laid out from the
server-recorded dimensions *before* any pixels arrive, so the message list never jumps as images
resolve.

## Story overlays

Overlays are **data, not pixels**. A story stores a JSON list of positioned elements and the
client composites them over the media at view time:

```json
[{ "type": "text", "x": 0.14, "y": 0.70, "rot": -4, "scale": 1,
   "value": "back on the road", "style": "plate", "color": "#F0B232" }]
```

Coordinates are **fractions of the frame**, not pixels — a story composed on a phone has to put
the sticker in the same place when read on a desktop, and absolute coordinates drift with every
screen size.

Types rendered today: `text` (plain or on a colour plate, with the foreground picked by
luminance so it stays readable), `sticker`/`emoji`, `mention`, `location`, and `music`. An
unknown type from a newer client is skipped silently rather than drawing a placeholder box.

The music widget is **metadata only** — title, artist, and a link out. That is the whole
licensing distinction: showing what someone is listening to and deep-linking into their own
music app needs no label deal; streaming a clip does.

The server never interprets any of this. It stores and returns the JSON, so adding an overlay
type costs no migration and no server deploy.

## Ports

Singular's MinIO runs on **9100/9101**, not the usual 9000/9001, and Valkey on **6380**.
Another project on this machine already publishes 9000 — and a Docker port clash does not fail
loudly: the container starts *without* publishing, your app connects to whatever else is on
that port, and you get authentication errors that look like bad credentials rather than a
wrong server. That is exactly what happened while building this.

## Chat layouts

Two, switched in **Settings → Appearance** (not in the conversation header — it's a preference
you set once, not an action on a particular chat):

- **Bubbles** — WhatsApp-style. Yours right, theirs left, time inside the bubble, corners
  squared where bubbles stack so a run reads as one block.
- **Compact** — Discord-style. No bubbles, name and time on the first line of a run only.

Both group consecutive messages by the same author, breaking on a gap over 5 minutes. The
avatar appears once per run; continuation lines get a blank gutter so text stays aligned.

**Handles never appear in the message list.** `name#0971` is how people find and add each
other; it shows in the sidebar and on your profile, and nowhere else.

## Permissions

A 128-bit bitfield per role, mirroring Discord's model because it's well designed and anyone
who has run a Discord server already knows how it behaves. `PermissionEngine.kt` resolves in a
fixed order, and that order *is* the security model:

```
1. owner                 -> everything
2. base = OR of all roles held (@everyone included)
3. ADMINISTRATOR         -> everything, bypassing every overwrite below
4. @everyone overwrite   -> deny, then allow
5. role overwrites       -> UNIONED first, then applied as one
6. member overwrite      -> deny, then allow. Final word.
7. no VIEW_CHANNEL       -> nothing
```

Step 5 is the one implementations get wrong. Applying role overwrites one at a time makes the
result depend on the order roles happen to load in — so a grant on any role correctly beats a
deny on another, instead of whichever was applied last.

Two escalation guards on top: you cannot manage a role at or above your own highest, and you
cannot grant a permission you don't hold yourself. Without the second, `MANAGE_ROLES` is just a
slow path to `ADMINISTRATOR`.

## Running it

### 1. Backing services

```bash
docker compose up -d
```

Postgres on `5432`, Valkey on `6379`, MinIO on `9000` (console `9001`, `singular` / `singular-dev-only`).

### 2. Server

```bash
cd server
./gradlew bootRun
```

- GraphQL endpoint: `http://localhost:8080/graphql`
- WebSocket (subscriptions): `ws://localhost:8080/graphql`
- GraphiQL (dev only): `http://localhost:8080/graphiql`

Flyway applies `V1__baseline.sql` on boot and creates monthly partitions through 2027.

### 3. Desktop client

```bash
cd client
./gradlew :composeApp:run
```

The Android target is **conditional on an SDK being present** — the build looks at
`local.properties` (`sdk.dir`), then `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then the platform's
default location. Without one, the build logs `No Android SDK found — building desktop only`
and skips Android entirely rather than failing configuration. Install the SDK or set
`ANDROID_HOME` and the target reappears with no edit to any build file.

Gradle wrappers are checked in; both projects build with a JDK 21 and nothing else.

## Verification

Everything below was run against a live server and a real PostgreSQL 17, not asserted:

```bash
cd server && ./gradlew build          # 12 unit tests
docker compose up -d && ./gradlew bootRun
pwsh scripts/e2e-api.ps1              # 44 checks over the real GraphQL API
pwsh scripts/e2e-qr-ws.ps1            # 9 checks over a real graphql-ws socket
```

`e2e-api.ps1` covers registration and discriminator allocation, refresh-token rotation and reuse
detection, DM idempotency, message paging, authorisation boundaries, the session list, and the
whole QR state machine. `e2e-qr-ws.ps1` covers what HTTP can't: the anonymous socket handshake,
`SCANNED` and `APPROVED` push, token delivery on the poll-secret channel, and two negative cases
(wrong poll secret, anonymous subscribe to messages).

Four bugs were found and fixed this way, all of which would have shipped:

| Bug | Symptom |
|---|---|
| `revokeFamily` ran inside the `@Transactional` method that then threw | The throw rolled the revocation back, so **detecting a stolen refresh token left every stolen session live** — the exact opposite of intent. Fixed with `noRollbackFor`. |
| `AuditLog.record` joined the caller's transaction | Every `LOGIN_FAILED` and `TOKEN_REUSE_DETECTED` row was rolled back by the throw that followed it. The security events most worth recording were the ones silently vanishing. Fixed with `REQUIRES_NEW`. |
| Bare `? IS NULL` bind on the message cursor | `could not determine data type of parameter $4` — the first page load of any channel, at runtime. Fixed with an explicit `CAST(:before AS bigint)`. |
| One-sided partition predicate | Pruned older partitions but scanned every future one: 17 index scans where 4 suffice. Fixed with a two-sided `created_at` window. |

## Try it without the client

```bash
# Register — the server allocates a random discriminator
curl -s localhost:8080/graphql -H 'content-type: application/json' -d '{
  "query": "mutation($i:RegisterInput!){ register(input:$i){ accessToken user{ handle } } }",
  "variables": {"i":{"username":"alex","email":"alex@example.com","password":"correct-horse-battery"}}
}'
```

Register a second user, call `openDirectMessage`, then `sendMessage`. Subscribe to
`messageCreated` from GraphiQL in a second tab to watch it arrive live.

## Configuration

Everything is env-overridable; see `server/src/main/resources/application.yml`.

| Variable | Default | Notes |
|---|---|---|
| `SINGULAR_NODE_ID` | `1` | Snowflake worker id. **Must be unique per running instance** (0–1023). |
| `SINGULAR_TOKEN_SECRET` | dev value | HMAC key for access tokens. **Replace before any deployment.** |
| `SINGULAR_PEPPER` | dev value | Server pepper for blind indexes. Changing it invalidates every email lookup. |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local docker | |

## QR sign-in

Scan a code on your desktop with a phone you're already signed in on. The code changes every
20 seconds.

```
desktop                          server                        phone (signed in)
   │  createLoginRequest            │                                │
   │ ──────────────────────────────>│                                │
   │  <── qrToken + pollSecret ─────│                                │
   │                                │                                │
   │  subscribe(id, pollSecret) ───>│                                │
   │  ...every 20s: rotate ────────>│                                │
   │                                │  <──── claimLoginRequest ──────│  scans
   │  <── SCANNED ──────────────────│  ───── device / IP / OS ──────>│  confirm screen
   │                                │  <──── approveLoginRequest ────│  user taps Approve
   │  <── APPROVED + tokens ────────│                                │
```

### Why two secrets

Creation returns a **qrToken** (public, in the QR, rotates) and a **pollSecret** (private, never
displayed, never rotates). If the QR token were the only credential, rotating it would achieve
nothing — the device would have to keep honouring the old value to stay subscribed. Splitting
them lets the visible half rotate freely while the channel that eventually carries the tokens
stays put.

Tokens are delivered **only** on the pollSecret-authenticated subscription, never to whoever did
the scanning.

### What rotation does and does not protect against

- **Does:** replay of a code captured off a screen — a screen share, a photo, a shoulder surfer,
  a leaked screenshot. A 20-second window makes a captured code near-useless.
- **Does not:** QRLJacking, where an attacker shows you *their* login QR and gets you to scan it,
  landing your account on their machine. No token lifetime helps there. The defence is the
  approval screen naming the requesting device, platform and IP, plus a separate explicit tap.
  **Never auto-confirm that step and never bury the details.**

Timings live in `QrLoginService`: token TTL 25s, client rotates at 20s (the 5s of slack stops a
scan landing at 19.9s from failing on a race the user can't see), whole request expires after
3 minutes.

## Sessions

`sessions` lists one row per **rotation family**, not per session row. Refresh rotation mints a
new `auth_sessions` row every 15 minutes, so listing raw rows would show the same laptop dozens
of times a day. The family is what a person recognises as "a device".

Rows are tagged with how the session started — `PASSWORD`, `QR_CODE` or `REFRESH` — because a QR
sign-in you don't recognise is exactly the shape a successful QRLJacking attack leaves behind.

`revokeSession` takes a family id and revokes the whole chain, so a rotated descendant can't
survive its parent. Ownership is enforced in the SQL `WHERE` clause, so a forged id revokes
nothing rather than someone else's laptop.

## Decisions worth not re-litigating

- **Snowflake IDs, serialised as strings.** JavaScript silently corrupts integers above 2^53. Every
  ID crosses the wire quoted.
- **`messages` is partitioned from commit one.** Monthly `RANGE` on `created_at`. Repartitioning a
  live table with billions of rows is a migration nobody enjoys; the cost today is zero.
- **Idempotency lives in its own table.** Postgres requires a unique index on a partitioned table to
  include the partition key, which would defeat nonce dedup — so nonces get a small unpartitioned
  table that gets reaped after 24h.
- **Blocks and mutes will be separate tables.** Blocks are visibility rules, mutes are notification
  rules. They look similar and merging them is a trap.
- **Access tokens are stateless and short-lived (15 min); refresh tokens are opaque, hashed, and
  rotate on every use.** A stolen refresh token is detectable — reuse of a rotated token revokes the
  whole session family.
- **Anonymous WebSockets are allowed, narrowly.** A device waiting on a QR sign-in has no bearer
  token yet, so `loginRequestUpdated` has to work unauthenticated; it uses the poll secret
  instead. Every other subscription calls `requirePrincipal()` and fails, so an open anonymous
  socket buys an attacker nothing.
- **`messages` pagination sends a two-sided `created_at` window.** A floor alone prunes older
  partitions but still scans every future one — measured on Postgres 17, one-sided planned 17
  index scans where two-sided planned 4.
