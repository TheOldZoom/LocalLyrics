package com.music.lyricsapp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** The three YouTube Music calls needed by the lyrics providers. */
object Innertube {
    private const val base = "https://music.youtube.com/youtubei/v1"
    private const val version = "1.20250101.01.00"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun next(videoId: String): JsonObject = post("next", buildJsonObject {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    })

    suspend fun browse(browseId: String, params: String? = null): JsonObject = post("browse", buildJsonObject {
        put("browseId", browseId)
        if (params != null) put("params", params)
    })

    suspend fun search(query: String): JsonObject = post("search", buildJsonObject {
        put("query", query)
    })

    suspend fun transcript(videoId: String): JsonObject = post("get_transcript", buildJsonObject {
        val encoded = Base64.getEncoder().encodeToString(byteArrayOf(10, videoId.length.toByte()) + videoId.toByteArray())
        put("params", encoded)
    })

    private suspend fun post(endpoint: String, fields: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", version)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            fields.forEach { (key, value) -> put(key, value) }
        }
        val request = Request.Builder()
            .url("$base/$endpoint?prettyPrint=false&hl=en")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", version)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("YouTube lyrics HTTP ${response.code}")
            json.parseToJsonElement(response.body?.string().orEmpty()).let { it as? JsonObject
                ?: error("Invalid YouTube lyrics response") }
        }
    }
}
