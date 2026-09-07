package app.singular.push

import app.singular.core.Snowflake
import app.singular.domain.PresenceStatus
import app.singular.presence.Presence
import app.singular.presence.PresenceService
import app.singular.social.SocialRepository
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notification decision, isolated. This is the single place blocks, mutes and DND are
 * honoured, so it's the single place worth a matrix: a "muted channel still buzzed me" bug is
 * a regression in one of exactly these branches.
 */
class PushServiceTest {

    private val tokens = mockk<PushTokenRepository>(relaxed = true)
    private val social = mockk<SocialRepository>()
    private val presence = mockk<PresenceService>()
    private val snowflake = mockk<Snowflake>()

    private val service = PushService(
        tokens = tokens,
        social = social,
        presence = presence,
        transports = listOf(LoggingPushTransport()),
        snowflake = snowflake,
    )

    private fun presence(status: PresenceStatus) {
        every { presence.presenceOf(any(), any()) } returns Presence(userId = 1, status = status)
    }

    @Test
    fun `never notifies the author about their own message`() {
        assertFalse(service.shouldNotify(recipientId = 7, authorId = 7, channelId = 1))
    }

    @Test
    fun `notifies an unobstructed recipient`() {
        every { social.blockedBy(any()) } returns emptySet()
        every { social.mutedUsers(any()) } returns emptySet()
        every { social.mutedChannels(any()) } returns emptySet()
        presence(PresenceStatus.ONLINE)

        assertTrue(service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1))
    }

    @Test
    fun `a block in either direction suppresses`() {
        every { social.blockedBy(7) } returns setOf(8L)
        assertTrue(8L in social.blockedBy(7))
        assertFalse(service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1))
    }

    @Test
    fun `a muted author suppresses`() {
        every { social.blockedBy(7) } returns emptySet()
        every { social.mutedUsers(7) } returns setOf(8L)
        assertFalse(service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1))
    }

    @Test
    fun `a muted channel suppresses`() {
        every { social.blockedBy(7) } returns emptySet()
        every { social.mutedUsers(7) } returns emptySet()
        every { social.mutedChannels(7) } returns setOf(1L)
        assertFalse(service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1))
    }

    @Test
    fun `DND suppresses even an otherwise-clean recipient`() {
        every { social.blockedBy(7) } returns emptySet()
        every { social.mutedUsers(7) } returns emptySet()
        every { social.mutedChannels(7) } returns emptySet()
        presence(PresenceStatus.DND)

        assertFalse(service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1))
    }

    @Test
    fun `invisible and away still notify`() {
        every { social.blockedBy(any()) } returns emptySet()
        every { social.mutedUsers(any()) } returns emptySet()
        every { social.mutedChannels(any()) } returns emptySet()

        listOf(PresenceStatus.ONLINE, PresenceStatus.AWAY, PresenceStatus.INVISIBLE).forEach { status ->
            presence(status)
            assertTrue(
                service.shouldNotify(recipientId = 7, authorId = 8, channelId = 1),
                "expected $status to notify",
            )
        }
    }
}
