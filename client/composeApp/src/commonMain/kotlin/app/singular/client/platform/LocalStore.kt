package app.singular.client.platform

/**
 * A tiny key-value store for local UI state, one per platform.
 *
 * Everything persisted here is disposable interface state — recently used emoji and nothing
 * else. The server remains the source of truth for anything a user would care about losing,
 * which is why this is deliberately *not* a general settings store: those live server-side in
 * `updateSettings`, where they follow the account rather than the machine.
 *
 * Plain `expect fun` declarations, same as [deviceId] — expect classes/objects are still a
 * Beta Kotlin feature and warn on every build.
 */

/** Reads [key], or null when it has never been written. Synchronous; call off the UI thread. */
expect fun readLocalString(key: String): String?

/** Writes [key]. Never throws; a machine without writable storage just forgets. */
expect fun writeLocalString(key: String, value: String)

/** Reads [key] as a list of lines, for convenience. Blank lines are dropped. */
fun readLocalList(key: String): List<String> =
    readLocalString(key)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

/** Writes a list; entries are joined with newlines, so no entry may contain one. */
fun writeLocalList(key: String, values: List<String>) {
    writeLocalString(key, values.joinToString("\n") { it.replace('\n', ' ') })
}
