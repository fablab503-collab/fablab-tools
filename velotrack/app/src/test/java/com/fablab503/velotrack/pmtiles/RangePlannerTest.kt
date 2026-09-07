package com.fablab503.velotrack.pmtiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangePlannerTest {

    @Test
    fun emptyInputGivesEmptyOutput() {
        assertTrue(RangePlanner.coalesce(emptyList()).isEmpty())
    }

    @Test
    fun singleRangeIsReturnedAsIs() {
        assertEquals(listOf(10L..20L), RangePlanner.coalesce(listOf(10L..20L)))
    }

    @Test
    fun mergesWhenGapFitsTheBudget() {
        val input = listOf(0L..99L, 110L..199L)      // payload 190, gap 10
        assertEquals(listOf(0L..199L), RangePlanner.coalesce(input, overfetch = 0.1))
    }

    @Test
    fun keepsSeparateWhenGapExceedsTheBudget() {
        val input = listOf(0L..99L, 110L..199L)      // budget 1 byte
        assertEquals(input, RangePlanner.coalesce(input, overfetch = 0.01))
    }

    @Test
    fun adjacentRangesAlwaysMerge() {
        val input = listOf(0L..99L, 100L..199L, 200L..299L)
        assertEquals(listOf(0L..299L), RangePlanner.coalesce(input, overfetch = 0.0))
    }

    @Test
    fun smallestGapsAreClosedFirst() {
        val input = listOf(0L..99L, 200L..299L, 305L..399L)   // payload 300, budget 15, gaps 100 and 5
        assertEquals(listOf(0L..99L, 200L..399L), RangePlanner.coalesce(input, overfetch = 0.05))
    }

    @Test
    fun respectsMaxRange() {
        val input = listOf(0L..99L, 100L..199L)
        assertEquals(input, RangePlanner.coalesce(input, overfetch = 1.0, maxRange = 150L))
        assertEquals(listOf(0L..199L), RangePlanner.coalesce(input, overfetch = 1.0, maxRange = 200L))
    }

    @Test
    fun maxRangeIsCheckedAgainstTheWholeGroup() {
        // Three adjacent 100-byte ranges, cap 250: the first two merge, the third would make 300.
        val input = listOf(0L..99L, 100L..199L, 200L..299L)
        assertEquals(listOf(0L..199L, 200L..299L), RangePlanner.coalesce(input, overfetch = 1.0, maxRange = 250L))
    }

    @Test
    fun defaultCapIsEightMebibytes() {
        val six = 6L shl 20
        val input = listOf(0L until six, six until 2 * six)
        assertEquals(input, RangePlanner.coalesce(input))
        val two = 2L shl 20
        assertEquals(listOf(0L until 2 * two), RangePlanner.coalesce(listOf(0L until two, two until 2 * two)))
    }

    @Test
    fun oversizedInputRangeIsKeptUnsplit() {
        val big = 0L..(20L shl 20)
        assertEquals(listOf(big), RangePlanner.coalesce(listOf(big, (30L shl 20)..(31L shl 20)), overfetch = 0.0).take(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsortedInput() {
        RangePlanner.coalesce(listOf(100L..199L, 0L..99L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlappingInput() {
        RangePlanner.coalesce(listOf(0L..99L, 99L..199L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyRange() {
        RangePlanner.coalesce(listOf(10L..9L))
    }
}
