package com.music.lyricsapp

import kotlin.math.abs
import kotlin.math.max

/** A deliberately conservative estimate. Provider agreement is required for automatic writes. */
object LyricConfidence {
    const val AUTO_WRITE_THRESHOLD = 80
    data class Score(
        val total: Int, val metadata: Int, val duration: Int,
        val agreement: Int, val timing: Int,
    )

    fun score(track: LocalTrack, candidate: ProviderCandidate, candidates: List<ProviderCandidate>): Score {
        if (candidate.state != "found" || candidate.plain.isBlank() || candidate.review == "wrong") {
            return Score(0, 0, 0, 0, 0)
        }
        val stem = track.name.substringBeforeLast('.')
        val metadata = when {
            track.title.isBlank() || track.artist.isBlank() -> 0
            stem.contains(track.title, ignoreCase = true) && stem.contains(track.artist, ignoreCase = true) -> 25
            else -> 15
        }
        val times = Regex("""\[(\d{1,3}):(\d{2})[.:](\d{2,3})]""")
            .findAll(candidate.plain).map { match ->
                val fraction = match.groupValues[3]
                match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 +
                    fraction.toLong() * if (fraction.length == 2) 10 else 1
            }.toList()
        val increasing = times.zipWithNext().all { (a, b) -> b > a }
        val timing = when {
            times.size >= 4 && increasing -> 20
            times.size >= 2 && increasing -> 10
            else -> 0
        }
        val last = times.lastOrNull() ?: 0L
        val difference = abs(track.durationMs - last)
        val tolerance = max(15_000L, track.durationMs / 10)
        val duration = when {
            track.durationMs <= 0 || last <= 0 || !increasing -> 0
            difference <= tolerance -> 25
            difference <= max(30_000L, track.durationMs / 5) -> 10
            else -> 0
        }
        val words = normalized(candidate.plain)
        val agreement = if (words.isNotBlank() && candidates.any {
                it.source != candidate.source && it.state == "found" && normalized(it.plain) == words
            }) 30 else 0
        return Score(metadata + duration + agreement + timing, metadata, duration, agreement, timing)
    }

    fun canAutoWrite(track: LocalTrack, candidate: ProviderCandidate, candidates: List<ProviderCandidate>): Boolean =
        track.existing.isBlank() && track.proposed == candidate.plain &&
            track.review != "wrong" && candidate.review != "wrong" &&
            score(track, candidate, candidates).total >= AUTO_WRITE_THRESHOLD

    private fun normalized(lyrics: String): String = lyrics
        .replace(Regex("""\[[^]]+]"""), "")
        .lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
