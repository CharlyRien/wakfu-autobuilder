package me.chosante.ui.state

import java.util.prefs.Preferences

/**
 * Remembers how long the OR-Tools warm-up took last time so the loading screen can show a
 * self-calibrating progress estimate and ETA. The native library load reports no real progress, so
 * a time-based estimate is the best we can do; persisting the measured duration makes it accurate
 * from the second launch onward. First launch (and any read/write failure) falls back to a default.
 */
object WarmupTiming {
    private const val KEY = "warmupDurationMs"
    private const val DEFAULT_MS = 10000L

    private val prefs: Preferences? = runCatching { Preferences.userRoot().node("me/chosante/wakfu-autobuilder") }.getOrNull()

    /** Best estimate of the upcoming warm-up duration, clamped to a sane range. */
    fun estimatedDurationMs(): Long = (runCatching { prefs?.getLong(KEY, DEFAULT_MS) }.getOrNull() ?: DEFAULT_MS).coerceIn(800L, 20_000L)

    /** Records the actually-measured warm-up duration for next launch. Ignores absurd values. */
    fun record(durationMs: Long) {
        if (durationMs in 1L..120_000L) {
            runCatching { prefs?.putLong(KEY, durationMs) }
        }
    }
}
