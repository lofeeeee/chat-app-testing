-- ============================================================================
-- Singular — baseline schema
--
-- Conventions:
--   * Every entity id is a 64-bit snowflake, minted by the application, stored as bigint.
--     Sorting by id IS sorting by creation time, so pagination indexes are free.
--   * Anything hashed is bytea (raw digest, 32 bytes) — not hex text, which doubles the size.
--   * Anything encrypted gets a _enc suffix and stays NULL until phase 5 wires up envelope keys.
--   * Soft deletes use deleted_at; hard deletes are reserved for GDPR erasure.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Users and handles
-- ----------------------------------------------------------------------------

CREATE TABLE users (
    id              bigint      PRIMARY KEY,
    username        text        NOT NULL,
    discriminator   smallint    NOT NULL,
    display_name    text,

    -- Encrypted for display, blind-indexed for lookup. Until phase 5 email_enc holds
    -- plaintext bytes; the column shape doesn't change when encryption lands.
    email_enc       bytea       NOT NULL,
    email_bidx      bytea       NOT NULL,

    password_hash   text        NOT NULL,

    avatar_key      text,
    banner_key      text,
    bio             text,
    accent_color    integer,

    flags           bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,

    CONSTRAINT users_discriminator_range CHECK (discriminator BETWEEN 1 AND 9999),
    CONSTRAINT users_username_format     CHECK (username ~ '^[A-Za-z0-9_.]{2,32}$'),
    CONSTRAINT users_bio_length          CHECK (bio IS NULL OR length(bio) <= 512)
);

COMMENT ON COLUMN users.discriminator IS
    'Legacy Discord-style #0971. Uniqueness is on the PAIR (lower(username), discriminator), '
    'not on username alone — up to 9999 users may share a name. Reallocated randomly on rename.';

-- The whole trick: uniqueness on the pair.
CREATE UNIQUE INDEX ux_users_handle     ON users (lower(username), discriminator);
CREATE UNIQUE INDEX ux_users_email_bidx ON users (email_bidx);
CREATE        INDEX ix_users_created    ON users (id DESC);

-- Released handles are quarantined so nobody can grab a freed (name, number) the instant its
-- owner renames — the impersonation vector that eventually killed the scheme at Discord.
CREATE TABLE handle_quarantine (
    username_lower  text        NOT NULL,
    discriminator   smallint    NOT NULL,
    released_by     bigint      NOT NULL,
    released_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (username_lower, discriminator)
);
CREATE INDEX ix_handle_quarantine_released ON handle_quarantine (released_at);

-- ----------------------------------------------------------------------------
-- Auth sessions (refresh tokens)
-- ----------------------------------------------------------------------------

CREATE TABLE auth_sessions (
    id                  bigint      PRIMARY KEY,
    user_id             bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- SHA-256 of the opaque refresh token. Tokens are already high-entropy, so no KDF.
    refresh_token_hash  bytea       NOT NULL,

    -- Rotation chain: every refresh mints a new session row pointing at its predecessor.
    -- Presenting an already-rotated token means the token leaked -> revoke the whole family.
    family_id           bigint      NOT NULL,
    superseded_by       bigint,

    device_id           uuid        NOT NULL,
    user_agent          text,
    ip                  inet,

    created_at          timestamptz NOT NULL DEFAULT now(),
    last_seen_at        timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    revoked_at          timestamptz
);

CREATE UNIQUE INDEX ux_auth_sessions_token  ON auth_sessions (refresh_token_hash);
CREATE        INDEX ix_auth_sessions_user   ON auth_sessions (user_id) WHERE revoked_at IS NULL;
CREATE        INDEX ix_auth_sessions_family ON auth_sessions (family_id);
CREATE        INDEX ix_auth_sessions_expiry ON auth_sessions (expires_at) WHERE revoked_at IS NULL;

