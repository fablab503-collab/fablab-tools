package com.fablab503.velotrack.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class StatsRepositoryTest {
    private val paris: ZoneId = ZoneId.of("Europe/Paris")

    private fun parisMs(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(paris).toInstant().toEpochMilli()

    @Test
    fun startOfWeekOnAMondayIsThatMidnight() {
        // 2026-09-07 is a Monday; France starts the week on Monday.
        val now = parisMs(2026, 9, 7, 12, 0)
        assertEquals(parisMs(2026, 9, 7), StatsRepository.startOfWeekMs(now, paris, Locale.FRANCE))
    }

    @Test
    fun startOfWeekOnASundayGoesBackToMonday() {
        // 2026-09-13 is a Sunday.
        val now = parisMs(2026, 9, 13, 23, 59)
        assertEquals(parisMs(2026, 9, 7), StatsRepository.startOfWeekMs(now, paris, Locale.FRANCE))
    }

    @Test
    fun startOfWeekFollowsLocaleFirstDay() {
        // The US starts the week on Sunday: Wednesday 2026-09-09 -> Sunday 2026-09-06.
        val now = parisMs(2026, 9, 9, 8, 30)
        assertEquals(parisMs(2026, 9, 6), StatsRepository.startOfWeekMs(now, paris, Locale.US))
        assertEquals(parisMs(2026, 9, 7), StatsRepository.startOfWeekMs(now, paris, Locale.FRANCE))
    }

    @Test
    fun startOfMonthIsTheFirstAtLocalMidnight() {
        val now = parisMs(2026, 9, 7, 12, 0)
        assertEquals(parisMs(2026, 9, 1), StatsRepository.startOfMonthMs(now, paris))
        // Midnight on the 1st already belongs to the new month; 23:59 on the 31st still to the old one.
        assertEquals(parisMs(2026, 9, 1), StatsRepository.startOfMonthMs(parisMs(2026, 9, 1, 0, 0), paris))
        assertEquals(parisMs(2026, 8, 1), StatsRepository.startOfMonthMs(parisMs(2026, 8, 31, 23, 59), paris))
    }

    @Test
    fun startOfYearCrossesDstOffsetChange() {
        // September is CEST (+02:00), January is CET (+01:00): the result must be Paris midnight, not UTC midnight.
        val now = parisMs(2026, 9, 7, 12, 0)
        val expected = parisMs(2026, 1, 1)
        assertEquals(expected, StatsRepository.startOfYearMs(now, paris))
        assertEquals(1_767_222_000_000L, expected) // 2025-12-31T23:00:00Z
    }

    @Test
    fun periodStartsAreNotAfterNow() {
        val now = parisMs(2026, 9, 7, 0, 0)
        assertTrue(StatsRepository.startOfWeekMs(now, paris, Locale.FRANCE) <= now)
        assertTrue(StatsRepository.startOfMonthMs(now, paris) <= now)
        assertTrue(StatsRepository.startOfYearMs(now, paris) <= now)
    }
}
