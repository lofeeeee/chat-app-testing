package app.singular.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the three copies of the preset list in step.
 *
 * A theme preset is spelled out in three places: the [ThemePreset] enum, the `ThemePreset` enum
 * in `schema.graphqls`, and `Presets.all` in the client's `Palette.kt`. Nothing links them, and
 * when the client shipped five new palettes without the other two moving, every one of them was
 * unselectable — GraphQL rejects an unknown enum value while coercing the variable, so the
 * mutation failed with `No value found for name 'PAPER'` before any resolver ran. The user saw a
 * theme they could click and not keep.
 *
 * That is a boring, entirely mechanical mistake, which is what makes it worth a test rather than
 * a comment asking people to remember. Adding a preset should either update all three or fail
 * here, at build time, naming the ones that are missing.
 */
class ThemePresetTest {

    @Test
    fun `graphql schema lists exactly the kotlin enum values`() {
        val schema = presetsIn(
            file = projectFile("src/main/resources/graphql/schema.graphqls"),
            block = Regex("""enum\s+ThemePreset\s*\{(.*?)}""", RegexOption.DOT_MATCHES_ALL),
            value = Regex("""^\s*([A-Z][A-Z0-9_]*)\s*$""", RegexOption.MULTILINE),
        )

        assertEquals(
            ThemePreset.entries.map { it.name }.toSet(),
            schema,
            "schema.graphqls and the ThemePreset enum disagree",
        )
    }

    @Test
    fun `every preset the client ships is accepted by the server`() {
        // The client is the source of truth for what a preset *is* — it owns the colours. The
        // server only has to agree that the name exists, so this asserts one direction: a
        // client preset the server would reject is the bug. A server-only value is harmless
        // (an older client simply never sends it).
        val palette = projectFile("../client/composeApp/src/commonMain/kotlin/app/singular/client/ui/Palette.kt")
        assertTrue(palette.isFile, "expected the client palette at ${palette.canonicalPath}")

        val clientIds = Regex("""id\s*=\s*"([A-Z][A-Z0-9_]*)"""")
            .findAll(palette.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue(clientIds.isNotEmpty(), "parsed no preset ids out of Palette.kt")

        val unknown = clientIds - ThemePreset.entries.map { it.name }.toSet()
        assertTrue(
            unknown.isEmpty(),
            "the client ships presets the server would reject: ${unknown.sorted()}. " +
                "Add them to the ThemePreset enum and to schema.graphqls.",
        )
    }

    private fun presetsIn(file: File, block: Regex, value: Regex): Set<String> {
        val body = block.find(file.readText())?.groupValues?.get(1)
            ?: error("no `enum ThemePreset { ... }` block in ${file.name}")
        return value.findAll(body).map { it.groupValues[1] }.toSet()
    }

    /** Resolves against the module directory, so it works from Gradle and from an IDE run. */
    private fun projectFile(path: String) = File(System.getProperty("user.dir"), path)
}
