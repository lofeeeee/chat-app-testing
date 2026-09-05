package app.singular.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import app.singular.client.net.AttachmentBriefDto
import app.singular.client.net.LastMessageDto
import app.singular.client.net.UserDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ME = "100"
private const val ORBIT = "200"
private const val ADMINS = "900"

private fun user(id: String, name: String) =
    UserDto(id = id, username = name.lowercase(), discriminator = 1, handle = "${name.lowercase()}#0001", displayName = name)

private fun resolver(myRoles: Set<String> = emptySet()) = MentionResolver(
    usersById = mapOf(ME to user(ME, "Nova"), ORBIT to user(ORBIT, "Orbit")),
    rolesById = mapOf(ADMINS to "Admins"),
    channelsById = mapOf("300" to "general"),
    selfId = ME,
    myRoleIds = myRoles,
)

class MentionsTest {

    // -- What counts as being addressed ------------------------------------

    @Test
    fun aDirectMentionOfMeCounts() {
        assertTrue(resolver().mentionsMe("hey <@$ME> look"))
        assertFalse(resolver().mentionsMe("hey <@$ORBIT> look"))
    }

    @Test
    fun aRoleIHoldCounts() {
        assertTrue(resolver(myRoles = setOf(ADMINS)).mentionsMe("<@&$ADMINS> deploy please"))
        // The same message must NOT highlight for someone without the role, or the highlight
        // disagrees with the notification the server actually sent.
        assertFalse(resolver().mentionsMe("<@&$ADMINS> deploy please"))
    }

    @Test
    fun broadcastsAreWordAnchoredLikeTheServer() {
        assertTrue(resolver().mentionsMe("@everyone stand up"))
        assertTrue(resolver().mentionsMe("ok @here now"))

        // The regression this anchoring exists for: an unanchored `contains` lights up every
        // message carrying an address like this one.
        assertFalse(resolver().mentionsMe("mail sales@everyone.example for details"))
        assertFalse(resolver().mentionsMe("see foo@here.io"))
    }

    // -- Composing ----------------------------------------------------------

    @Test
    fun tokenDetectionRequiresAWordBoundary() {
        assertEquals("or", activeMentionToken("hi @or", 6)?.query)
        assertEquals("", activeMentionToken("hi @", 4)?.query)
        // Mid-word @ is an email, not a mention.
        assertEquals(null, activeMentionToken("mail me@example.com", 19))
        // A completed token followed by a space is no longer active.
        assertEquals(null, activeMentionToken("hi @orbit ", 10))
    }

    @Test
    fun applyMentionWritesTheWireFormat() {
        val token = activeMentionToken("hey @adm", 8)!!
        val (text, caret) = applyMention("hey @adm", token, MentionCandidate.Role(ADMINS, "Admins"))
        assertEquals("hey <@&$ADMINS> ", text)
        assertEquals(text.length, caret)

        val userToken = activeMentionToken("yo @or", 6)!!
        val (userText, _) = applyMention("yo @or", userToken, MentionCandidate.User(user(ORBIT, "Orbit")))
        assertEquals("yo <@$ORBIT> ", userText)
    }

    // -- The composer's display layer ---------------------------------------

    @Test
    fun transformationShowsNamesNotIds() {
        val out = MentionVisualTransformation(resolver(), Color.Blue)
            .filter(AnnotatedString("hey <@$ORBIT> and <@&$ADMINS>"))
        assertEquals("hey @Orbit and @Admins", out.text.text)
    }

    /**
     * The mapping is the part that crashes if it is wrong.
     *
     * Compose calls it for every caret move, selection edge and click, and throws if it ever
     * returns an offset outside the other string — so the contract is checked exhaustively at
     * every position rather than at a couple of interesting ones.
     */
    @Test
    fun offsetMappingStaysInBoundsEverywhere() {
        val samples = listOf(
            "",
            "plain text with no mentions",
            "<@$ORBIT>",
            "hey <@$ORBIT> and <@&$ADMINS> and <#300> done",
            "<@$ORBIT><@$ORBIT>",
            "trailing <@$ORBIT>",
            "@everyone at the start",
            "unknown <@999999> id",
        )

        for (raw in samples) {
            val transformed = MentionVisualTransformation(resolver(), Color.Blue)
                .filter(AnnotatedString(raw))
            val shown = transformed.text.text
            val map = transformed.offsetMapping

            for (i in 0..raw.length) {
                val t = map.originalToTransformed(i)
                assertTrue(
                    t in 0..shown.length,
                    "originalToTransformed($i) = $t is outside 0..${shown.length} for \"$raw\"",
                )
            }
            for (i in 0..shown.length) {
                val o = map.transformedToOriginal(i)
                assertTrue(
                    o in 0..raw.length,
                    "transformedToOriginal($i) = $o is outside 0..${raw.length} for \"$raw\"",
                )
            }

            // The ends must pin to the ends, or the caret can't reach the end of the line.
            assertEquals(0, map.originalToTransformed(0), "start of \"$raw\"")
            assertEquals(shown.length, map.originalToTransformed(raw.length), "end of \"$raw\"")
        }
    }
}

/**
 * The sidebar's one-line preview.
 *
 * Split out because it has bitten twice: once absent entirely, and once present but rendering
 * `You: <@221239599735771136>` — a line that is technically the last message and useless as a
 * preview of it.
 */
class LastMessagePreviewTest {

    private fun last(content: String?, authorId: String, attachments: List<AttachmentBriefDto> = emptyList()) =
        LastMessageDto(
            id = "1", content = content, createdAt = "2026-01-01T00:00:00Z",
            author = user(authorId, if (authorId == ME) "Nova" else "Orbit"),
            attachments = attachments,
        )

    @Test
    fun ownMessagesArePrefixed() {
        assertEquals("You: hello", last("hello", ME).preview(ME))
        assertEquals("hello", last("hello", ORBIT).preview(ME))
    }

    @Test
    fun mentionsResolveToNames() {
        val r = resolver()
        assertEquals(
            "You: @Orbit please check",
            last("<@$ORBIT> please check", ME).preview(ME, r::displayFor),
        )
        assertEquals("@Admins", last("<@&$ADMINS>", ORBIT).preview(ME, r::displayFor))
    }

    @Test
    fun aBareMentionStillPreviewsAsSomething() {
        // The exact case from the sidebar bug: content that is *only* a mention.
        val rendered = last("<@$ME>", ME).preview(ME, resolver()::displayFor)
        assertEquals("You: @Nova", rendered)
        assertFalse(rendered.contains("<@"), "a raw wire token reached the sidebar")
    }

    @Test
    fun attachmentsWithoutCaptionsGetALabel() {
        assertEquals(
            "You: Photo",
            last("", ME, listOf(AttachmentBriefDto("1", "IMAGE"))).preview(ME),
        )
        assertEquals(
            "Voice message",
            last(null, ORBIT, listOf(AttachmentBriefDto("1", "VOICE_NOTE"))).preview(ME),
        )
    }

    @Test
    fun newlinesCollapseToOneLine() {
        assertEquals("You: a b", last("a\nb", ME).preview(ME))
    }
}
