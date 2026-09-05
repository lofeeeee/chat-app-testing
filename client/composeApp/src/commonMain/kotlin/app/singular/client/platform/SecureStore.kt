package app.singular.client.platform

/**
 * Storage for the one secret this app keeps on disk: the refresh token.
 *
 * Deliberately separate from [readLocalString]. That store holds disposable interface state in
 * a plain text file and says so; putting a credential in it would be the single worst thing in
 * this codebase. Different guarantees deserve different doors.
 *
 * ## What this actually protects against, and what it does not
 *
 * The desktop implementation encrypts through **DPAPI** with `CurrentUser` scope. That gives
 * two real properties:
 *
 *  * Another **user** on the same machine cannot read it, even with the file in hand.
 *  * The file is **useless on another machine** — copying it to an attacker's box gets them
 *    ciphertext they cannot unwrap.
 *
 * It does **not** protect against malware already running as you. Nothing in user space does:
 * an attacker at that privilege can simply ask DPAPI to unprotect the blob, which is exactly
 * how infostealers lift Chrome and Discord tokens today. Anyone claiming otherwise is selling
 * obfuscation as security.
 *
 * What genuinely narrows that window is on the server, and is already built: refresh tokens
 * are opaque, single-use, and rotate on every exchange, with reuse detection that revokes the
 * whole family. A stolen token is therefore good for one use, and using it *tells the real
 * owner's session to die* — so theft is detectable and self-limiting rather than silent and
 * permanent. Storage hardening buys the time; rotation is what bounds the damage.
 */

/** Reads the stored secret for [key], or null if absent or undecryptable. */
expect fun readSecret(key: String): String?

/** Stores [value] under [key]. Never throws — a machine that cannot store it just won't. */
expect fun writeSecret(key: String, value: String)

/** Removes [key]. Called on sign-out, and on any hint the stored value is no longer good. */
expect fun clearSecret(key: String)

/** The one key in use. A constant so a typo can't silently create a second, empty slot. */
const val REFRESH_TOKEN_KEY = "refresh_token"
