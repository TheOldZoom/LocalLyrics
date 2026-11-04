package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.music.lyricsapp.core.LrcLib
import com.music.lyricsapp.core.toLrc
import com.music.lyricsapp.tags.Mp4Tagger
import com.music.lyricsapp.tags.WebmTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object ApprovedWriter {
    data class Outcome(val message: String, val uri: String, val signature: String, val replaced: Boolean)
    /** Returns the actual outcome; a provider that cannot rename leaves a clearly named copy. */
    suspend fun apply(context: Context, track: LocalTrack): Outcome = withContext(Dispatchers.IO) {
        require(track.status == "ready" && track.proposed.isNotBlank()) { "No reviewed lyrics to write" }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(LibrarySettings.folder(context)))
            ?: error("Choose the music folder again")
        val (parent, original) = locate(root, track.uri) ?: error("File is no longer in the chosen folder")
        require("${original.length()}:${original.lastModified()}:${original.name}" == track.signature) {
            "This file changed since the scan; scan again before writing lyrics"
        }
        val extension = track.name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("mp3", "flac", "m4a", "webm")) { "Unsupported audio format" }
        val input = File.createTempFile("lyric-original-", ".$extension", context.cacheDir)
        val tagged = File.createTempFile("lyric-tagged-", ".$extension", context.cacheDir)
        try {
            context.contentResolver.openInputStream(original.uri)?.use { source ->
                input.outputStream().use { target -> source.copyTo(target) }
            } ?: error("Could not read the audio file")
            when (extension) {
                "flac" -> FlacLyricsWriter.write(input, tagged, track.proposed, track.enhanced)
                "mp3" -> {
                    require(input.length() <= 64L * 1024 * 1024) { "This MP3 is too large to rewrite in memory" }
                    tagged.writeBytes(Mp3Lyrics.write(input.readBytes(), track.proposed))
                }
                else -> {
                    require(input.length() <= 64L * 1024 * 1024) { "This file is too large for the M4A/WebM tagger" }
                    val originalBytes = input.readBytes()
                    val output = if (extension == "m4a") {
                        Mp4Tagger.tag(originalBytes, "", "", null, track.proposed, null, false,
                            track.enhanced.ifBlank { null })
                    } else {
                        WebmTagger.tag(originalBytes, "", "", null, track.proposed, null, "image/jpeg",
                            track.enhanced.ifBlank { null })
                    }
                    require(!output.contentEquals(originalBytes)) { "The file's metadata layout is not supported" }
                    tagged.writeBytes(output)
                }
            }
            require(verify(context, Uri.fromFile(tagged), track.proposed, extension)) { "Tagged file failed lyric verification" }

            val mime = when (extension) { "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a" -> "audio/mp4"; else -> "audio/webm" }
            val candidateName = "${track.name.substringBeforeLast('.')}.tagged-${UUID.randomUUID()}.$extension"
            val candidate = parent.createFile(mime, candidateName)
                ?: error("Could not create a tagged file in this folder")
            context.contentResolver.openOutputStream(candidate.uri, "wt")?.use { target ->
                tagged.inputStream().use { it.copyTo(target, 64 * 1024) }
            } ?: error("Could not write the tagged file")
            if (!verify(context, candidate.uri, track.proposed, extension)) {
                candidate.delete()
                error("Folder provider did not retain the tagged lyrics")
            }

            val backupName = "${track.name}.lyrics-backup-${UUID.randomUUID()}"
            if (!original.renameTo(backupName)) return@withContext Outcome(
                "Tagged copy saved as ${candidate.name}; original unchanged", candidate.uri.toString(),
                "${candidate.length()}:${candidate.lastModified()}:${candidate.name}", false)
            if (!candidate.renameTo(track.name)) {
                val restored = original.renameTo(track.name)
                return@withContext Outcome(if (restored)
                    "Tagged copy saved as ${candidate.name}; original restored"
                else "Tagged copy saved as ${candidate.name}; original remains at ${original.name}",
                    candidate.uri.toString(), "${candidate.length()}:${candidate.lastModified()}:${candidate.name}", false)
            }
            if (!original.delete()) {
                return@withContext Outcome("Updated ${track.name}; backup retained as ${original.name}",
                    candidate.uri.toString(), "${candidate.length()}:${candidate.lastModified()}:${candidate.name}", true)
            }
            Outcome("Updated ${track.name}", candidate.uri.toString(),
                "${candidate.length()}:${candidate.lastModified()}:${candidate.name}", true)
        } finally {
            input.delete(); tagged.delete()
        }
    }

    private fun locate(folder: DocumentFile, uri: String): Pair<DocumentFile, DocumentFile>? {
        for (child in folder.listFiles()) {
            if (child.uri.toString() == uri) return folder to child
            if (child.isDirectory) locate(child, uri)?.let { return it }
        }
        return null
    }

    private suspend fun verify(context: Context, uri: Uri, plain: String, extension: String): Boolean {
        val raw = RawEmbeddedLyrics.read(context, uri, extension)?.trim() ?: return false
        return raw == plain.trim() || LrcLib.parseLrc(raw).toLrc().trim() == plain.trim()
    }
}
