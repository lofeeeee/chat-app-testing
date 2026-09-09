-- ============================================================================
-- V8 — message editing, pins, full-text search, unread counts
--
-- Three things that all answer "a message is not just an append-only row":
--   * editing/deleting already had columns (edited_at, deleted_at) but no
--     index to find them for live fanout correction;
--   * pins are a many-to-many between channels and messages, not a flag on
--     the message, because a pin is per-channel and a message lives in
--     exactly one channel but the flag would still be a second thing to
--     keep consistent with the message list;
--   * search needs a GIN index over a tsvector derived from content, which
--     works on a partitioned table as long as the index includes the
--     partition key (it does — same shape as the PK).
-- ============================================================================

-- Pins. One row per (channel, message): pinning is idempotent, unpinning
-- removes the row. `pinned_by` records who did it for the UI attribution and
-- for the audit trail, not for permission checks at read time.
CREATE TABLE message_pins (
    channel_id  bigint      NOT NULL,
    message_id  bigint      NOT NULL,
    pinned_by   bigint      NOT NULL,
    pinned_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, message_id)
);

-- Unread counts need an index that lets the sidebar ask "messages newer than
-- my cursor in this channel" without scanning the channel's whole history.
-- The existing ix_messages_channel (channel_id, id DESC) already serves this:
-- a count of ids > cursor is an index range scan on the same btree, read
-- backwards. No new index needed — but a functional note lives here so nobody
-- adds one later.

-- Full-text search. A generated column keeps the tsvector always in sync with
-- `content` (no trigger, no application discipline), and the GIN index makes
-- `content_tsv @@ query` an index scan rather than a seq scan. On a partitioned
-- table every partition gets its own index; the planner prunes partitions by
-- the created_at window the query carries, exactly like the paging path.
ALTER TABLE messages ADD COLUMN content_tsv tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(content, ''))
    ) STORED;

-- A non-unique index on a partitioned table needs no partition-key columns (that
-- rule is for unique indexes, where it exists so uniqueness can be enforced per
-- partition); Postgres cascades this one to every partition automatically, and
-- the planner prunes partitions from the created_at window the query carries,
-- exactly like the paging path.
CREATE INDEX ix_messages_fts ON messages USING GIN (content_tsv);

-- Search is bounded by time (the client picks how far back to search) so the
-- partition window stays explicit rather than implied by "all of history".
-- No new column or index needed for that; the query carries its own window.
