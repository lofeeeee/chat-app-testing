package app.singular.client.platform

import java.io.File

/**
 * Desktop actual: a properties-style text file under the same config directory as the device
 * id, keeping all per-machine state in one place.
 *
 * Emoji recents are the only writer today, and losing the file to a crash mid-write is losing
 * a row of recently used emoji — the reason this is a plain write rather than an atomic
 * move. The moment something worth protecting moves in here, that changes.
 */
private object LocalStore {
    private const val FILE_NAME = "local-state.txt"

    // Resolved through DataDir so `-Dsingular.dataDir` isolates this along with everything
    // else. See DataDir for why the location is decided in one place.
    private val file: File by lazy { DataDir.file(FILE_NAME) }

    // Mutable all the way down: `toMap()` and `emptyMap()` are read-only types, so the
    // delegate has to build a mutable map rather than one the setter can't write to.
    private val values: MutableMap<String, String> by lazy {
        runCatching {
            if (file.exists()) {
                file.readLines().mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx > 0) line.take(idx) to line.substring(idx + 1) else null
                }.toMap(mutableMapOf())
            } else mutableMapOf()
        }.getOrDefault(mutableMapOf())
    }

    @Synchronized
    fun read(key: String): String? = values[key]

    @Synchronized
    fun write(key: String, value: String) {
        values[key] = value
        runCatching {
            file.writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }
}

actual fun readLocalString(key: String): String? = LocalStore.read(key)

actual fun writeLocalString(key: String, value: String) = LocalStore.write(key, value)
