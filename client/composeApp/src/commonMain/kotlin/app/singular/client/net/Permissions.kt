package app.singular.client.net

/**
 * The permission flags, mirroring the server's `Permission` enum.
 *
 * **The bit numbers are a wire contract.** They are stored in every role row and every channel
 * overwrite on the server; renumbering one here would silently change what the client thinks a
 * role can do. Append only, and only ever in step with `server/.../guild/Permissions.kt`.
 *
 * Only the flags the client actually gates UI on are listed. The server has more (voice,
 * moderation, text-channel extras); when one of those needs a control, add it here at its
 * real bit — never renumber to fill a gap.
 */
enum class Permission(val bit: Int, val label: String) {
    /** Grants everything and bypasses every channel overwrite. Hand out sparingly. */
    ADMINISTRATOR(0, "Administrator"),
    MANAGE_GUILD(2, "Manage server"),
    MANAGE_ROLES(3, "Manage roles"),
    MANAGE_CHANNELS(4, "Manage channels"),
    CREATE_INVITE(5, "Create invite"),
    MANAGE_INVITES(6, "Manage invites"),
    KICK_MEMBERS(10, "Kick members"),
    MODERATE_MEMBERS(12, "Time out members"),
    CHANGE_NICKNAME(13, "Change own nickname"),
    MANAGE_NICKNAMES(14, "Manage others' nicknames"),
}

/**
 * A parsed permission bitfield, as the server hands it to the viewer.
 *
 * The wire form is a 128-bit number serialised as a *decimal string* — [GuildDto.myPermissions]
 * carries the same warning. `java.math.BigInteger` is not available in `commonMain`, and parsing
 * into any fixed-width numeric type would corrupt the high bits, so this does arithmetic on the
 * digits themselves: halving the decimal string until the interesting bit lands in the ones
 * digit is exactly `testBit`, done in base 10.
 */
class GuildPermissions private constructor(private val decimal: String) {

    /** Administrator implies everything, checked before any individual flag. */
    fun allows(flag: Permission): Boolean = has(Permission.ADMINISTRATOR) || has(flag)

    fun has(flag: Permission): Boolean = testBit(flag.bit)

    /**
     * `true` when bit [bit] is set.
     *
     * Halving the decimal string `bit` times shifts the interesting bit into the ones digit,
     * whose parity is then the bit itself. Digits are mutated in place, so the check costs one
     * small buffer however long the field is — and bits past the end of the value are simply
     * zero, which is why an empty permission set answers `false` for everything.
     */
    private fun testBit(bit: Int): Boolean {
        val digits = decimal.toCharArray()
        repeat(bit) {
            var carry = 0
            for (i in digits.indices) {
                val value = carry * 10 + (digits[i] - '0')
                digits[i] = ('0' + value / 2)
                carry = value % 2
            }
        }
        return (digits.last() - '0') % 2 == 1
    }

    companion object {
        private val NONE = GuildPermissions("0")

        fun of(value: String?): GuildPermissions {
            // The server promises a decimal string. If it ever isn't one, treat it as no
            // permissions rather than guessing — a garbage field should never widen access.
            if (value.isNullOrEmpty() || value.any { it !in '0'..'9' }) return NONE
            return GuildPermissions(value)
        }
    }
}

/** The viewer's permissions in this server. Never the raw decimal string. */
val GuildDto.myPermissionSet: GuildPermissions
    get() = GuildPermissions.of(myPermissions)
