package app.singular.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure logic added alongside the audit fixes: day dividers, day-key grouping, and the
 * searchEmoji fast path. All deterministic, all date-arithmetic — exactly the kind of code
 * that silently breaks (leap years, year boundaries, prefix-vs-contains ordering) without
 * a build-time check.
 */
class DayDividerTest {

    @Test
    fun labelForTodayAndYesterday() {
        assertEquals("Today", dayDividerLabel("2026-03-04T10:00:00Z", todayIso = "2026-03-04"))
        assertEquals("Yesterday", dayDividerLabel("2026-03-03T23:59:59Z", todayIso = "2026-03-04"))
    }

    @Test
    fun labelForOlderDatesIncludesTheYearOnlyWhenDifferent() {
        assertEquals("4 March", dayDividerLabel("2026-03-04T10:00:00Z", todayIso = "2026-09-01"))
        assertEquals("31 December 2025", dayDividerLabel("2025-12-31T10:00:00Z", todayIso = "2026-09-01"))
    }

    @Test
    fun monthBoundariesRollBackward() {
        assertEquals("1 March", dayDividerLabel("2026-03-01T00:00:00Z", todayIso = "2026-03-05"))
    }

    @Test
    fun dayBeforeHandlesMonthAndYearRolls() {
        assertEquals("2026-02-28", dayBefore("2026-03-01"))
        // 2024 is a leap year; 2026/2025 are not.
        assertEquals("2024-02-29", dayBefore("2024-03-01"))
        assertEquals("2025-02-28", dayBefore("2025-03-01"))
        assertEquals("2025-12-31", dayBefore("2026-01-01"))
        assertEquals("2026-01-31", dayBefore("2026-02-01"))
    }

    @Test
    fun dayKeyIsTheDatePart() {
        assertEquals("2026-03-04", dayKey("2026-03-04T10:00:00.123Z"))
    }

    @Test
    fun groupingMarksDayBreaksIndependentlyOfRunBreaks() {
        val messages = listOf(
            message("1", "alice", "2026-03-03T23:58:00Z"),
            // Same author, 3 minutes later — same run, but across midnight: a new day.
            message("2", "alice", "2026-03-04T00:01:00Z"),
        )
        val grouped = groupMessages(messages, selfId = null)

        assertTrue(grouped[0].startsDay, "first message always starts a day")
        assertTrue(grouped[1].startsDay, "midnight breaks the day even mid-run")
        assertFalse(grouped[1].startsGroup, "3-minute gap stays within the author run")
    }

    @Test
    fun groupingKeepsRunBreaksWhenSameDay() {
        val messages = listOf(
            message("1", "alice", "2026-03-04T10:00:00Z"),
            message("2", "alice", "2026-03-04T10:01:00Z"),
            // Same day, different author — run breaks, day doesn't.
            message("3", "bob", "2026-03-04T10:02:00Z"),
        )
        val grouped = groupMessages(messages, selfId = null)

        assertFalse(grouped[1].startsDay)
        assertTrue(grouped[2].startsDay.not(), "same calendar day, no divider")
        assertTrue(grouped[2].startsGroup, "different author breaks the run")
    }

    private fun message(id: String, author: String, createdAt: String) =
        app.singular.client.net.MessageDto(
            id = id,
            channelId = "1",
            author = app.singular.client.net.UserDto(
                id = author,
                username = author,
                discriminator = 0,
                handle = "$author#0000",
            ),
            content = "m$id",
            createdAt = createdAt,
        )
}
