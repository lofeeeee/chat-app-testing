-- ============================================================================
-- Media: attachments, voice notes, location, stories.
--
-- The pipeline is upload-direct-to-storage:
--
--   1. client asks for an upload slot   -> server mints an id + object key, returns a
--                                          presigned PUT and records the row as PENDING
--   2. client PUTs the bytes to MinIO   -> the server never touches the payload
--   3. client finalises                 -> server HEADs the object to confirm it exists and
--                                          matches the declared size, strips EXIF, makes a
--                                          thumbnail, and flips the row to READY
--
-- Step 3 is what makes step 2 safe. A row only becomes usable once the server has verified
-- the object itself; without it, a client could claim an upload it never performed, or
-- declare 2 KB and store 2 GB.
-- ============================================================================

CREATE TABLE attachments (
    id            bigint      PRIMARY KEY,
    uploader_id   bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- NULL until the attachment is actually sent. An upload that is never attached to
    -- anything is garbage and gets reaped.
    message_id    bigint,
    channel_id    bigint,

    object_key    text        NOT NULL UNIQUE,
    filename      text        NOT NULL,
    content_type  text        NOT NULL,
    size_bytes    bigint      NOT NULL,

    kind          smallint    NOT NULL,   -- 0 file, 1 image, 2 video, 3 audio, 4 voice note
    status        smallint    NOT NULL DEFAULT 0,   -- 0 pending, 1 ready, 2 failed

    -- Images and video
    width         integer,
    height        integer,
    thumbnail_key text,
    blurhash      text,

    -- Audio and voice notes
    duration_ms   integer,
    -- Precomputed peaks, 0-100, so the client draws a waveform without decoding the file.
    waveform      smallint[],

    created_at    timestamptz NOT NULL DEFAULT now(),
    ready_at      timestamptz,

    CONSTRAINT attachments_size_positive CHECK (size_bytes > 0),
    CONSTRAINT attachments_kind_valid    CHECK (kind BETWEEN 0 AND 4),
    CONSTRAINT attachments_status_valid  CHECK (status BETWEEN 0 AND 2)
);

CREATE INDEX ix_attachments_message  ON attachments (message_id) WHERE message_id IS NOT NULL;
-- Drives the reaper: uploads that were never attached to a message.
CREATE INDEX ix_attachments_orphaned ON attachments (created_at)
    WHERE message_id IS NULL;

COMMENT ON COLUMN attachments.status IS
    'PENDING until the server has HEADed the object in storage. A client cannot move a row to '
    'READY by asserting it — only verification does that.';

-- ----------------------------------------------------------------------------
-- Location sharing (part of feature 6)
--
-- Columns on the message rather than an attachment: a shared location has no file, and
-- modelling it as one would mean every read joins a table for something that is two floats.
-- ----------------------------------------------------------------------------

ALTER TABLE messages
    ADD COLUMN location_lat   double precision,
    ADD COLUMN location_lon   double precision,
    ADD COLUMN location_label text,
    -- Set for live location; the pin stops updating once this passes.
    ADD COLUMN location_expires_at timestamptz;

-- ----------------------------------------------------------------------------
-- Stories (features 5 and 20)
--
-- Overlays are JSONB and composited by the client, never baked into the image. Text,
-- stickers, mentions, polls, the music widget and location pins then all share one uniform
-- structure — and a story can be restyled or re-rendered later without re-uploading a byte.
-- This is the clearest case in the whole app for a document field.
-- ----------------------------------------------------------------------------

CREATE TABLE stories (
    id            bigint      PRIMARY KEY,
    author_id     bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    attachment_id bigint      REFERENCES attachments(id) ON DELETE SET NULL,
    -- Text-only stories have no media, just a background and overlays.
    background    text,

    -- [{type, x, y, rot, scale, ...}] — see StoryOverlay in the Kotlin source.
    overlays      jsonb       NOT NULL DEFAULT '[]'::jsonb,

    -- {"mode":"all"|"except"|"only","userIds":[...]}
    audience      jsonb       NOT NULL DEFAULT '{"mode":"all"}'::jsonb,

    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    deleted_at    timestamptz
);
CREATE INDEX ix_stories_author  ON stories (author_id, created_at DESC);
-- The reader's query: everything still live, newest first.
CREATE INDEX ix_stories_live    ON stories (expires_at) WHERE deleted_at IS NULL;

CREATE TABLE story_views (
    story_id  bigint      NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    viewer_id bigint      NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    viewed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, viewer_id)
);
CREATE INDEX ix_story_views_viewer ON story_views (viewer_id, viewed_at DESC);

-- ----------------------------------------------------------------------------
-- Push device tokens (feature 7)
--
-- The table exists now even though delivery does not: registering a device is the half that
-- doesn't need Apple and Google credentials, and having it in place means the client can
-- ship its registration path before the server can actually send anything.
-- ----------------------------------------------------------------------------

CREATE TABLE push_tokens (
    id           bigint      PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform     smallint    NOT NULL,   -- 0 FCM, 1 APNs, 2 WebPush
    token        text        NOT NULL,
    device_id    uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz,
    -- Set when the provider tells us the token is dead, rather than deleting immediately:
    -- a token that starts failing often recovers, and churning rows loses that signal.
    invalid_at   timestamptz,

    CONSTRAINT push_tokens_platform_valid CHECK (platform BETWEEN 0 AND 2)
);
CREATE UNIQUE INDEX ux_push_tokens_token ON push_tokens (token);
CREATE INDEX ix_push_tokens_user ON push_tokens (user_id) WHERE invalid_at IS NULL;
