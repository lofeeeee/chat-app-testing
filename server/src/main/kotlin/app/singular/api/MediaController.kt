package app.singular.api

import app.singular.channel.ChannelService
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.domain.Message
import app.singular.domain.User
import app.singular.media.Attachment
import app.singular.media.AttachmentRepository
import app.singular.media.MediaService
import app.singular.media.UploadSlot
import app.singular.message.MessageService
import app.singular.presence.RichPresence
import app.singular.presence.RichPresenceService
import app.singular.push.PushPlatform
import app.singular.push.PushService
import app.singular.security.principalOrNull
import app.singular.security.requirePrincipal
import app.singular.story.Story
import app.singular.story.StoryRepository
import app.singular.story.StoryService
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import java.time.Instant
import java.util.UUID

/**
 * A shared location.
 *
 * A real class rather than a Map: Spring resolves GraphQL fields from properties, and a Map
 * leaves every field unmapped — which the schema inspector reports at boot rather than
 * letting it fail per-request.
 */
data class LocationView(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
    val expiresAt: Instant?,
)

/** A story plus its viewer, so `seen` can resolve without a second lookup. */
data class StoryView(val story: Story, val viewerId: Long)

@Controller
class MediaController(
    private val media: MediaService,
    private val attachments: AttachmentRepository,
    private val stories: StoryService,
    private val storyRepo: StoryRepository,
    private val richPresence: RichPresenceService,
    private val push: PushService,
    private val messageService: MessageService,
    private val channelService: ChannelService,
    private val users: UserRepository,
) {

    // -- Uploads -------------------------------------------------------------

    @MutationMapping
    fun createUpload(
        @Argument filename: String,
        @Argument contentType: String,
        @Argument sizeBytes: String,
        @Argument voiceNote: Boolean?,
        ctx: GraphQLContext,
    ): UploadSlot {
        // Size arrives as a string for the same reason snowflakes do — a 100 MB file is well
        // inside Int range, but the schema shouldn't cap it there.
        val size = sizeBytes.toLongOrNull() ?: throw InvalidInput("Invalid file size.")
        return media.createUpload(
            uploaderId = ctx.requirePrincipal().userId,
            filename = filename,
            contentType = contentType,
            sizeBytes = size,
            voiceNote = voiceNote == true,
        )
    }

    @MutationMapping
    fun finalizeUpload(
        @Argument attachmentId: Long,
        @Argument durationMs: Int?,
        @Argument waveform: List<Int>?,
        ctx: GraphQLContext,
    ): Attachment = media.finalizeUpload(
        attachmentId, ctx.requirePrincipal().userId, durationMs, waveform,
    )

    // -- Location ------------------------------------------------------------

    @MutationMapping
    fun sendLocation(
        @Argument channelId: Long,
        @Argument latitude: Double,
        @Argument longitude: Double,
        @Argument label: String?,
        @Argument expiresAt: Instant?,
        ctx: GraphQLContext,
    ): Message {
        val principal = ctx.requirePrincipal()
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw InvalidInput("Those coordinates aren't on Earth.")
        }
        return messageService.sendLocation(
            channelId, principal.userId, latitude, longitude, label,
            expiresAt, principal.sessionId,
        )
    }

    // -- Stories -------------------------------------------------------------

    @QueryMapping
    fun storyFeed(ctx: GraphQLContext): List<StoryView> {
        val me = ctx.requirePrincipal().userId
        // Everyone you share a conversation with. "Everyone" would make a story a public post
        // rather than something you show the people you actually talk to.
        val contacts = channelService.listForUser(me)
            .flatMap { channelService.membersOf(it.id) }
            .toSet() + me
        return stories.feedFor(me, contacts).map { StoryView(it, me) }
    }

    @QueryMapping
    fun story(@Argument id: Long, ctx: GraphQLContext): StoryView {
        val me = ctx.requirePrincipal().userId
        return StoryView(stories.view(id, me), me)
    }

    @QueryMapping
    fun storyViewers(@Argument storyId: Long, ctx: GraphQLContext): List<User> {
        val ids = stories.viewers(storyId, ctx.requirePrincipal().userId)
        val loaded = users.findAllById(ids)
        return ids.mapNotNull { loaded[it] }
    }

    @MutationMapping
    fun createStory(
        @Argument attachmentId: Long?,
        @Argument background: String?,
        @Argument overlays: String?,
        @Argument audienceMode: String?,
        @Argument audienceUserIds: List<Long>?,
        ctx: GraphQLContext,
    ): StoryView {
        val me = ctx.requirePrincipal().userId
        val story = stories.create(
            me, attachmentId, background, overlays, audienceMode, audienceUserIds.orEmpty(),
        )
        return StoryView(story, me)
    }

    @MutationMapping
    fun deleteStory(@Argument id: Long, ctx: GraphQLContext): Boolean =
        stories.delete(id, ctx.requirePrincipal().userId)

    @MutationMapping
    fun markStorySeen(@Argument id: Long, ctx: GraphQLContext): Boolean {
        stories.view(id, ctx.requirePrincipal().userId)
        return true
    }

    // -- Rich presence (feature 17) ------------------------------------------

    @MutationMapping
    fun setRichPresence(
        @Argument activityType: String,
        @Argument name: String,
        @Argument details: String?,
        @Argument state: String?,
        @Argument largeImageKey: String?,
        @Argument endsAt: Instant?,
        ctx: GraphQLContext,
    ): Boolean {
        val me = ctx.requirePrincipal().userId
        if (name.isBlank()) throw InvalidInput("Rich presence needs a name.")
        richPresence.set(
            RichPresence(
                userId = me,
                activityType = activityType.uppercase().take(20),
                name = name.take(128),
                details = details?.take(128),
                state = state?.take(128),
                largeImageKey = largeImageKey?.take(256),
                endsAt = endsAt,
            )
        )
        return true
    }

    @MutationMapping
    fun clearRichPresence(ctx: GraphQLContext): Boolean {
        richPresence.clear(ctx.requirePrincipal().userId)
        return true
    }

    @SubscriptionMapping
    fun richPresenceChanged(ctx: GraphQLContext): Flux<RichPresence> {
        ctx.requirePrincipal()
        return richPresence.subscribe()
    }

    // -- Push registration (feature 7) ---------------------------------------

    @MutationMapping
    fun registerPushToken(
        @Argument platform: String,
        @Argument token: String,
        @Argument deviceId: String?,
        ctx: GraphQLContext,
    ): Boolean {
        val parsed = PushPlatform.byName(platform) ?: throw InvalidInput("Unknown platform.")
        push.register(
            ctx.requirePrincipal().userId,
            parsed,
            token,
            deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        )
        return true
    }

    @MutationMapping
    fun unregisterPushToken(@Argument token: String, ctx: GraphQLContext): Boolean {
        ctx.requirePrincipal()
        return push.unregister(token)
    }

    // -- Field resolvers -----------------------------------------------------

    @BatchMapping(typeName = "Message", field = "attachments")
    fun messageAttachments(messages: List<Message>): Map<Message, List<Attachment>> {
        val loaded = attachments.forMessages(messages.map { it.id })
        return messages.associateWith { loaded[it.id].orEmpty() }
    }

    @SchemaMapping(typeName = "Attachment", field = "kind")
    fun attachmentKind(a: Attachment): String = a.kind.name

    @SchemaMapping(typeName = "Attachment", field = "status")
    fun attachmentStatus(a: Attachment): String = a.status.name

    @SchemaMapping(typeName = "Attachment", field = "sizeBytes")
    fun attachmentSize(a: Attachment): String = a.sizeBytes.toString()

    /**
     * Signed at read time, never stored.
     *
     * The URL expires, so caching it in a client would produce dead links after an hour. A
     * pending upload has no URL at all — there is nothing there yet to link to.
     */
    @SchemaMapping(typeName = "Attachment", field = "url")
    fun attachmentUrl(a: Attachment): String? =
        if (a.status == app.singular.media.AttachmentStatus.READY) media.downloadUrl(a.objectKey)
        else null

    @SchemaMapping(typeName = "Attachment", field = "thumbnailUrl")
    fun attachmentThumbnail(a: Attachment): String? =
        a.thumbnailKey?.let { media.downloadUrl(it) }

    @SchemaMapping(typeName = "Story", field = "id")
    fun storyId(v: StoryView) = v.story.id

    @SchemaMapping(typeName = "Story", field = "author")
    fun storyAuthor(v: StoryView): User? = users.findById(v.story.authorId)

    @SchemaMapping(typeName = "Story", field = "attachment")
    fun storyAttachment(v: StoryView): Attachment? =
        v.story.attachmentId?.let { attachments.find(it) }

    @SchemaMapping(typeName = "Story", field = "background")
    fun storyBackground(v: StoryView) = v.story.background

    @SchemaMapping(typeName = "Story", field = "overlays")
    fun storyOverlays(v: StoryView) = v.story.overlaysJson

    @SchemaMapping(typeName = "Story", field = "createdAt")
    fun storyCreated(v: StoryView) = v.story.createdAt

    @SchemaMapping(typeName = "Story", field = "expiresAt")
    fun storyExpires(v: StoryView) = v.story.expiresAt

    @SchemaMapping(typeName = "Story", field = "viewCount")
    fun storyViewCount(v: StoryView): Int = storyRepo.viewCount(v.story.id)

    @SchemaMapping(typeName = "Story", field = "seen")
    fun storySeen(v: StoryView): Boolean =
        v.viewerId in storyRepo.viewerIds(v.story.id)

    @SchemaMapping(typeName = "RichPresence", field = "userId")
    fun rpUser(p: RichPresence) = p.userId

    @BatchMapping(typeName = "User", field = "richPresence")
    fun userRichPresence(list: List<User>): Map<User, RichPresence?> {
        val loaded = richPresence.forUsers(list.map { it.id })
        return list.associateWith { loaded[it.id] }
    }

    @SchemaMapping(typeName = "Message", field = "location")
    fun messageLocation(message: Message): LocationView? {
        val lat = message.locationLat ?: return null
        val lon = message.locationLon ?: return null
        return LocationView(lat, lon, message.locationLabel, message.locationExpiresAt)
    }
}
