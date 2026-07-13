package com.sidescreen.app

/**
 * Pure night-window helpers (no Android framework deps) for unit tests and runtime.
 *
 * Times are minutes since local midnight in `[0, 1440)`.
 * Same-day windows: `[start, end)`. Cross-midnight: `[start, 1440) U [0, end)`.
 * When `start == end`, the window is treated as empty (never inside).
 */
object NightWindow {
    private const val MINUTES_PER_DAY = 24 * 60
    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MILLIS_PER_DAY = MINUTES_PER_DAY * MILLIS_PER_MINUTE

    /**
     * Parse `"HH:mm"` (or `"H:mm"`) into minutes since midnight.
     * Invalid input falls back to `0`.
     */
    fun parseHhMm(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        val parts = value.trim().split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return 0
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        return h * 60 + m
    }

    fun formatHhMm(minutes: Int): String {
        val normalized = ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val h = normalized / 60
        val m = normalized % 60
        return String.format("%02d:%02d", h, m)
    }

    fun isInside(
        nowMinutes: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        val now = normalizeMinutes(nowMinutes)
        val start = normalizeMinutes(startMinutes)
        val end = normalizeMinutes(endMinutes)
        if (start == end) return false
        return if (start < end) {
            now >= start && now < end
        } else {
            now >= start || now < end
        }
    }

    /**
     * Milliseconds from the given local time-of-day until the next window
     * boundary (start when outside, end when inside).
     *
     * When start == end, returns one full day (no meaningful boundary).
     * Result is always `> 0` for valid distinct boundaries.
     */
    fun millisUntilNextBoundary(
        nowMinutes: Int,
        nowSecondsInMinute: Int,
        nowMillisInSecond: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Long {
        val start = normalizeMinutes(startMinutes)
        val end = normalizeMinutes(endMinutes)
        if (start == end) return MILLIS_PER_DAY

        val nowMin = normalizeMinutes(nowMinutes)
        val sec = nowSecondsInMinute.coerceIn(0, 59)
        val ms = nowMillisInSecond.coerceIn(0, 999)
        val elapsedInMinuteMs = sec * 1000L + ms

        val target =
            if (isInside(nowMin, start, end)) {
                end
            } else {
                start
            }

        var deltaMinutes = target - nowMin
        if (deltaMinutes < 0) {
            deltaMinutes += MINUTES_PER_DAY
        } else if (deltaMinutes == 0) {
            // Exactly on the boundary minute: if still "inside" at end with
            // half-open [start, end), end means outside — target was start and
            // delta 0 only if now == start while outside (impossible) or
            // now == end while inside (impossible with < end). Guard full day.
            deltaMinutes = MINUTES_PER_DAY
        }

        val delay = deltaMinutes * MILLIS_PER_MINUTE - elapsedInMinuteMs
        return if (delay <= 0L) MILLIS_PER_DAY else delay
    }

    private fun normalizeMinutes(value: Int): Int =
        ((value % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
