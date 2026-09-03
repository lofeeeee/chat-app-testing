package app.singular.api

import app.singular.audit.AuditLog
import app.singular.auth.SessionRepository
import app.singular.core.InvalidInput
import app.singular.domain.AuditAction
import app.singular.domain.DeviceSession
import app.singular.security.requirePrincipal
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller

/** The device list carries which family is the caller's own, so `current` can be resolved. */
data class DeviceSessionView(val session: DeviceSession, val currentFamilyId: Long?)

@Controller
class SessionController(
    private val sessions: SessionRepository,
    private val audit: AuditLog,
) {

    @QueryMapping
    fun sessions(ctx: GraphQLContext): List<DeviceSessionView> {
        val principal = ctx.requirePrincipal()
        val currentFamily = sessions.familyOf(principal.sessionId)
        return sessions.listDevices(principal.userId).map { DeviceSessionView(it, currentFamily) }
    }

    @MutationMapping
    fun revokeSession(@Argument id: Long, ctx: GraphQLContext): Boolean {
        val principal = ctx.requirePrincipal()

        // Ownership is enforced in the WHERE clause, not here — a forged family id revokes
        // nothing rather than someone else's laptop.
        val revoked = sessions.revokeFamilyForUser(id, principal.userId)
        if (revoked == 0) throw InvalidInput("That device isn't signed in.")

        audit.record(
            principal.userId,
            AuditAction.SESSION_REVOKED,
            sessionId = principal.sessionId,
            targetId = id,
            changes = mapOf("sessionsRevoked" to revoked),
        )
        return true
    }

    @MutationMapping
    fun revokeOtherSessions(ctx: GraphQLContext): Int {
        val principal = ctx.requirePrincipal()
        val currentFamily = sessions.familyOf(principal.sessionId)
            ?: throw InvalidInput("Your own session couldn't be identified — sign in again.")

        val revoked = sessions.revokeAllExceptFamily(principal.userId, currentFamily)
        audit.record(
            principal.userId,
            AuditAction.SESSION_REVOKED,
            sessionId = principal.sessionId,
            changes = mapOf("scope" to "all-others", "sessionsRevoked" to revoked),
        )
        return revoked
    }

    // -- Field resolvers ----------------------------------------------------
    //
    // The GraphQL id is the FAMILY id: that is the stable handle a user can act on, and it is
    // what revokeSession expects back.

    @SchemaMapping(typeName = "DeviceSession", field = "id")
    fun id(view: DeviceSessionView): Long = view.session.familyId

    @SchemaMapping(typeName = "DeviceSession", field = "deviceId")
    fun deviceId(view: DeviceSessionView): String = view.session.deviceId.toString()

    @SchemaMapping(typeName = "DeviceSession", field = "platform")
    fun platform(view: DeviceSessionView): String? = view.session.platform

    @SchemaMapping(typeName = "DeviceSession", field = "userAgent")
    fun userAgent(view: DeviceSessionView): String? = view.session.userAgent

    @SchemaMapping(typeName = "DeviceSession", field = "ipAddress")
    fun ipAddress(view: DeviceSessionView): String? = view.session.ipAddress

    @SchemaMapping(typeName = "DeviceSession", field = "origin")
    fun origin(view: DeviceSessionView): String = view.session.origin.name

    @SchemaMapping(typeName = "DeviceSession", field = "firstSeenAt")
    fun firstSeenAt(view: DeviceSessionView) = view.session.firstSeenAt

    @SchemaMapping(typeName = "DeviceSession", field = "lastSeenAt")
    fun lastSeenAt(view: DeviceSessionView) = view.session.lastSeenAt

    @SchemaMapping(typeName = "DeviceSession", field = "current")
    fun current(view: DeviceSessionView): Boolean =
        view.currentFamilyId != null && view.session.familyId == view.currentFamilyId
}
