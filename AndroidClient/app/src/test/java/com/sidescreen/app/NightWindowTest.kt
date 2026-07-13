package com.sidescreen.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightWindowTest {
    @Test
    fun parseHhMm_basicAndInvalid() {
        assertEquals(22 * 60, NightWindow.parseHhMm("22:00"))
        assertEquals(7 * 60 + 5, NightWindow.parseHhMm("07:05"))
        assertEquals(9 * 60 + 30, NightWindow.parseHhMm("9:30"))
        assertEquals(0, NightWindow.parseHhMm(null))
        assertEquals(0, NightWindow.parseHhMm(""))
        assertEquals(0, NightWindow.parseHhMm("bad"))
    }

    @Test
    fun formatHhMm_roundTrip() {
        assertEquals("22:00", NightWindow.formatHhMm(22 * 60))
        assertEquals("07:05", NightWindow.formatHhMm(7 * 60 + 5))
        assertEquals("00:00", NightWindow.formatHhMm(0))
    }

    @Test
    fun sameDayWindow_insideAndOutside() {
        val start = 9 * 60
        val end = 17 * 60
        assertTrue(NightWindow.isInside(9 * 60, start, end))
        assertTrue(NightWindow.isInside(12 * 60, start, end))
        assertTrue(NightWindow.isInside(16 * 60 + 59, start, end))
        assertFalse(NightWindow.isInside(17 * 60, start, end))
        assertFalse(NightWindow.isInside(8 * 60 + 59, start, end))
        assertFalse(NightWindow.isInside(20 * 60, start, end))
    }

    @Test
    fun crossMidnightWindow_insideAndOutside() {
        val start = 22 * 60
        val end = 7 * 60
        assertTrue(NightWindow.isInside(22 * 60, start, end))
        assertTrue(NightWindow.isInside(23 * 60 + 30, start, end))
        assertTrue(NightWindow.isInside(0, start, end))
        assertTrue(NightWindow.isInside(6 * 60 + 59, start, end))
        assertFalse(NightWindow.isInside(7 * 60, start, end))
        assertFalse(NightWindow.isInside(12 * 60, start, end))
        assertFalse(NightWindow.isInside(21 * 60 + 59, start, end))
    }

    @Test
    fun equalStartEnd_neverInside() {
        assertFalse(NightWindow.isInside(0, 0, 0))
        assertFalse(NightWindow.isInside(12 * 60, 8 * 60, 8 * 60))
    }

    @Test
    fun nextBoundary_sameDay_outsideBeforeStart() {
        val start = 9 * 60
        val end = 17 * 60
        // 08:00:00 → 09:00 = 60 minutes
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 8 * 60,
                nowSecondsInMinute = 0,
                nowMillisInSecond = 0,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals(60 * 60_000L, delay)
    }

    @Test
    fun nextBoundary_sameDay_insideBeforeEnd() {
        val start = 9 * 60
        val end = 17 * 60
        // 12:30:00 → 17:00 = 4h 30m
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 12 * 60 + 30,
                nowSecondsInMinute = 0,
                nowMillisInSecond = 0,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals((4 * 60 + 30) * 60_000L, delay)
    }

    @Test
    fun nextBoundary_subtractsElapsedSeconds() {
        val start = 9 * 60
        val end = 17 * 60
        // 08:00:30.500 → 09:00 is 59_500 ms less than 60 min
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 8 * 60,
                nowSecondsInMinute = 30,
                nowMillisInSecond = 500,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals(60 * 60_000L - 30_500L, delay)
    }

    @Test
    fun nextBoundary_crossMidnight_insideTargetsEnd() {
        val start = 22 * 60
        val end = 7 * 60
        // 23:00 → 07:00 next day = 8 hours
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 23 * 60,
                nowSecondsInMinute = 0,
                nowMillisInSecond = 0,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals(8 * 60 * 60_000L, delay)
    }

    @Test
    fun nextBoundary_crossMidnight_outsideTargetsStart() {
        val start = 22 * 60
        val end = 7 * 60
        // 12:00 → 22:00 = 10 hours
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 12 * 60,
                nowSecondsInMinute = 0,
                nowMillisInSecond = 0,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals(10 * 60 * 60_000L, delay)
    }

    @Test
    fun nextBoundary_afterEnd_crossMidnight_wrapsToStart() {
        val start = 22 * 60
        val end = 7 * 60
        // 07:00 just left window → next start 22:00 same day = 15 hours
        val delay =
            NightWindow.millisUntilNextBoundary(
                nowMinutes = 7 * 60,
                nowSecondsInMinute = 0,
                nowMillisInSecond = 0,
                startMinutes = start,
                endMinutes = end,
            )
        assertEquals(15 * 60 * 60_000L, delay)
    }
}
