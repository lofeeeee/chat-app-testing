package app.singular.push

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * Reads the two private keys push signing needs, using only the JDK.
 *
 * Both credentials arrive as PKCS#8 PEM — an APNs `.p8` auth key and the `private_key` field of
 * a Firebase service-account JSON are both `-----BEGIN PRIVATE KEY-----`. That is exactly the
 * format `PKCS8EncodedKeySpec` takes, so `KeyFactory` reads them directly and BouncyCastle's
 * `PEMParser` was never required.
 *
 * This started as `PEMParser` from `bcpkix`, which isn't on the classpath — only `bcprov` is.
 * Adding the missing artifact was one option; deleting the need for it is the better one. A
 * cryptography dependency earns its place by doing something the platform can't, and stripping
 * base64 out of an armoured block is not that.
 *
 * The one thing BouncyCastle would still buy is reading the older SEC1/PKCS#1 forms
 * (`BEGIN EC PRIVATE KEY`, `BEGIN RSA PRIVATE KEY`), which the JDK cannot parse. Neither
 * provider issues those, so [readPkcs8] rejects them with an error that says what to do rather
 * than failing obscurely inside a key factory.
 */
internal object PemKeys {

    /**
     * @param algorithm "EC" for an APNs `.p8`, "RSA" for a Firebase service account.
     */
    fun readPkcs8(pem: String, algorithm: String): PrivateKey {
        val body = pem.trim()

        require(!body.contains("BEGIN EC PRIVATE KEY") && !body.contains("BEGIN RSA PRIVATE KEY")) {
            "That key is in the legacy SEC1/PKCS#1 format. Convert it with " +
                "`openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem` — Apple and " +
                "Google both issue PKCS#8, so this usually means the key was re-exported."
        }
        require(body.contains("BEGIN")) {
            "Expected a PEM block beginning with -----BEGIN PRIVATE KEY-----."
        }

        // Everything between the armour lines, with all whitespace removed: PEM wraps at 64
        // columns and the line endings vary by whoever last saved the file.
        val base64 = body
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .filterNot { it.isWhitespace() }

        val der = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The PEM body is not valid base64.", e)
        }

        return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
    }
}
