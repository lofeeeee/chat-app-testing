-- ============================================================================
-- Reactions (Discord/WhatsApp parity).
--
-- One row per (message, user, emoji): a person may react with the same emoji
-- only once, but may react with several different emoji on the same message.
-- The aggregate a client renders (emoji, count, whether the viewer reacted) is
-- computed at read time, never stored — a stored count would go stale the
-- moment two reactions raced.
--
-- Reactions are not partitioned with `messages`. They are small, high-churn,
-- and always looked up by message id (never time-ranged on their own), so a
-- plain table with a message_id index beats a monthly partition here.
-- ============================================================================

CREATE TABLE message_reactions (
    message_id  bigint      NOT NULL,
    channel_id  bigint      NOT NULL,  -- carried so fanout can key the channel without a join
    user_id     bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    emoji       text        NOT NULL,  -- a single unicode emoji grapheme (ZWJ/flag sequences included)
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, user_id, emoji)
);

-- The read path: "every reaction on these messages", to render chips.
CREATE INDEX ix_message_reactions_message ON message_reactions (message_id);
-- The cleanup path: removing every reaction a user ever made (account deletion).
CREATE INDEX ix_message_reactions_user ON message_reactions (user_id);
