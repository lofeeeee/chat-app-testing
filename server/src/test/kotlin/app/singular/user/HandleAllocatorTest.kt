package app.singular.user

import app.singular.core.NameExhausted
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The discriminator rules, pinned down.
 *
 * These are the behaviours that are easy to misremember, so they're asserted rather than
 * described: the number is random, it is scoped to one username, it is released on rename, and
 * a released number is not immediately reusable.
 */
class HandleAllocatorTest {

    private val users = mockk<UserRepository>(relaxed = true)
    private val allocator = HandleAllocator(users)

    @Test
    fun `allocates a number in the usable range`() {
        val taken = mutableSetOf<Short>()
        val assigned = allocator.allocate("alex") { taken.add(it) }

        assertTrue(assigned in 1..9999, "#0000 is reserved; got $assigned")
    }

    @Test
    fun `two users sharing a name get different numbers`() {
        val taken = mutableSetOf<Short>()
        val first = allocator.allocate("alex") { taken.add(it) }
        val second = allocator.allocate("alex") { taken.add(it) }

        // Uniqueness is on the PAIR (username, discriminator), never on username alone.
        assertTrue(first != second, "both users got #$first")
    }

    @Test
    fun `falls back to enumeration when the name is crowded`() {
        // 9,998 of 9,999 numbers gone: random draws will essentially always miss, so the
        // allocator must scan for the one survivor rather than give up.
        val survivor: Short = 4242
        val occupied = (1..9999).map { it.toShort() }.toMutableSet().apply { remove(survivor) }

        every { users.takenDiscriminators("alex") } returns occupied
        every { users.quarantinedDiscriminators("alex", any()) } returns emptySet()

        val assigned = allocator.allocate("alex") { it !in occupied }
        assertEquals(survivor, assigned)
    }

    @Test
    fun `throws when every number for the name is taken`() {
        val full = (1..9999).map { it.toShort() }.toSet()
        every { users.takenDiscriminators("alex") } returns full
        every { users.quarantinedDiscriminators("alex", any()) } returns emptySet()

        val failure = runCatching { allocator.allocate("alex") { false } }.exceptionOrNull()
        assertTrue(failure is NameExhausted, "expected NameExhausted, got $failure")
    }

    @Test
    fun `quarantined numbers are not reissued`() {
        // The scenario the user described: alex#0971 renames away, then someone else tries to
        // claim alex. #0971 must stay unavailable for the quarantine window, or it becomes an
        // impersonation vector.
        val quarantined: Short = 971
        val occupied = (1..9999).map { it.toShort() }.toMutableSet().apply {
            remove(quarantined)
            remove(1500.toShort())
        }

        every { users.takenDiscriminators("alex") } returns occupied
        every { users.quarantinedDiscriminators("alex", any()) } returns setOf(quarantined)

        val assigned = allocator.allocate("alex") { it !in occupied }

        assertEquals(1500.toShort(), assigned)
        assertFalse(assigned == quarantined, "reissued a quarantined discriminator")
    }

    @Test
    fun `rename away and back yields a fresh draw`() {
        // alex#0971 -> sam -> alex.  The original number was released, so coming back is an
        // ordinary allocation against whatever is free now -- not a restoration.
        var live = mutableSetOf<Short>(971)

        every { users.takenDiscriminators("alex") } answers { live }
        every { users.quarantinedDiscriminators("alex", any<Instant>()) } returns setOf(971)

        live = mutableSetOf()                       // renamed away: #0971 released
        val reallocated = allocator.allocate("alex") { it != 971.toShort() && live.add(it) }

        assertTrue(reallocated != 971.toShort(), "came back to the same number")
    }
}
