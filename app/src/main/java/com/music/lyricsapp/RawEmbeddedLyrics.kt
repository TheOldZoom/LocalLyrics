package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Reads the standard container field exposed by TagLib as `LYRICS`, including text without LRC stamps. */
object RawEmbeddedLyrics {
    suspend fun read(context: Context, uri: Uri, extension: String): String? = withContext(Dispatchers.IO) {
        val limit = if (extension == "flac" || extension == "mp3") 8 * 1024 * 1024 else 68 * 1024 * 1024
        context.contentResolver.openInputStream(uri)?.use { source ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val count = source.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            runCatching {
                if (extension == "mp3") Mp3Lyrics.read(out.toByteArray())
                else EmbeddedLyrics.fromBytesStandard(out.toByteArray())
            }.getOrNull()
        }
    }
}
