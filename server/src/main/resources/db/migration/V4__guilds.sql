-- ============================================================================
-- Servers ("guilds"), roles, permissions, nicknames, invites, folders, mentions.
--
-- This is the migration everything else was waiting on: per-server nicknames (14),
-- server folders (18) and server icons (19) are all attributes of a server, and none of
-- them can exist until servers do.
-- ============================================================================

CREATE TABLE guilds (
    id                bigint      PRIMARY KEY,
    name              text        NOT NULL,
    icon_key          text,                       -- feature 19
    banner_key        text,
    description       text,
    owner_id          bigint      NOT NULL REFERENCES users(id),
    system_channel_id bigint,

    -- Copied from Discord because it costs one boolean and is the highest-value defence
    -- against a compromised moderator account emptying a server.
    require_2fa_for_moderation boolean NOT NULL DEFAULT false,

    created_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,

    CONSTRAINT guilds_name_length CHECK (length(name) BETWEEN 2 AND 100)
);
CREATE INDEX ix_guilds_owner ON guilds (owner_id);

-- ----------------------------------------------------------------------------
-- Channels grow up: categories, ordering, topics, slowmode
-- ----------------------------------------------------------------------------

ALTER TABLE channels
    ADD COLUMN parent_id        bigint REFERENCES channels(id) ON DELETE SET NULL,
    ADD COLUMN position         integer NOT NULL DEFAULT 0,
    ADD COLUMN topic            text,
    ADD COLUMN nsfw             boolean NOT NULL DEFAULT false,
    ADD COLUMN slowmode_seconds integer NOT NULL DEFAULT 0;

-- 3 = GUILD_CATEGORY, 4 = GUILD_VOICE join the original three.
ALTER TABLE channels DROP CONSTRAINT channels_type_valid;
ALTER TABLE channels ADD CONSTRAINT channels_type_valid CHECK (type IN (0, 1, 2, 3, 4));

ALTER TABLE channels
    ADD CONSTRAINT channels_guild_fk FOREIGN KEY (guild_id)
        REFERENCES guilds(id) ON DELETE CASCADE;

CREATE INDEX ix_channels_guild_position ON channels (guild_id, position, id)
    WHERE guild_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Roles
--
-- `permissions` is bit(128), not bigint. Discord shipped a 53-bit-safe integer, blew past
-- it, moved to 64, blew past that too, and had to re-serialise permissions as strings in
-- a live API. 128 bits costs nothing now and leaves ~100 spare flags.
--
-- Postgres has native &, | and ~ on bit strings, so permission maths stays in the database
-- when it needs to be, and BigInteger handles it in Kotlin when it doesn't.
--
-- The @everyone role has id = guild_id and position 0, exactly like Discord. Making it a
-- real row rather than a special case means the resolution algorithm has no branch for it.
-- ----------------------------------------------------------------------------

CREATE TABLE roles (
    id          bigint      PRIMARY KEY,
    guild_id    bigint      NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,
    name        text        NOT NULL,
    color       integer,
    icon_key    text,

    -- Hierarchy. You may only edit, assign or moderate BELOW your own highest role; this
    -- is the whole defence against privilege escalation inside a server.
    position    integer     NOT NULL,

    permissions bit(128)    NOT NULL DEFAULT repeat('0', 128)::bit(128),
    hoist       boolean     NOT NULL DEFAULT false,  -- list separately in the member list
    mentionable boolean     NOT NULL DEFAULT false,
    managed_by  bigint,                              -- set when owned by an integration
    created_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT roles_name_length CHECK (length(name) BETWEEN 1 AND 100)
);
CREATE INDEX ix_roles_guild ON roles (guild_id, position DESC, id DESC);

-- ----------------------------------------------------------------------------
-- Membership
-- ----------------------------------------------------------------------------

CREATE TABLE guild_members (
    guild_id        bigint      NOT NULL REFERENCES guilds(id) ON DELETE CASCADE,
    user_id         bigint      NOT NULL REFERENCES users(id)  ON DELETE CASCADE,

    nickname        text,                       -- feature 14
    avatar_key      text,                       -- per-server avatar

    joined_at       timestamptz NOT NULL DEFAULT now(),
    timed_out_until timestamptz,                -- MODERATE_MEMBERS

    PRIMARY KEY (guild_id, user_id),
    CONSTRAINT guild_members_nickname_length
        CHECK (nickname IS NULL OR length(nickname) BETWEEN 1 AND 32)
);
CREATE INDEX ix_guild_members_user ON guild_members (user_id);

