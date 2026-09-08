-- Push delivery outbox (feature 7, final hop).
--
-- Registration, the mute/DND filter and the fanout decision were already real; this table is
-- what makes the delivery itself durable and asynchronous. A message send enqueues rows here
-- (after commit) and a worker drains them, so a slow or unreachable push provider can never
-- sit in the request path of `sendMessage` -- and a provider outage becomes "notifications
-- arrive late" rather than "chat stops working".
--
-- Token is copied onto the row rather than joined: a re-registered or deleted token between
-- enqueue and dispatch should not change what an in-flight notification was aimed at, and
-- copying keeps the worker single-table.

CREATE TABLE push_outbox (
    id            bigint PRIMARY KEY,                -- snowflake
    user_id       bigint      NOT NULL,
    token        text        NOT NULL,
    platform     smallint    NOT NULL,              -- 0 FCM, 1 APNs, 2 WebPush
    title        text        NOT NULL,
    body         text        NOT NULL,
    channel_id   bigint,
    message_id   bigint,
    attempts     smallint    NOT NULL DEFAULT 0,
    next_attempt timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz,
    failed_at    timestamptz
);

-- The worker's queue: what's due, oldest first.
CREATE INDEX ix_push_outbox_due ON push_outbox (next_attempt)
    WHERE delivered_at IS NULL AND failed_at IS NULL;

-- A dead letter is still worth reading for a while: which notifications never made it is the
-- first question anyone asks when "I never got told about this" is reported.
CREATE INDEX ix_push_outbox_failed ON push_outbox (failed_at)
    WHERE failed_at IS NOT NULL;