-- ----------------------------------------------------------------------------
-- Channels
--
-- A DM is just a 2-member channel with no guild. Guild channels arrive in phase 3 and
-- reuse this table unchanged — guild_id stops being NULL, nothing else moves.
-- ----------------------------------------------------------------------------

CREATE TABLE channels (
    id              bigint      PRIMARY KEY,
    guild_id        bigint,                      -- NULL for DM / group DM
    type            smallint    NOT NULL,        -- 0 DM, 1 GROUP_DM, 2 GUILD_TEXT
    name            text,                        -- NULL for 1:1 DMs
    icon_key        text,
    owner_id        bigint      REFERENCES users(id) ON DELETE SET NULL,
    last_message_id bigint,
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz,

    CONSTRAINT channels_type_valid CHECK (type IN (0, 1, 2)),
    CONSTRAINT channels_dm_unnamed CHECK (type <> 0 OR name IS NULL)
);
CREATE INDEX ix_channels_guild ON channels (guild_id) WHERE guild_id IS NOT NULL;

CREATE TABLE channel_members (
    channel_id           bigint      NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id              bigint      NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    joined_at            timestamptz NOT NULL DEFAULT now(),
    last_read_message_id bigint,
    -- Mute is a notification rule and lives here. Blocking is a visibility rule and gets its
    -- own table in phase 2. They look alike; merging them is a trap.
    muted_until          timestamptz,
    notification_level   smallint    NOT NULL DEFAULT 0,  -- 0 all, 1 mentions, 2 none
    PRIMARY KEY (channel_id, user_id)
);
CREATE INDEX ix_channel_members_user ON channel_members (user_id);

-- Get-or-create semantics for 1:1 DMs. The pair is stored sorted so (a,b) and (b,a) collide.
CREATE TABLE dm_pairs (
    low_user_id   bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    high_user_id  bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel_id    bigint NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    PRIMARY KEY (low_user_id, high_user_id),
    CONSTRAINT dm_pairs_ordered CHECK (low_user_id < high_user_id)
);
CREATE INDEX ix_dm_pairs_channel ON dm_pairs (channel_id);

-- ----------------------------------------------------------------------------
-- Messages — partitioned from commit one.
--
-- Repartitioning a live table holding billions of rows is a migration nobody enjoys.
-- Doing it now costs nothing.
-- ----------------------------------------------------------------------------

CREATE TABLE messages (
    id          bigint      NOT NULL,
    channel_id  bigint      NOT NULL,
    author_id   bigint      NOT NULL,

    -- Which connection sent it. This 8-byte FK replaces ~300 bytes of per-message
    -- IP/geo/agent/device forensics, at no loss of detail — it's a join now.
    session_id  bigint,

    content     text,
    content_enc bytea,                            -- phase 5; content moves here
    type        smallint    NOT NULL DEFAULT 0,   -- 0 default, 1 join, 2 leave, 3 pin
    flags       bigint      NOT NULL DEFAULT 0,
    reply_to_id bigint,

    created_at  timestamptz NOT NULL,
    edited_at   timestamptz,
    deleted_at  timestamptz,

    PRIMARY KEY (id, created_at),                 -- partition key must be in the PK
    CONSTRAINT messages_has_body CHECK (content IS NOT NULL OR content_enc IS NOT NULL OR type <> 0)
) PARTITION BY RANGE (created_at);

-- Reading a channel is always "newest first, paginate backwards by id".
CREATE INDEX ix_messages_channel ON messages (channel_id, id DESC);
CREATE INDEX ix_messages_author  ON messages (author_id, id DESC);

-- Idempotent send.
--
-- A unique index on a partitioned table must include every partition key column, which would
-- make (channel, author, nonce, created_at) unique — useless for dedup, since a retry has a
-- different timestamp. So nonces live in their own unpartitioned table and get reaped daily.
CREATE TABLE message_nonces (
    channel_id bigint      NOT NULL,
    author_id  bigint      NOT NULL,
    nonce      text        NOT NULL,
    message_id bigint      NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, author_id, nonce)
);
CREATE INDEX ix_message_nonces_reap ON message_nonces (created_at);

