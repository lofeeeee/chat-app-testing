package app.singular.client.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.singular.client.SingularApp
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android secret storage: Keystore-backed AES-256-GCM at rest.
 *
 * The previous implementation was private-mode SharedPreferences — the value sat on disk in
 * plaintext, protected only by the app sandbox. That boundary holds against other *apps* on a
 * non-rooted device, which is real, but not against a rooted device or a forensic extraction
 * of the data partition: both yield the raw refresh token to whoever holds the phone.
 *
 * This version encrypts the blob with an AES-256-GCM key that lives in the **Android Keystore**
 * and is non-exportable. Consequences, stated honestly:
 *
 *  * Reading the prefs file off the disk (rooted device, forensic dump) now yields ciphertext,
 *    and the key is in TEE-backed storage the OS will not export — the dump alone is no longer
 *    the token.
 *  * It does NOT defend against malware running *as this app*, which can simply ask the
 *    Keystore to decrypt, exactly like DPAPI on desktop. Same threat line as
 *    `SecureStore.desktop.kt`; rotation + reuse detection on the server is what bounds that.
 *  * Android's full-device backup will not restore the secrets (the key is unrecoverable),
 *    which is the correct default for a credential — the user just signs in again.
 *
 * ## Format
 *
 * `v1:<base64(iv)>:<base64(ciphertext)>` per value, each write a fresh IV — GCM with a reused
 * IV is catastrophic, and generating per-encryption is the cheap habit that makes it
 * impossible to get wrong by accident. Values written by the old plaintext build are still
 * readable (and migrated to encrypted on the next write), so an app update doesn't sign
 * everyone out.
 */
private object SecureStore {

    private const val PREFS = "singular_secrets"
    private const val KEY_ALIAS = "singular_secret_store"
    private const val FORMAT_PREFIX = "v1"
    private const val IV_BYTES = 12   // GCM standard: 96-bit IV
    private const val TAG_BITS = 128

    private val prefs by lazy {
        SingularApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun read(key: String): String? = runCatching {
        val stored = prefs.getString(key, null) ?: return null

        // Migration: values from the plaintext build — still readable, rewritten encrypted
        // on the next write. Clearing the key on read failure would sign people out over a
        // transient Keystore hiccup, so a bad entry degrades to null (sign-in screen).
        if (!stored.startsWith("$FORMAT_PREFIX:")) return stored

        val parts = stored.split(':')
        if (parts.size != 3) return null

        val iv = Base64.getDecoder().decode(parts[1])
        val blob = Base64.getDecoder().decode(parts[2])

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(blob), Charsets.UTF_8)
    }.getOrNull()

    fun write(key: String, value: String) {
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

            val b64 = Base64.getEncoder()
            prefs.edit().putString(
                key,
                "$FORMAT_PREFIX:${b64.encodeToString(cipher.iv)}:${b64.encodeToString(blob)}",
            ).apply()
        }
    }

    fun clear(key: String) {
        runCatching { prefs.edit().remove(key).apply() }
    }

    /**
     * The Keystore key, created on first use.
     *
     * Lazy generation rather than at startup: a device with a broken Keystore (rare, but
     * vendor ROMs ship bugs) then degrades per-value instead of failing the whole launch.
     * A hard Keystore failure surfaces as `runCatching` → null → sign-in screen, which is the
     * correct floor for "we cannot store your secret on this machine".
     */
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}

actual fun readSecret(key: String): String? = SecureStore.read(key)

actual fun writeSecret(key: String, value: String) = SecureStore.write(key, value)

actual fun clearSecret(key: String) = SecureStore.clear(key)
