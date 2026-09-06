package app.singular.client.platform

import java.io.File

/**
 * Where this instance keeps its per-machine state: the device id, local preferences, and the
 * stored refresh token.
 *
 * One resolver rather than the same `APPDATA` expression copied into three files. That
 * duplication was the reason a second window could not be isolated: overriding the location
 * meant remembering to override it in each place, and forgetting one would leave a "temporary"
 * window quietly writing its login over the real one.
 *
 * ## `-Dsingular.dataDir`
 *
 * Points every one of those at somewhere else. `start_another.bat` gives each extra window a
 * fresh temporary directory, which makes it genuinely disposable: it starts signed out because
 * there is no token to read, and it cannot overwrite the primary window's session because it
 * never writes to that directory at all.
 *
 * Isolating the directory — rather than adding a "don't save my login" flag — is what makes
 * that true of *everything*, including the device id. Two windows sharing a device id would
 * appear as one entry in "where you're signed in", and revoking one would sign out both.
 */
object DataDir {

    /** True when this instance is running against a throwaway directory. */
    val isEphemeral: Boolean = System.getProperty(PROPERTY) != null

    val root: File by lazy {
        val override = System.getProperty(PROPERTY)
        val base =
            if (override != null) File(override)
            else when {
                System.getProperty("os.name").orEmpty().startsWith("Windows") ->
                    File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Singular")
                System.getProperty("os.name").orEmpty().startsWith("Mac") ->
                    File(System.getProperty("user.home"), "Library/Application Support/Singular")
                else ->
                    File(System.getProperty("user.home"), ".config/singular")
            }
        base.mkdirs()
        base
    }

    fun file(name: String): File = File(root, name)

    private const val PROPERTY = "singular.dataDir"
}
