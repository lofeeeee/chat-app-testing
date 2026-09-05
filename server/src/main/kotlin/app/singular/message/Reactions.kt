package app.singular.message

import app.singular.event.FanoutBus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import reactor.core.publisher.Flux

/**
 * A single reaction as stored: one user, one emoji, one message.
 *
 * The chip a client renders is an aggregate — see [ReactionSummary]. Storing the raw rows and
 * aggregating at read time means a count can never disagree with the rows behind it.
 */
data class Reaction(val messageId: Long, val userId: Long, val emoji: String)

/**
 * The aggregate the GraphQL API exposes: how many reacted with this emoji, and whether the
 * requesting viewer is among them (`me` drives the highlighted state of the chip).
 */
data class ReactionSummary(val emoji: String, val count: Int, val me: Boolean)

/**
 * The fanout payload for `reactionUpdated`. Carries the full post-change summary set rather
 * than a delta — a client that missed an event re-renders from the snapshot instead of trying
 * to apply a diff to a count it may have lost sync on.
 */
data class ReactionUpdate(
    val messageId: Long,
    val channelId: Long,
    val reactions: List<ReactionSummary>,
)

@Repository
class ReactionRepository(private val jdbc: JdbcClient) {

    /** Idempotent: re-adding the same emoji is a no-op, so a double-tap can't duplicate. */
    fun add(messageId: Long, channelId: Long, userId: Long, emoji: String) {
        jdbc.sql(
            """
            INSERT INTO message_reactions (message_id, channel_id, user_id, emoji)
            VALUES (:m, :c, :u, :e)
            ON CONFLICT DO NOTHING
            """
        )
            .param("m", messageId).param("c", channelId).param("u", userId).param("e", emoji)
            .update()
    }

    fun remove(messageId: Long, userId: Long, emoji: String) {
        jdbc.sql(
            """
            DELETE FROM message_reactions
            WHERE message_id = :m AND user_id = :u AND emoji = :e
            """
        )
            .param("m", messageId).param("u", userId).param("e", emoji)
            .update()
    }

    /**
     * Every reaction on the given messages, aggregated per (message, emoji), with `me` resolved
     * against [viewerId]. Ordered so the earliest-created emoji group renders first, matching
     * the left-to-right order Discord and WhatsApp both use.
     */
    fun summariesFor(messageIds: Collection<Long>, viewerId: Long): Map<Long, List<ReactionSummary>> {
        if (messageIds.isEmpty()) return emptyMap()
        return jdbc.sql(
            """
            SELECT message_id, emoji,
                   COUNT(*) AS count,
                   BOOL_OR(user_id = :viewer) AS me,
                   MIN(created_at) AS first_at
            FROM message_reactions
            WHERE message_id = ANY(:ids)
            GROUP BY message_id, emoji
            ORDER BY first_at
            """
        )
            .param("ids", messageIds.toLongArray())
            .param("viewer", viewerId)
            .query { rs, _ ->
                rs.getLong("message_id") to
                    ReactionSummary(rs.getString("emoji"), rs.getInt("count"), rs.getBoolean("me"))
            }
            .list()
            .groupBy({ it.first }, { it.second })
    }
}

/**
 * Fanout for `reactionUpdated`, keyed by channel so every watcher of the channel re-renders
 * chips — not just the people in the conversation view, but the notifications stream too.
 *
 * Same delivery deal as [MessageEvents]: publish to Valkey, every node (this one included)
 * receives it back and feeds its own local subscribers, so there is exactly one delivery path.
 */
@Component
class ReactionEvents(private val bus: FanoutBus) {

    fun publish(update: ReactionUpdate) {
        bus.publish("reaction", update.channelId.toString(), update)
    }

    fun subscribe(channelId: Long): Flux<ReactionUpdate> =
        bus.subscribe("reaction", channelId.toString())
}
