-- ============================================================================
-- Blocking, muting, and per-user client settings.
--
-- Blocks and mutes are separate tables on purpose. They look alike and get merged
-- constantly, but they answer different questions:
--
--   BLOCK  is a VISIBILITY rule. It hides content, is one-directional, and follows the
--          person across every channel you share.
--   MUTE   is a NOTIFICATION rule. Content stays fully visible; only the alert stops.
--          It is scoped to one thing (a channel, later a guild), not to a person everywhere.
--
-- Merging them means "mute this noisy group chat" and "I never want to see this person
-- again" end up sharing a code path, and one of the two inevitably gets the other's
-- semantics.
-- ============================================================================

CREATE TABLE blocks (
    blocker_id bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT blocks_not_self CHECK (blocker_id <> blocked_id)
);

-- "Who have I blocked" drives message filtering on every channel read, so it is the hot
-- direction and gets the PK. The reverse ("who blocked me") is only needed to stop DM
-- delivery, hence the second index.
CREATE INDEX ix_blocks_blocked ON blocks (blocked_id);

COMMENT ON TABLE blocks IS
    'One-directional. In a DM a block stops delivery outright; in a shared server or group DM '
    'the message is still delivered but flagged, so the client can collapse it to "Blocked '
    'message - show" and reveal it with no round trip. Revealing one message never unblocks.';

-- Muting a *person* everywhere, as opposed to muting one channel (which lives on
-- channel_members.muted_until).
CREATE TABLE user_mutes (
    user_id     bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted_id    bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    muted_until timestamptz,          -- NULL = indefinitely
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, muted_id),
    CONSTRAINT user_mutes_not_self CHECK (user_id <> muted_id)
);

-- ----------------------------------------------------------------------------
-- Per-user client settings
--
-- JSONB rather than a column per preference: these change shape constantly as the client
-- grows, and none of them are ever queried by the server — it stores and returns them.
-- Adding a setting should not cost a migration.
-- ----------------------------------------------------------------------------

CREATE TABLE user_settings (
    user_id    bigint      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,

    -- 0 = bubbles (WhatsApp-style), 1 = compact (Discord-style)
    chat_layout smallint   NOT NULL DEFAULT 0,

    theme_primary   integer,          -- 0xRRGGBB, NULL = app default
    theme_secondary integer,
    theme_dark      boolean,          -- NULL = follow the OS

    -- Server folders (feature 18), notification levels, and anything else the client owns.
    extras     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT user_settings_layout_valid CHECK (chat_layout IN (0, 1))
);

-- ----------------------------------------------------------------------------
-- Presence
--
-- Live presence is volatile and belongs in Valkey — it changes several times a minute per
-- user and is disposable on restart. What IS worth persisting is the user's *chosen* status,
-- because "I set myself to Do Not Disturb" must survive a reconnect. The distinction:
--
--   desired_status  what the user picked          -> durable, here
--   effective status  what others actually see    -> derived from connections, in memory
--
-- Someone set to DND who closes every client shows as Offline, but is still DND when they
-- come back. Storing only one of the two loses that.
-- ----------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN desired_status    smallint NOT NULL DEFAULT 0,  -- 0 ONLINE, 1 AWAY, 2 DND, 3 INVISIBLE
    ADD COLUMN custom_status     text,
    ADD COLUMN custom_status_emoji text,
    ADD COLUMN custom_status_expires_at timestamptz,
    ADD CONSTRAINT users_desired_status_valid CHECK (desired_status BETWEEN 0 AND 3),
    ADD CONSTRAINT users_custom_status_length CHECK (custom_status IS NULL OR length(custom_status) <= 128);

COMMENT ON COLUMN users.desired_status IS
    'What the user chose. INVISIBLE is stored here but never leaves the server as itself - '
    'it is reported to other people as OFFLINE, which is the entire point of it.';
