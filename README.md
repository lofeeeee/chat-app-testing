# Singular

*Working name.*

A multiplatform chat application — Discord's server/role model crossed with WhatsApp's
DM and status (stories) model. One Kotlin codebase renders natively on desktop and Android
(no browser, no webview), backed by a self-hosted GraphQL API.

**Architecture blueprint:** https://claude.ai/code/artifact/f38b3300-1766-447d-8bd2-2a236f86fef0

## What it does

- **Servers** with categories, channels, invites, and a 128-bit role/permission system
- **Direct messages** (private, group-schema-ready) and **presence** (Online/Away/DND/Offline/Invisible)
- **Stories** — a tray, composer, and viewer with positioned overlays (text, stickers, mentions, location, music)
- **Media** — image/file/audio upload straight to object storage, with EXIF stripping and thumbnailing
- **Push notifications**, per-session device/IP/action logging, blocking + muting, mentions, custom profiles
- **QR sign-in** — scan a rotating code on desktop with an already-signed-in phone

See [Feature status](#feature-status) below for what's done vs. in progress.

## Tech stack

| Layer | Technology | Notes |
|---|---|---|
| **Client** | Kotlin Multiplatform + Compose Multiplatform | Renders with Skia straight to the GPU — no webview. Desktop + Android from one `commonMain` source set. |
| **Client networking** | Ktor client + `graphql-ws` | Ktor engine varies per platform (CIO on desktop, OkHttp on Android). |
| **Client images** | Coil 3 | Cached by attachment snowflake, not URL (presigned URLs re-sign on every fetch). |
| **Client QR** | ZXing (pure-Java `core`) | Same artifact on desktop and Android. |
| **Server** | Kotlin + Spring Boot 3 (Spring for GraphQL, WebFlux/WebSocket, Actuator) | JVM 21, virtual threads + ZGC. |
| **API** | GraphQL over HTTP + `graphql-ws` subscriptions | Live updates (messages, presence, login requests) over a single socket type. |
| **Database** | PostgreSQL 17 | Source of truth. Monthly-partitioned `messages` table, Flyway migrations. |
| **Cache / pub-sub** | Valkey (via Lettuce) | Ephemeral state only — presence, typing, rate limits, fanout. |
| **Object storage** | MinIO (S3-compatible, via AWS SDK v2) | Direct presigned-URL upload/download; swappable for real S3 later. |
| **Auth / crypto** | Argon2id (Spring Security Crypto + BouncyCastle), HMAC-signed access tokens, rotating opaque refresh tokens | |
| **Build** | Gradle (Kotlin DSL), JDK 21, checked-in wrappers | Server and client build independently — a missing Android SDK never blocks the backend. |
| **Infra (dev)** | Docker Compose (Postgres, Valkey, MinIO) | |

### Ground rules

1. **No remote runtime dependencies.** Nothing is fetched at startup or runtime from a host you
   don't control. Fonts, icons, and emoji ship inside the binary. Every backing service
   (Postgres, Valkey, MinIO) is self-hosted. The only unavoidable external edges are FCM and
   APNs — Apple and Google own the only sockets to a sleeping phone — and those sit behind a
   single `PushTransport` interface, degrading to in-app notifications when unreachable.
2. **No webview.** The client renders with Skia straight to the GPU via Compose Multiplatform.
3. **Postgres is the source of truth.** Valkey holds only disposable state. MinIO holds only bytes.

## Layout

```
singular/
├── server/              Kotlin + Spring Boot + Spring for GraphQL      -> server/README.md
├── client/              Kotlin Multiplatform + Compose                 -> client/README.md
├── scripts/             End-to-end checks against a live server        -> scripts/README.md
├── docker-compose.yml   Postgres 17, Valkey, MinIO
├── start.bat            Starts services + server + one client window
├── start_another.bat    Opens a second client (for testing two accounts)
├── build.bat            Incremental build of both projects
├── rebuild.bat          Clean rebuild
└── _env.bat             Shared JDK discovery. Not run directly.
```

**Each folder has its own README** covering the decisions specific to it:

| | |
|---|---|
| [`server/README.md`](server/README.md) | Package map, the permission engine, hash-vs-encrypt, why Valkey is mandatory, migrations |
| [`client/README.md`](client/README.md) | Compose state rules, the two chat layouts, image caching, platform `expect`/`actual` pieces |
| [`scripts/README.md`](scripts/README.md) | What the E2E suites assert, and the bugs they caught |

The two Gradle builds are deliberately independent: a missing Android SDK must never be able to
block the backend.

## Running it

### The short way (Windows)

```
start.bat
```

Brings up the three containers, waits for each to answer, launches the server, waits for it to
be healthy, then opens a client window. `start_another.bat` opens a second client against the
same server — handy for watching messages cross between two accounts.

### The long way

#### 1. Backing services

```bash
docker compose up -d
```

| Service | Host port | Why not the default |
|---|---|---|
| Postgres | `5432` | — |
| Valkey | **`6380`** | 6379 is very often already taken |
| MinIO | **`9100`** (console `9101`) | 9000 likewise |

Those non-standard ports are deliberate. **A Docker port clash does not fail loudly**: the
container starts *without publishing*, your app connects to whatever else is on that port, and
you get authentication errors that look like bad credentials rather than a wrong server. That
happened during development — the server spent an hour talking to a different project's MinIO.

**All three are required.** The server refuses to boot without Valkey:

```
Cannot reach Valkey at redis://localhost:6380 — is the container up?
```

That is on purpose. Valkey carries cross-node fanout, presence, rate limiting and the
scheduled-job lock; silently degrading to single-node behaviour in a multi-node deployment
split-brains, with messages reaching only half the users. Failing at boot beats failing under
load. MinIO is the opposite call — it logs a warning and boots anyway, because most requests
never touch it.

#### 2. Server

```bash
cd server
./gradlew bootRun
```

- GraphQL endpoint: `http://localhost:8080/graphql`
- WebSocket (subscriptions): `ws://localhost:8080/graphql`
- GraphiQL (dev only): `http://localhost:8080/graphiql`

Flyway applies all five migrations on boot and creates monthly partitions through 2027.

#### 3. Desktop client

```bash
cd client
./gradlew :composeApp:run
```

The Android target is **conditional on an SDK being present** — the build checks
`local.properties` (`sdk.dir`), then `ANDROID_HOME` / `ANDROID_SDK_ROOT`, then the platform's
default location. Without one it logs `No Android SDK found — building desktop only` and skips
Android rather than failing configuration. Install the SDK or set `ANDROID_HOME` and the target
reappears with no build-file edit.

Gradle wrappers are checked in; both projects build with a JDK 21 and nothing else.

#### Try it without the client

```bash
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

### Ports

Singular's MinIO runs on **9100/9101**, not the usual 9000/9001, and Valkey on **6380**, to
avoid clashing with other projects on the same machine — a Docker port clash doesn't fail
loudly: the container starts *without* publishing, your app connects to whatever else is on
that port, and you get authentication errors that look like bad credentials rather than a
wrong server.

## Feature status

Numbered against the original feature list.

| # | Feature | State |
|---|---|---|
| 1 | Private DM | ✅ |
| 2 | Group DM | 🟡 schema + channel type; no group-creation UI |
| 3 | Servers | ✅ create, channels, categories, invites, leave, delete |
| 4 | Online / Away / DND / Offline | ✅ + Invisible, with heartbeat |
| 5 | Stories (WhatsApp/Insta style) | ✅ tray, composer, viewer, overlay compositor |
| 6 | Emoji / files / audio / location | ✅ emoji picker (grid, search, recents) with a bundled Noto Color Emoji face; files/audio/location via presigned upload |
| 7 | Push notifications | 🟡 registration + mute/DND filter done; delivery needs your FCM/APNs keys |
| 8 | IP/device/agent + action logging | ✅ per-session, not per-message |
| 9 | "Hashing" | ✅ split into hash vs encrypt |
| 10 | Server roles | ✅ 128-bit bitfield, hierarchy, channel overwrites |
| 11 | Blocking + muting | ✅ separate tables, separate semantics |
| 12 | Tagging / mentions | ✅ @-autocomplete in the composer, `<@id>` highlighting, mentions inbox screen |
| 13 | Custom pfp / border / banner / about me / handle | ✅ fields, editor, and an upload path |
| 14 | Per-server nickname | ✅ |
| 15 | Blocked-but-shared collapse | ✅ both layouts |
| 16 | Custom primary + secondary colour | ✅ in Settings, with a contrast floor |
| 17 | Rich presence | ✅ volatile, with a staleness ceiling |
| 18 | Server folders | ✅ server-side; no drag-and-drop UI yet |
| 19 | Custom server icon | ✅ |
| 20 | Music on stories | 🟡 overlay model supports it; needs a licensed source |

Plus, not on the original list: QR sign-in with a 20s rotating code, session/device management,
typing indicators, two chat layouts, Enter-to-send, **emoji reactions** (chips under messages,
long-press quick-react, live counts over `reactionUpdated`), **custom emoji+text status**, and
**story stickers** (emoji overlays from the picker).

### What's left

- **Push delivery.** Registration, the mute/DND filter, and fanout are all real. Only the final
  hop is a `LoggingPushTransport` stub — Apple and Google own the only sockets to a sleeping
  phone, and that needs your credentials.
- **A licensing decision for music-on-stories.** The overlay model already carries a music
  widget, but streaming a commercial clip needs a label deal. Realistic options: user-uploaded
  audio, or metadata plus a deep link into the listener's own music app.
- **Voice recording.** Voice notes are modelled, uploaded, and rendered from precomputed peaks;
  capturing audio needs a platform recorder (`AudioRecord` on Android, `javax.sound` on desktop)
  plus Opus encoding.
- **Direct manipulation of story overlays.** The composer places a caption and the viewer draws
  every overlay type; dragging, pinching, and rotating them on the image isn't wired yet. The
  data model already carries `x`, `y`, `rot`, `scale`, so this is a gesture handler, not a redesign.

## How it's built

### Media pipeline

Uploads go **straight to storage** — bytes never pass through the application server:

```
1. createUpload      -> server mints an id + object key, returns a presigned PUT.
                        Content type and length are SIGNED INTO the URL, so the client
                        cannot upload something other than what it declared.
2. PUT to MinIO      -> direct. A 100 MB video never occupies a request thread.
3. finalizeUpload    -> server HEADs the object, checks the size matches, strips EXIF from
                        images, builds a thumbnail, and flips the row to READY.
```

Step 3 is what makes step 2 safe: until it runs, the row is only a *claim* — a client can't
move an attachment to READY by asserting it. EXIF is stripped server-side because phone cameras
embed GPS in JPEGs by default, and a client can simply choose not to strip it. Orphaned uploads
(composed, never sent) are reaped after 6 hours.

### Story overlays

Overlays are **data, not pixels**. A story stores a JSON list of positioned elements and the
client composites them over the media at view time:

```json
[{ "type": "text", "x": 0.14, "y": 0.70, "rot": -4, "scale": 1,
   "value": "back on the road", "style": "plate", "color": "#F0B232" }]
```

Coordinates are fractions of the frame, not pixels, so a story composed on a phone lands in the
same place read on desktop. Types rendered today: `text`, `sticker`/`emoji`, `mention`,
`location`, `music`. An unknown type from a newer client is skipped silently. The server never
interprets any of this — it stores and returns the JSON, so a new overlay type costs no
migration and no server deploy.

### Chat layouts

Two, switched in **Settings → Appearance**:

- **Bubbles** — WhatsApp-style. Yours right, theirs left, time inside the bubble.
- **Compact** — Discord-style. No bubbles, name and time on the first line of a run only.

Both group consecutive messages by author, breaking on a gap over 5 minutes.
**Handles never appear in the message list** — `name#0971` is for finding/adding people; it
shows in the sidebar and on profiles only.

### Permissions

A 128-bit bitfield per role, mirroring Discord's model. `PermissionEngine.kt` resolves in a
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

Step 5 is the one implementations get wrong: applying role overwrites one at a time makes the
result depend on load order, so a grant on any role correctly beats a deny on another instead of
whichever was applied last. Two escalation guards on top: you cannot manage a role at or above
your own highest, and you cannot grant a permission you don't hold yourself.

### QR sign-in

Scan a code on desktop with a phone you're already signed in on. The code rotates every 20s.

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

Creation returns a **qrToken** (public, in the QR, rotates every 20s) and a **pollSecret**
(private, never displayed, never rotates) — splitting them lets the visible half rotate freely
while the channel that eventually carries the tokens stays put. Tokens are delivered **only** on
the pollSecret-authenticated subscription, never to whoever did the scanning.

Rotation defends against replay of a captured code (screen share, photo, shoulder surf) — it
does **not** defend against QRLJacking (an attacker shows you *their* QR and gets you to scan
it). The defence there is the approval screen naming the requesting device/platform/IP plus an
explicit tap; never auto-confirm that step.

### Sessions

`sessions` lists one row per **rotation family**, not per raw session row — refresh rotation
mints a new row every 15 minutes, so listing raw rows would show the same laptop dozens of times
a day. Rows are tagged with how the session started (`PASSWORD`, `QR_CODE`, `REFRESH`), because
an unrecognised QR sign-in is exactly the shape a successful QRLJacking attack leaves behind.
`revokeSession` takes a family id and revokes the whole chain, so a rotated descendant can't
survive its parent.

## Verification

Everything below was run against a live server and a real PostgreSQL 17, not asserted:

```bash
cd server && ./gradlew build          # unit tests
docker compose up -d && ./gradlew bootRun
pwsh scripts/e2e-api.ps1              # checks over the real GraphQL API
pwsh scripts/e2e-qr-ws.ps1            # checks over a real graphql-ws socket
```

`e2e-api.ps1` covers registration and discriminator allocation, refresh-token rotation and reuse
detection, DM idempotency, message paging, authorisation boundaries, the session list, and the
whole QR state machine. `e2e-qr-ws.ps1` covers what HTTP can't: the anonymous socket handshake,
`SCANNED`/`APPROVED` push, token delivery on the poll-secret channel, and negative cases (wrong
poll secret, anonymous subscribe to messages).

## Decisions worth not re-litigating

- **Snowflake IDs, serialised as strings.** JavaScript silently corrupts integers above 2^53;
  every ID crosses the wire quoted.
- **`messages` is partitioned from commit one.** Monthly `RANGE` on `created_at`. Repartitioning
  a live table with billions of rows is a migration nobody enjoys; the cost today is zero.
- **Idempotency lives in its own table.** Postgres requires a partitioned table's unique index to
  include the partition key, which would defeat nonce dedup — so nonces get a small unpartitioned
  table, reaped after 24h.
- **Blocks and mutes are separate tables.** Blocks are visibility rules, mutes are notification
  rules — similar-looking, but merging them is a trap.
- **Access tokens are stateless and short-lived (15 min); refresh tokens are opaque, hashed, and
  rotate on every use.** A stolen refresh token is detectable — reuse of a rotated token revokes
  the whole session family.
- **Anonymous WebSockets are allowed, narrowly.** A device waiting on a QR sign-in has no bearer
  token yet, so `loginRequestUpdated` works unauthenticated via the poll secret. Every other
  subscription calls `requirePrincipal()` and fails.
- **`messages` pagination sends a two-sided `created_at` window.** A floor alone still scans every
  future partition — measured on Postgres 17, one-sided planned 17 index scans where two-sided
  planned 4.

## Bugs found and fixed by the E2E suite

| Bug | Symptom |
|---|---|
| `revokeFamily` ran inside the `@Transactional` method that then threw | The throw rolled the revocation back, so **detecting a stolen refresh token left every stolen session live** — the opposite of intent. Fixed with `noRollbackFor`. |
| `AuditLog.record` joined the caller's transaction | Every `LOGIN_FAILED` and `TOKEN_REUSE_DETECTED` row was rolled back by the throw that followed it — the security events most worth recording were vanishing. Fixed with `REQUIRES_NEW`. |
| Bare `? IS NULL` bind on the message cursor | `could not determine data type of parameter $4` on the first page load of any channel. Fixed with an explicit `CAST(:before AS bigint)`. |
| One-sided partition predicate | Pruned older partitions but scanned every future one: 17 index scans where 4 suffice. Fixed with a two-sided `created_at` window. |
