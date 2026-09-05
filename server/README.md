# Singular — server

Kotlin + Spring Boot + Spring for GraphQL. Owns every rule in the system: authentication,
permissions, message durability, media verification, and the audit trail.

Builds with a **JDK 21 and nothing else** — no Android SDK, no Node, no codegen step. That
independence is deliberate: a missing Android SDK must never be able to break the backend.

```bash
./gradlew build      # compile + unit tests
./gradlew bootRun    # run against docker-compose services
```

- GraphQL: `POST http://localhost:8080/graphql`
- Subscriptions: `ws://localhost:8080/graphql` (`graphql-transport-ws`)
- GraphiQL (dev only): http://localhost:8080/graphiql
- Health: http://localhost:8080/actuator/health

Requires **Postgres 5432, Valkey 6380, MinIO 9100**. The server refuses to boot without Valkey
— see [Why Valkey is mandatory](#why-valkey-is-mandatory).

---

## Package map

Each package owns one concern. The API layer holds no rules; it translates GraphQL into service
calls and back.

| Package | What lives there |
|---|---|
| `api/` | GraphQL controllers only — argument parsing, field resolvers, batch loaders. No business logic. |
| `auth/` | Registration, sign-in, refresh-token rotation, QR sign-in. |
| `security/` | Argon2id, HMAC access tokens, blind indexes, the request interceptor. |
| `user/` | Accounts and the `USERNAME#0971` handle allocator. |
| `channel/` | DMs, group DMs, and the single visibility gate for both channel kinds. |
| `message/` | Sending, pagination, typing indicators, mentions, the reaper. |
| `guild/` | Servers, roles, and the permission engine. |
| `social/` | Blocks, mutes, per-user settings, chosen status. |
| `presence/` | Effective presence and rich presence. Both volatile. |
| `media/` | Presigned uploads, EXIF stripping, thumbnails, S3-compatible storage. |
| `story/` | Ephemeral stories, overlays, audience, viewer lists. |
| `push/` | Device token registration and the notify/suppress decision. |
| `event/` | `FanoutBus` — cross-node event delivery over Valkey pub/sub. |
| `ratelimit/` | Token-bucket limiter backed by Valkey. |
| `schedule/` | `DistributedLock`, so scheduled jobs run on one node rather than all of them. |
| `audit/` | The action trail. Writes in its own transaction. |
| `core/` | Snowflake IDs and the domain error hierarchy. |
| `domain/` | Plain data classes shared across packages. |
| `config/` | Typed properties, GraphQL scalars, Valkey wiring. |

---

## Decisions worth not re-litigating

### Snowflake IDs, serialised as strings

64-bit, time-sortable, minted by the app. Sorting by id **is** sorting by time, so one index
serves both pagination and chronology, and every id carries its own creation timestamp.

They cross the wire **quoted**. JavaScript silently corrupts integers above 2^53 — `JSON.parse`
rounds rather than failing, so an unquoted id arrives subtly wrong and every lookup for it
misses. Discord quotes every id for exactly this reason.

`singular.node-id` must be unique per running instance. Two nodes sharing one mint colliding
ids: duplicate-key errors if you're lucky, silently overwritten rows if you're not.

### `messages` is partitioned from the first commit

Monthly `RANGE` on `created_at`. Repartitioning a live table holding billions of rows is a
migration nobody enjoys; doing it now costs nothing.

Pagination sends a **two-sided** `created_at` window. A floor alone prunes older partitions but
still scans every future one — measured on Postgres 17 across 36 partitions, one-sided planned
17 index scans where two-sided planned 4.

There is deliberately **no DEFAULT partition**. A DEFAULT that has absorbed rows blocks
`CREATE TABLE ... PARTITION OF` for any overlapping range, turning routine maintenance into an
outage. `MessageReaper` creates partitions six months ahead instead.

### Idempotency lives in its own table

Postgres requires a unique index on a partitioned table to include the partition key, which
would make `(channel, author, nonce, created_at)` unique — useless for dedup, since a retry
carries a different timestamp. Nonces get a small unpartitioned table, reaped after 24h.

### Hash vs encrypt

Two different tools, and conflating them is the mistake this codebase is built to avoid.

**Hash** what you only ever *compare*: passwords (Argon2id, 19 MiB / t=2 / p=1), session tokens
(SHA-256 — already high-entropy, no KDF needed), recovery codes.

**Encrypt** what you must *read back*: message bodies, email, files. Hash a message and it is
gone forever.

A failed sign-in still verifies against a real dummy hash, so "no such account" and "wrong
password" take the same time. Skipping the hash on a miss is a textbook enumeration side channel.

### Refresh tokens rotate; reuse is treated as theft

Every refresh mints a new token and marks its predecessor superseded. Presenting an
already-superseded token means it was captured and replayed — and we cannot tell whether the
replay came from a thief or a retrying client, so the safe reading is theft and the whole
rotation family is revoked.

That branch revokes *and then throws*, so `refresh` is annotated `noRollbackFor` — without it
the throw rolls the revocation back and a detected theft leaves every stolen session live.

### Audit writes run in their own transaction

`REQUIRES_NEW`, deliberately. Half the events worth auditing happen on paths that then fail: a
rejected login records `LOGIN_FAILED` and throws; a replayed token records
`TOKEN_REUSE_DETECTED` and throws. Joining the caller's transaction means the throw rolls the
audit row back too, and the security events you most want a record of are exactly the ones that
silently vanish.

### Per-session forensics, not per-message

One `connection_sessions` row per *connection* holds IP, geo, user agent and device id. Each
message carries an 8-byte `session_id`. Same forensic answers, ~60–75× less data — a join
instead of a duplicated row.

**MAC addresses are not collected**, because they cannot be. They don't survive the first router
hop, browsers have no API for them, Android 10+ returns a fixed dummy, and iOS blocks them. A
self-reported MAC is trivially spoofed and worthless as evidence. A keystore-backed install UUID
is what actually gives device continuity.

### Why Valkey is mandatory

`ValkeyConfig` fails startup if Valkey is unreachable. That is on purpose: `FanoutBus`,
presence, rate limiting and the scheduled-job lock all run through it, and silently degrading to
single-node behaviour in a multi-node deployment split-brains — two nodes each believing they
have the whole picture, with messages reaching only half the users.

Failing loudly at boot beats failing subtly under load.

Storage is the opposite call: MinIO being down logs a warning and boots anyway, because most
requests never touch it and refusing to start would make every developer's morning worse.

---

## The permission engine

`guild/PermissionEngine.kt`. Resolution order is not stylistic — it **is** the security model:

```
1. owner                 -> everything
2. base = OR of every role held (@everyone included)
3. ADMINISTRATOR         -> everything, bypassing every overwrite below
4. @everyone overwrite   -> deny, then allow
5. role overwrites       -> UNIONED first, then applied as one
6. member overwrite      -> deny, then allow. Final word.
7. no VIEW_CHANNEL       -> nothing
```

Step 5 is the one implementations get wrong. Applying role overwrites one at a time makes the
result depend on the order roles happen to load in, which produces permission bugs nobody can
reproduce. Unioned first, a grant on *any* role correctly beats a deny on another.

Permissions are **128-bit** (`bit(128)`, `BigInteger` in Kotlin, decimal strings on the wire).
Discord shipped 53-bit-safe integers, outgrew them, moved to 64, outgrew that too, and had to
re-serialise permissions as strings in a live public API.

Two escalation guards: you cannot manage a role at or above your own highest, and you cannot
grant a permission you don't hold yourself. Without the second, `MANAGE_ROLES` is a slow path to
`ADMINISTRATOR`.

`@everyone` has `role.id == guild.id`, like Discord. That removes a special case from every
lookup — the engine resolves an ordinary row rather than branching on "is this the default role".

---

## Media pipeline

Bytes never pass through this server.

```
1. createUpload    -> mints a row + presigned PUT. Content type and length are SIGNED INTO
                      the URL, so the client cannot upload something other than declared.
2. PUT to MinIO    -> direct.
3. finalizeUpload  -> HEADs the object, checks size, strips EXIF, builds a thumbnail, READY.
```

Step 3 is what makes step 2 safe. Until it runs the row is a *claim*.

**EXIF stripping is server-side and not optional.** Phone cameras embed GPS in JPEGs by default,
so an unprocessed photo posted to a channel publishes where it was taken — one taken at home
publishes an address. A client can simply not strip it, and the client that doesn't is the one
leaking.

---

## Migrations

Flyway, `src/main/resources/db/migration/`. Applied on boot, in order, never edited once shipped.

| | |
|---|---|
| `V1__baseline` | users, handles, sessions, channels, partitioned messages, audit, connection sessions |
| `V2__qr_login` | login requests, session platform/origin |
| `V3__social` | blocks, mutes, user settings, chosen status |
| `V4__guilds` | servers, roles, members, overwrites, invites, folders, mentions, profile fields |
| `V5__media` | attachments, message location, stories, story views, push tokens |

---

## Configuration

Everything is env-overridable — see `src/main/resources/application.yml`.

| Variable | Default | Notes |
|---|---|---|
| `SINGULAR_NODE_ID` | `1` | Snowflake worker id, 0–1023. **Unique per instance.** |
| `SINGULAR_TOKEN_SECRET` | dev value | HMAC key for access tokens. **Replace before deploying.** |
| `SINGULAR_PEPPER` | dev value | Blind-index pepper. Changing it orphans every email lookup — treat as permanent once you have users. |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | local docker | |
| `VALKEY_URI` | `redis://localhost:6380` | |
| `MINIO_ENDPOINT` | `http://localhost:9100` | Not 9000 — see the root README on ports. |

## Tests

```bash
./gradlew test
```

Unit tests cover the two pieces where a subtle bug is silent: the snowflake generator
(monotonicity, sequence rollover under contention, clock-reversal handling) and the discriminator
allocator (random draw, crowded-name enumeration, quarantine, rename behaviour).

End-to-end coverage lives in [`../scripts/`](../scripts) and runs against a live server.
