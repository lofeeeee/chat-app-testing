package app.singular.security

import app.singular.config.SingularProperties
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hashing, HMAC and random generation.
 *
 * The distinction that matters, and the one most often got wrong:
 *
 *   HASH    — one-way. For things you only ever COMPARE: passwords, tokens, recovery codes.
 *   HMAC    — one-way but keyed. For blind indexes: lets you look a value up without
 *             storing it in the clear, and an attacker without the pepper can't build a
 *             rainbow table for it.
 *   ENCRYPT — two-way. For things you must READ BACK: message bodies, email, files.
 *             Not implemented here; lands in phase 5 with envelope keys.
 */
@Component
class Crypto(props: SingularProperties) {

    private val pepper: ByteArray = props.crypto.pepper.toByteArray()
    private val random = SecureRandom()

    /**
     * Argon2id at OWASP's minimum: 19 MiB, 2 iterations, 1 lane, 16-byte salt, 32-byte hash.
     *
     * Memory cost is the point — it's what makes GPU cracking uneconomic — so tune `memory`
     * up rather than `iterations` if you have headroom.
     */
    private val passwordEncoder = Argon2PasswordEncoder(
        /* saltLength = */ 16,
        /* hashLength = */ 32,
        /* parallelism = */ 1,
        /* memory = */ 19 * 1024,
        /* iterations = */ 2,
    )

    fun hashPassword(raw: CharSequence): String = passwordEncoder.encode(raw)

    /**
     * A real Argon2id hash of a value nobody will ever know.
     *
     * Verifying against this on a failed lookup burns the same CPU time as verifying a genuine
     * hash, so "no such account" and "wrong password" take equally long. Skipping the hash when
     * the account doesn't exist is a textbook user-enumeration timing side channel — and it has
     * to be a *valid* encoded hash, or the encoder short-circuits and the timing tell comes
     * straight back.
     */
    val dummyHash: String by lazy { hashPassword(randomToken()) }

    fun verifyPassword(raw: CharSequence, encoded: String): Boolean =
        passwordEncoder.matches(raw, encoded)

    /**
     * True when [encoded] was produced with weaker parameters than the current policy, meaning
     * it should be transparently re-hashed on the user's next successful sign-in.
     */
    fun passwordNeedsRehash(encoded: String): Boolean = passwordEncoder.upgradeEncoding(encoded)

    /** Raw SHA-256. For high-entropy inputs only — never for passwords. */
    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun sha256(input: String): ByteArray = sha256(input.toByteArray())

    /**
     * Keyed digest for blind indexes and IP matching.
     *
     * Lets `WHERE email_bidx = ?` and "was this IP seen before" work without ever storing the
     * plaintext in a searchable column. The pepper must never live in the database — if it
     * leaks alongside a dump, the index is just a hash again.
     */
    fun blindIndex(value: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(pepper, HMAC_ALG))
        return mac.doFinal(value.lowercase().trim().toByteArray())
    }

    /** URL-safe, unpadded, 256 bits of entropy. */
    fun randomToken(bytes: Int = 32): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    companion object {
        private const val HMAC_ALG = "HmacSHA256"

        /** Comparison whose timing doesn't depend on where the first differing byte is. */
        fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
            MessageDigest.isEqual(a, b)
    }
}
