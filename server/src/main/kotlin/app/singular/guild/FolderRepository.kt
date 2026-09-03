package app.singular.guild

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** One folder in the server rail. Feature 18. */
data class GuildFolder(
    val id: String,
    val name: String?,
    val color: Int?,
    val guildIds: List<String>,
)

data class FolderLayout(
    val folders: List<GuildFolder>,
    /** Servers not filed in any folder, in the order the user dragged them into. */
    val loose: List<String>,
)

/**
 * Server folders.
 *
 * A **per-user view preference**, not shared state — two people in the same twelve servers can
 * organise them completely differently, and neither sees the other's arrangement. That is why
 * this is one JSONB row per user rather than a table of folder memberships: nothing ever
 * queries across it, the client owns the shape, and adding a field costs no migration.
 */
@Repository
class FolderRepository(
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
) {

    fun load(userId: Long): FolderLayout = jdbc
        .sql("SELECT folders::text AS folders, guild_order::text AS guild_order FROM guild_folders WHERE user_id = :u")
        .param("u", userId)
        .query { rs, _ ->
            FolderLayout(
                folders = parseFolders(rs.getString("folders")),
                loose = parseIds(rs.getString("guild_order")),
            )
        }
        .optional()
        // A user who has never reordered anything still needs an answer; returning null would
        // push an empty-case branch into every caller.
        .orElse(FolderLayout(emptyList(), emptyList()))

    fun save(userId: Long, layout: FolderLayout) {
        jdbc.sql(
            """
            INSERT INTO guild_folders (user_id, folders, guild_order)
            VALUES (:u, CAST(:f AS jsonb), CAST(:o AS jsonb))
            ON CONFLICT (user_id) DO UPDATE SET
                folders     = EXCLUDED.folders,
                guild_order = EXCLUDED.guild_order,
                updated_at  = now()
            """
        )
            .param("u", userId)
            .param("f", json.writeValueAsString(layout.folders))
            .param("o", json.writeValueAsString(layout.loose))
            .update()
    }

    private fun parseFolders(raw: String?): List<GuildFolder> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.readTree(raw).map { node ->
                GuildFolder(
                    id = node.path("id").asText(),
                    name = node.path("name").takeIf { !it.isNull }?.asText(),
                    color = node.path("color").takeIf { it.isNumber }?.asInt(),
                    guildIds = node.path("guildIds").map { it.asText() },
                )
            }
        // Malformed JSON here means a broken client wrote it, and the right failure is an
        // unsorted server list rather than a rail that refuses to render at all.
        }.getOrDefault(emptyList())
    }

    private fun parseIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.readTree(raw).map { it.asText() } }.getOrDefault(emptyList())
    }
}
