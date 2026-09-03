-- ============================================================================
-- QR sign-in ("remote auth") and session management.
--
-- The flow, and why it has the shape it does:
--
--   1. An unauthenticated device asks for a login request. It receives TWO secrets:
--        * qr_token    — goes in the QR code, rotates every 20 seconds
--        * poll_secret — stays on the device, never displayed, never rotates
--   2. The device renders the QR and subscribes for updates, authenticating that
--      subscription with poll_secret.
--   3. An already-signed-in phone scans the QR and claims the request. It is shown the
--      requesting device's IP, location and platform, and must explicitly approve.
--   4. On approval the server mints a session and pushes the tokens to the ORIGINAL device
--      over its poll_secret-authenticated channel — never to whoever did the scanning.
--
-- Why two secrets: if the QR token were the only credential, rotating it would achieve
-- nothing, because the device would have to keep accepting the old one to stay subscribed.
-- Splitting them means the short-lived public half can rotate freely while the long-lived
-- private half never leaves the device.
--
-- What rotation does and does not protect against:
--   DOES  — a QR captured off a screen (screen share, a photo, a shoulder surfer, a leaked
--           screenshot) and replayed later. A 20-second window makes that mostly useless.
--   DOES NOT — QRLJacking, where an attacker shows a victim *their own* login QR and asks
--           them to scan it, capturing the victim's account onto the attacker's machine.
--           The only real defence there is step 3: an explicit approval screen naming the
--           device, its location and its IP. That step is not optional.
-- ============================================================================

CREATE TABLE login_requests (
    id                 bigint      PRIMARY KEY,

    -- SHA-256 of the current QR token. High entropy already, so no KDF.
    token_hash         bytea       NOT NULL,
    -- SHA-256 of the secret only the requesting device holds. Never in the QR.
    poll_secret_hash   bytea       NOT NULL,

    status             smallint    NOT NULL DEFAULT 0,
    -- 0 PENDING, 1 SCANNED, 2 APPROVED, 3 DENIED, 4 EXPIRED, 5 CONSUMED

    claimed_by         bigint      REFERENCES users(id) ON DELETE CASCADE,
    claimed_at         timestamptz,

    -- Shown to the approving user so they can tell their own laptop from an attacker's.
    request_ip         inet,
    request_user_agent text,
    request_platform   text,
    -- The session is minted for the REQUESTING device, so its install id is recorded here
    -- rather than taken from whichever phone did the scanning.
    request_device_id  uuid,

    created_at         timestamptz NOT NULL DEFAULT now(),
    -- The QR token's own expiry, distinct from the request's. Rotation moves this forward.
    token_expires_at   timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    consumed_at        timestamptz,

    CONSTRAINT login_requests_status_valid CHECK (status BETWEEN 0 AND 5)
);

-- Lookup is by token hash when a phone scans.
CREATE UNIQUE INDEX ux_login_requests_token ON login_requests (token_hash);
CREATE        INDEX ix_login_requests_reap  ON login_requests (expires_at)
    WHERE status NOT IN (5, 4);

COMMENT ON COLUMN login_requests.poll_secret_hash IS
    'Authenticates the requesting device''s subscription. Rotating the QR token does not '
    'rotate this — that is the whole point of splitting them.';

-- ----------------------------------------------------------------------------
-- Session management
--
-- Users think in devices, not tokens. A refresh rotation mints a new auth_sessions row every
-- 15 minutes, so listing raw rows would show one "device" per rotation. family_id is the
-- stable identity across a chain, so the settings screen groups by it.
-- ----------------------------------------------------------------------------

ALTER TABLE auth_sessions
    ADD COLUMN platform    text,
    ADD COLUMN created_via smallint NOT NULL DEFAULT 0;   -- 0 password, 1 QR, 2 refresh

COMMENT ON COLUMN auth_sessions.created_via IS
    'How this session came to exist. Surfaced in the sessions list so a QR sign-in the user '
    'does not recognise is visibly different from an ordinary password login.';

-- Listing sessions is "newest live session per family, for this user".
CREATE INDEX ix_auth_sessions_user_family
    ON auth_sessions (user_id, family_id, id DESC)
    WHERE revoked_at IS NULL;
