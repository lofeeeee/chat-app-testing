package app.singular.client.platform

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Desktop secret storage: DPAPI on Windows, restrictive file permissions everywhere.
 *
 * DPAPI is reached through PowerShell's `ProtectedData` rather than a JNI binding, because the
 * alternative is adding a native-interop dependency to call two functions. The cost is one
 * short-lived process on read and on write — at most twice per launch, off the UI thread — and
 * in exchange the blob is bound to the Windows account *and* the machine, which no amount of
 * hand-rolled AES with a key sitting next to the ciphertext can give you.
 *
 * See [readSecret]'s expect declaration for what this does and does not defend against. In
 * short: it stops another user and another machine, not malware running as you.
 */
private object SecureStore {

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    // Through DataDir, so a window started with `-Dsingular.dataDir` reads and writes its own
    // token file and cannot see — or clobber — the real one.
    private fun fileFor(key: String) = DataDir.file("$key.secret")

    fun read(key: String): String? = runCatching {
        val file = fileFor(key)
        if (!file.exists()) return null
        val stored = file.readText().trim()
        if (stored.isEmpty()) return null

        val (marker, payload) = stored.split(':', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else "" to stored
        }

        when (marker) {
            "dpapi" -> unprotect(payload)
            // Written by a build that could not reach DPAPI. Still readable, so an upgrade
            // doesn't sign everyone out — and rewritten protected on the next write.
            "plain" -> String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            else -> null
        }
    }.getOrNull()

    fun write(key: String, value: String) {
        runCatching {
            val protectedValue = if (isWindows) protect(value) else null
            val line =
                if (protectedValue != null) "dpapi:$protectedValue"
                else "plain:" + Base64.getEncoder().encodeToString(value.toByteArray())

            val file = fileFor(key)
            file.writeText(line)
            restrictToOwner(file)
        }
    }

    fun clear(key: String) {
        runCatching { fileFor(key).delete() }
    }

    // -- DPAPI ---------------------------------------------------------------

    /** Base64 of the DPAPI blob, or null when DPAPI isn't reachable. */
    private fun protect(value: String): String? = runCatching {
        val b64 = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        powershell(
            """
            Add-Type -AssemblyName System.Security;
            ${'$'}b = [Convert]::FromBase64String('$b64');
            ${'$'}p = [Security.Cryptography.ProtectedData]::Protect(${'$'}b, ${'$'}null, 'CurrentUser');
            [Convert]::ToBase64String(${'$'}p)
            """
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun unprotect(blob: String): String? = runCatching {
        val out = powershell(
            """
            Add-Type -AssemblyName System.Security;
            ${'$'}b = [Convert]::FromBase64String('$blob');
            ${'$'}p = [Security.Cryptography.ProtectedData]::Unprotect(${'$'}b, ${'$'}null, 'CurrentUser');
            [Convert]::ToBase64String(${'$'}p)
            """
        )
        if (out.isBlank()) null
        else String(Base64.getDecoder().decode(out), Charsets.UTF_8)
    }.getOrNull()

    /**
     * Runs a snippet and returns its trimmed stdout.
     *
     * `-NonInteractive` and a timeout because this sits on the launch path: a PowerShell that
     * stops for a prompt, or an execution policy that blocks, must degrade to "no stored
     * token" — a sign-in screen — rather than to an app that never finishes starting.
     */
    private fun powershell(script: String): String {
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-Command", script.trimIndent().replace('\n', ' '),
        ).redirectErrorStream(false).start()

        val out = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ""
        }
        return if (process.exitValue() == 0) out.trim() else ""
    }

    // -- Permissions ---------------------------------------------------------

    /**
     * Takes the file down to owner-only.
     *
     * Belt and braces next to DPAPI, and the only protection at all on Linux and macOS, where
     * this store falls back to base64. `File.setReadable(false, false)` clears the group and
     * world bits; on Windows the POSIX-shaped calls are approximations, so `icacls` does the
     * real work — inheritance off, then a single grant to the current user.
     */
    private fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }

        if (!isWindows) return
        runCatching {
            val user = System.getenv("USERNAME") ?: return
            ProcessBuilder(
                "icacls", file.absolutePath,
                "/inheritance:r", "/grant:r", "$user:(R,W)",
            ).start().waitFor(10, TimeUnit.SECONDS)
        }
    }
}

actual fun readSecret(key: String): String? = SecureStore.read(key)

actual fun writeSecret(key: String, value: String) = SecureStore.write(key, value)

actual fun clearSecret(key: String) = SecureStore.clear(key)
