package com.music.lyricsapp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Finds a YouTube Music recording for local files, which have no video ID tag. */
object YouTubeVideoLookup {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    suspend fun find(title: String, artist: String, durationMs: Long): String? {
        if (title.isBlank() || artist.isBlank()) return null
        val result = runCatching { Innertube.search("$title $artist") }.getOrNull() ?: return null
        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        return result.named("musicResponsiveListItemRenderer").firstNotNullOfOrNull { row ->
            val texts = row.youtubeStrings().map(::normalize)
            if (wantedTitle !in texts || wantedArtist !in texts) return@firstNotNullOfOrNull null
            val listedDuration = row.youtubeStrings().firstNotNullOfOrNull(::duration)
            if (durationMs > 0 && listedDuration != null &&
                kotlin.math.abs(listedDuration - durationMs) > 8_000L) return@firstNotNullOfOrNull null
            row.named("watchEndpoint").firstNotNullOfOrNull { endpoint ->
                (endpoint["videoId"] as? JsonPrimitive)?.contentOrNull?.takeIf(videoId::matches)
            }
        }
    }

    private fun normalize(text: String): String = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun duration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null }) return null
        return parts.fold(0L) { seconds, part -> seconds * 60 + part.toLong() } * 1000
    }

    private fun JsonElement.named(name: String): Sequence<JsonObject> = sequence {
        when (this@named) {
            is JsonObject -> for ((key, value) in this@named) {
                if (key == name && value is JsonObject) yield(value)
                yieldAll(value.named(name))
            }
            is JsonArray -> for (value in this@named) yieldAll(value.named(name))
            else -> Unit
        }
    }
}
