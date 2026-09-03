package app.singular.core

import app.singular.config.SingularProperties
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnowflakeTest {

    private fun generator(nodeId: Long = 7) = Snowflake(SingularProperties(nodeId = nodeId))

    @Test
    fun `ids are strictly increasing`() {
        val gen = generator()
        val ids = List(10_000) { gen.next() }
        assertEquals(ids, ids.sorted(), "snowflakes must sort chronologically")
        assertEquals(ids.size, ids.toSet().size, "snowflakes must be unique")
    }

    @Test
    fun `sequence rollover does not collide under contention`() {
        // 4096 ids per millisecond per node is the ceiling; past it the generator must wait for
        // the next tick rather than wrap into ids it has already handed out.
        val gen = generator()
        val pool = Executors.newFixedThreadPool(8)
        val tasks = List(8) { Callable { List(5_000) { gen.next() } } }

        val all = pool.invokeAll(tasks).flatMap { it.get() }
        pool.shutdown()

        assertEquals(40_000, all.toSet().size, "concurrent generation produced duplicates")
    }

    @Test
    fun `id round-trips its own timestamp`() {
        val before = Instant.now().toEpochMilli()
        val id = generator().next()
        val after = Instant.now().toEpochMilli()

        val extracted = Snowflake.timestampOf(id).toEpochMilli()
        assertTrue(extracted in before..after, "embedded timestamp $extracted outside [$before, $after]")
    }

    @Test
    fun `node id is recoverable`() {
        assertEquals(511, Snowflake.nodeOf(generator(nodeId = 511).next()))
    }

    @Test
    fun `floorFor bounds a time window`() {
        val gen = generator()
        val cutoff = Instant.now()
        val floor = Snowflake.floorFor(cutoff)
        val laterId = gen.next()

        assertTrue(laterId > floor, "ids minted after a cutoff must exceed its floor")
    }

    @Test
    fun `node id outside the 10-bit range is rejected`() {
        val failure = runCatching { generator(nodeId = 1024) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "expected rejection, got $failure")
    }
}