-- ----------------------------------------------------------------------------
-- Connection sessions and audit log
--
-- The forensic story: one row per CONNECTION, not per message.
-- ----------------------------------------------------------------------------

-- Dictionary table — a few thousand distinct UA strings cover millions of sessions.
CREATE TABLE user_agents (
    id   integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    hash bytea   NOT NULL UNIQUE,
    raw  text    NOT NULL
);

CREATE TABLE connection_sessions (
    id           bigint      PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- Keystore-backed install UUID. NOT a MAC address: MAC addresses do not survive the first
    -- router hop, browsers have no API for them, Android 10+ returns a fixed dummy value and
    -- iOS blocks them outright. A self-reported MAC is spoofable and worthless as evidence.
    device_id    uuid        NOT NULL,

    ip           inet        NOT NULL,           -- 7 bytes for v4, 19 for v6
    ip_hmac      bytea       NOT NULL,           -- match "same IP" without decrypting
    asn          integer,
    ua_id        integer     REFERENCES user_agents(id),

    geo_country  char(2),                        -- from a local GeoLite2 .mmdb, never an API
    geo_region   text,
    geo_lat      double precision,               -- only when the user shared precise location
    geo_lon      double precision,

    started_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    ended_at     timestamptz
);
CREATE INDEX ix_conn_sessions_user    ON connection_sessions (user_id, started_at DESC);
CREATE INDEX ix_conn_sessions_ip_hmac ON connection_sessions (ip_hmac);
CREATE INDEX ix_conn_sessions_device  ON connection_sessions (device_id);

CREATE TABLE audit_events (
    id          bigint      NOT NULL,
    actor_id    bigint      NOT NULL,
    session_id  bigint,
    action      smallint    NOT NULL,
    target_type smallint,
    target_id   bigint,
    changes     jsonb,                            -- {"field": {"old": …, "new": …}}
    occurred_at timestamptz NOT NULL,
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_actor  ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_target ON audit_events (target_type, target_id, occurred_at DESC);

COMMENT ON COLUMN audit_events.action IS
    '1 REGISTER, 2 LOGIN, 3 LOGIN_FAILED, 4 LOGOUT, 5 TOKEN_REFRESH, 6 TOKEN_REUSE_DETECTED, '
    '7 PASSWORD_CHANGE, 8 USERNAME_CHANGE, 9 AVATAR_CHANGE, 10 PROFILE_CHANGE, '
    '20 CHANNEL_CREATE, 21 CHANNEL_DELETE, 22 MESSAGE_DELETE';

-- ----------------------------------------------------------------------------
-- Partition management
-- ----------------------------------------------------------------------------

CREATE FUNCTION ensure_month_partition(parent text, month date) RETURNS void AS $$
DECLARE
    start_ts date := date_trunc('month', month)::date;
    end_ts   date := (date_trunc('month', month) + interval '1 month')::date;
    part     text := format('%s_p%s', parent, to_char(start_ts, 'YYYYMM'));
BEGIN
    IF to_regclass(part) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            part, parent, start_ts, end_ts
        );
    END IF;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION ensure_month_partition IS
    'Idempotent. Call from a scheduled job a few months ahead of time. There is deliberately no '
    'DEFAULT partition: a DEFAULT that has absorbed rows blocks CREATE TABLE ... PARTITION OF for '
    'any overlapping range, which turns a routine maintenance task into an outage.';

-- Seed three years of partitions so nothing can fall over unattended in development.
DO $$
DECLARE m date := date '2025-01-01';
BEGIN
    WHILE m < date '2028-01-01' LOOP
        PERFORM ensure_month_partition('messages', m);
        PERFORM ensure_month_partition('audit_events', m);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;
