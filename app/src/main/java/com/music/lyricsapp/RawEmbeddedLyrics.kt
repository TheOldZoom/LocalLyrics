package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A failed inspection must never look like "no embedded lyrics". */
object RawEmbeddedLyrics {
    suspend fun read(context: Context, uri: Uri, extension: String): String? =
        inspect(context, uri, extension, standardOnly = true)

    suspend fun readPreferred(context: Context, uri: Uri, extension: String): String? =
        inspect(context, uri, extension, standardOnly = false)

    private suspend fun inspect(context: Context, uri: Uri, extension: String, standardOnly: Boolean): String? =
        withContext(Dispatchers.IO) {
        val copy = File.createTempFile("lyrics-read-", ".$extension", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                copy.outputStream().use { target -> source.copyTo(target, 64 * 1024) }
            } ?: error("Could not read audio file")
            if (standardOnly) {
                val standard = StandardLyricsReader.read(copy, extension)
                require(standard != null || !StandardLyricsReader.hasPrivateOnly(copy, extension)) {
                    "Private timing exists without a standard lyrics field"
                }
                standard
            } else StandardLyricsReader.readPreferred(copy, extension)
        } finally { copy.delete() }
    }
}