CREATE TABLE member_roles (
    guild_id bigint NOT NULL,
    user_id  bigint NOT NULL,
    role_id  bigint NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (guild_id, user_id, role_id),
    FOREIGN KEY (guild_id, user_id)
        REFERENCES guild_members(guild_id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_member_roles_role ON member_roles (role_id);

-- ----------------------------------------------------------------------------
-- Channel overwrites
--
-- Per-channel allow/deny pairs targeting either a role or one specific member. The order
-- they are applied in is the security-critical part and lives in PermissionEngine.kt.
-- ----------------------------------------------------------------------------

CREATE TABLE channel_overwrites (
    channel_id  bigint   NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    target_id   bigint   NOT NULL,               -- a role id OR a user id
    target_type smallint NOT NULL,               -- 0 = role, 1 = member
    allow       bit(128) NOT NULL DEFAULT repeat('0', 128)::bit(128),
    deny        bit(128) NOT NULL DEFAULT repeat('0', 128)::bit(128),
    PRIMARY KEY (channel_id, target_id),
    CONSTRAINT overwrites_target_type_valid CHECK (target_type IN (0, 1))
);

-- ----------------------------------------------------------------------------
-- Invites
-- ----------------------------------------------------------------------------

CREATE TABLE guild_invites (
    code       text        PRIMARY KEY,
    guild_id   bigint      NOT NULL REFERENCES guilds(id)   ON DELETE CASCADE,
    channel_id bigint               REFERENCES channels(id) ON DELETE SET NULL,
    inviter_id bigint               REFERENCES users(id)    ON DELETE SET NULL,
    uses       integer     NOT NULL DEFAULT 0,
    max_uses   integer,                          -- NULL = unlimited
    expires_at timestamptz,                      -- NULL = never
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_guild_invites_guild ON guild_invites (guild_id);

-- ----------------------------------------------------------------------------
-- Server folders (feature 18)
--
-- Purely a per-user view preference — folders are not shared, and two people in the same
-- servers can organise them completely differently. JSONB because the shape is the
-- client's business and adding a field to it should never cost a migration.
-- ----------------------------------------------------------------------------

CREATE TABLE guild_folders (
    user_id     bigint      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    -- [{ "id": "...", "name": "Games", "color": 5793266, "guildIds": ["1","2"] }]
    folders     jsonb       NOT NULL DEFAULT '[]'::jsonb,
    -- Guilds not in any folder, in the order the user dragged them into.
    guild_order jsonb       NOT NULL DEFAULT '[]'::jsonb,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ----------------------------------------------------------------------------
-- Mentions (feature 12)
--
-- The message body keeps the mention inline as `<@id>` / `<@&roleId>` / `<#channelId>`,
-- which is what actually renders. This table exists for the two things a scan of the body
-- can't do cheaply: fanning out notifications when the message is sent, and answering
-- "show me everything that mentions me" without a full-text scan of every channel.
-- ----------------------------------------------------------------------------

CREATE TABLE message_mentions (
    message_id  bigint      NOT NULL,
    channel_id  bigint      NOT NULL,
    guild_id    bigint,
    target_type smallint    NOT NULL,   -- 0 user, 1 role, 2 @everyone, 3 @here
    -- 0 for @everyone and @here, which have no target of their own.
    target_id   bigint      NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (message_id, target_type, target_id)
);

-- The mentions inbox: "everything aimed at me, newest first".
CREATE INDEX ix_message_mentions_target
    ON message_mentions (target_type, target_id, message_id DESC);
CREATE INDEX ix_message_mentions_channel ON message_mentions (channel_id, message_id DESC);

-- ----------------------------------------------------------------------------
-- Profile customisation (feature 13)
-- ----------------------------------------------------------------------------

ALTER TABLE users
    ADD COLUMN border_key text,        -- decorative avatar frame
    ADD COLUMN pronouns   text,
    ADD CONSTRAINT users_pronouns_length
        CHECK (pronouns IS NULL OR length(pronouns) <= 40);
