package com.music.lyricsapp

import android.content.Context
import com.music.lyricsapp.core.LyricsSource

/** Persisted provider-wide exponential backoff; a failing host does not block other sources. */
internal object ProviderHealth {
    private const val PREFS = "lyrics_provider_health"

    @Synchronized fun allowed(context: Context, source: LyricsSource, now: Long): Boolean =
        now >= context.getSharedPreferences(PREFS, 0).getLong("next_${source.name}", 0L)

    @Synchronized fun nextAllowed(context: Context, source: LyricsSource): Long =
        context.getSharedPreferences(PREFS, 0).getLong("next_${source.name}", 0L)

    @Synchronized fun success(context: Context, source: LyricsSource) {
        context.getSharedPreferences(PREFS, 0).edit()
            .remove("fail_${source.name}").remove("next_${source.name}").apply()
    }

    @Synchronized fun failure(context: Context, source: LyricsSource, now: Long) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val failures = (prefs.getInt("fail_${source.name}", 0) + 1).coerceAtMost(10)
        val exponential = 30_000L * (1L shl (failures - 1))
        val delay = (if (failures >= 3) maxOf(5 * 60_000L, exponential) else exponential)
            .coerceAtMost(60 * 60_000L)
        prefs.edit().putInt("fail_${source.name}", failures)
            .putLong("next_${source.name}", now + delay).apply()
    }
}
