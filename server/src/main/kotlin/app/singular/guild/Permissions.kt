package app.singular.guild

import java.math.BigInteger

/**
 * A 128-bit permission set.
 *
 * Backed by [BigInteger] rather than a Long because 64 bits is not enough headroom. Discord
 * shipped 53-bit-safe integers, outgrew them, moved to 64, outgrew that too, and ended up
 * re-serialising permissions as strings in a live public API. Starting at 128 costs nothing
 * and leaves roughly a hundred spare flags.
 *
 * Crosses the wire as a decimal **string** for the same reason snowflakes do: no JSON parser
 * on a 53-bit-float platform can hold this value, and it would corrupt silently.
 */
@JvmInline
value class Permissions(val bits: BigInteger) {

    infix fun has(flag: Permission): Boolean = bits.testBit(flag.bit)

    /** Administrator implies everything. Checked before overwrites, never after. */
    fun allows(flag: Permission): Boolean =
        bits.testBit(Permission.ADMINISTRATOR.bit) || bits.testBit(flag.bit)

    infix fun or(other: Permissions) = Permissions(bits.or(other.bits))
    infix fun and(other: Permissions) = Permissions(bits.and(other.bits))

    /** `this AND NOT other` — the deny step of an overwrite. */
    infix fun without(other: Permissions) = Permissions(bits.andNot(other.bits))

    fun with(flag: Permission) = Permissions(bits.setBit(flag.bit))
    fun withOut(flag: Permission) = Permissions(bits.clearBit(flag.bit))

    val isEmpty: Boolean get() = bits.signum() == 0

    /** Zero-padded to 128 characters, which is what `bit(128)` requires on the way in. */
    fun toBitString(): String = bits.toString(2).padStart(WIDTH, '0')

    override fun toString(): String = bits.toString()

    companion object {
        const val WIDTH = 128

        val NONE = Permissions(BigInteger.ZERO)

        /** Every flag that exists — what an owner or administrator resolves to. */
        val ALL = Permissions(
            Permission.entries.fold(BigInteger.ZERO) { acc, p -> acc.setBit(p.bit) }
        )

        fun of(vararg flags: Permission) = Permissions(
            flags.fold(BigInteger.ZERO) { acc, p -> acc.setBit(p.bit) }
        )

        /** From the `bit(128)` column, which JDBC hands back as a string of '0' and '1'. */
        fun fromBitString(value: String?): Permissions =
            if (value.isNullOrEmpty()) NONE else Permissions(BigInteger(value, 2))

        /** From the decimal string clients send. */
        fun parse(value: String?): Permissions =
            if (value.isNullOrBlank()) NONE else Permissions(BigInteger(value))

        /**
         * What a brand-new @everyone role gets.
         *
         * Deliberately conservative: read, write, react, and nothing that changes the shape of
         * the server. Handing @everyone anything management-shaped by default is how servers
         * get wrecked by their tenth member.
         */
        val DEFAULT_EVERYONE = of(
            Permission.VIEW_CHANNEL,
            Permission.SEND_MESSAGES,
            Permission.READ_MESSAGE_HISTORY,
            Permission.ADD_REACTIONS,
            Permission.ATTACH_FILES,
            Permission.EMBED_LINKS,
            Permission.CREATE_INVITE,
            Permission.CHANGE_NICKNAME,
            Permission.CONNECT_VOICE,
            Permission.SPEAK,
        )
    }
}

/**
 * Permission flags.
 *
 * **Bit numbers are permanent.** They are stored in every role row and every channel
 * overwrite; renumbering one silently changes what existing roles can do. Append only.
 */
enum class Permission(val bit: Int, val label: String) {

    // -- General -------------------------------------------------------------
    /** Grants everything and bypasses every channel overwrite. Hand out sparingly. */
    ADMINISTRATOR(0, "Administrator"),
    VIEW_AUDIT_LOG(1, "View audit log"),
    MANAGE_GUILD(2, "Manage server"),
    MANAGE_ROLES(3, "Manage roles"),
    MANAGE_CHANNELS(4, "Manage channels"),
    CREATE_INVITE(5, "Create invite"),
    MANAGE_INVITES(6, "Manage invites"),
    MANAGE_EMOJI(7, "Manage emoji and stickers"),
    MANAGE_WEBHOOKS(8, "Manage webhooks"),

    // -- Membership ----------------------------------------------------------
    KICK_MEMBERS(10, "Kick members"),
    BAN_MEMBERS(11, "Ban members"),
    /** Timeouts. Separate from kick/ban because it is the reversible one. */
    MODERATE_MEMBERS(12, "Time out members"),
    CHANGE_NICKNAME(13, "Change own nickname"),
    MANAGE_NICKNAMES(14, "Manage others' nicknames"),

    // -- Text channels -------------------------------------------------------
    VIEW_CHANNEL(20, "View channel"),
    SEND_MESSAGES(21, "Send messages"),
    SEND_MESSAGES_IN_THREADS(22, "Send messages in threads"),
    CREATE_THREADS(23, "Create threads"),
    EMBED_LINKS(24, "Embed links"),
    ATTACH_FILES(25, "Attach files"),
    ADD_REACTIONS(26, "Add reactions"),
    USE_EXTERNAL_EMOJI(27, "Use external emoji"),
    MENTION_EVERYONE(28, "Mention @everyone and @here"),
    MANAGE_MESSAGES(29, "Manage messages"),
    READ_MESSAGE_HISTORY(30, "Read message history"),
    PIN_MESSAGES(31, "Pin messages"),
    SEND_VOICE_MESSAGES(32, "Send voice messages"),
    ATTACH_LOCATION(33, "Share location"),

    // -- Voice ---------------------------------------------------------------
    CONNECT_VOICE(40, "Connect to voice"),
    SPEAK(41, "Speak"),
    MUTE_MEMBERS(42, "Mute members"),
    DEAFEN_MEMBERS(43, "Deafen members"),
    MOVE_MEMBERS(44, "Move members"),
    STREAM(45, "Share screen"),
    ;

    companion object {
        fun byName(name: String): Permission? = entries.firstOrNull { it.name == name }
    }
}
