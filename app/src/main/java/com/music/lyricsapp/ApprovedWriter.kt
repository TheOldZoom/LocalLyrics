package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.music.lyricsapp.tags.Mp4Tagger
import com.music.lyricsapp.tags.WebmTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object ApprovedWriter {
    data class Outcome(val message: String, val uri: String, val name: String,
        val signature: String, val replaced: Boolean, val backupUri: String = "")
    /** Returns the actual outcome; a provider that cannot rename leaves a clearly named copy. */
    suspend fun apply(context: Context, track: LocalTrack): Outcome = withContext(Dispatchers.IO) {
        require(track.status == "ready" && track.proposed.isNotBlank()) { "No reviewed lyrics to write" }
        StandardLyricsField.requireLyrics(track.proposed)
        val root = DocumentFile.fromTreeUri(context, Uri.parse(LibrarySettings.folder(context)))
            ?: error("Choose the music folder again")
        val (parent, original) = locate(root, track.uri) ?: error("File is no longer in the chosen folder")
        require("${original.length()}:${original.lastModified()}:${original.name}" == track.signature) {
            "This file changed since the scan; scan again before writing lyrics"
        }
        val extension = track.name.substringAfterLast('.', "").lowercase()
        require(extension in setOf("mp3", "flac", "m4a", "mp4", "webm")) { "Unsupported audio format" }
        require(extension != "mp4" || original.type?.startsWith("audio/") == true) {
            "Only audio MP4 files can be tagged"
        }
        StandardLyricsField.forExtension(extension)
        val input = File.createTempFile("lyric-original-", ".$extension", context.cacheDir)
        val tagged = File.createTempFile("lyric-tagged-", ".$extension", context.cacheDir)
        try {
            context.contentResolver.openInputStream(original.uri)?.use { source ->
                input.outputStream().use { target -> source.copyTo(target) }
            } ?: error("Could not read the audio file")
            require("${original.length()}:${original.lastModified()}:${original.name}" == track.signature) {
                "This file changed while it was being copied; scan again before writing lyrics"
            }
            require(StandardLyricsReader.read(input, extension) == track.existing.ifBlank { null }) {
                "Embedded lyrics changed since the scan; scan again before writing"
            }
            require(!StandardLyricsReader.hasPrivateOnly(input, extension)) {
                "Private timing exists without a standard lyrics field; this file needs manual repair"
            }
            when (extension) {
                "flac" -> FlacLyricsWriter.write(input, tagged, track.proposed, track.enhanced)
                "mp3" -> Mp3Lyrics.write(input, tagged, track.proposed)
                "m4a", "mp4" -> Mp4Tagger.tagFile(input, tagged, "", "", null, track.proposed,
                    null, false, track.enhanced.ifBlank { null })
                else -> WebmTagger.tagFile(input, tagged, "", "", null, track.proposed,
                    null, "image/jpeg", track.enhanced.ifBlank { null })
            }
            require(StandardLyricsReader.read(tagged, extension) == track.proposed) {
                "Tagged file failed standard lyric verification"
            }

            val mime = when (extension) { "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "m4a", "mp4" -> "audio/mp4"; else -> "audio/webm" }
            val candidateName = "${track.name.substringBeforeLast('.')}.tagged-${UUID.randomUUID()}.$extension"
            val candidate = parent.createFile(mime, candidateName)
                ?: error("Could not create a tagged file in this folder")
            context.contentResolver.openOutputStream(candidate.uri, "wt")?.use { target ->
                tagged.inputStream().use { it.copyTo(target, 64 * 1024) }
            } ?: error("Could not write the tagged file")
            require(candidate.length() == tagged.length() && sameBytes(context, candidate.uri, tagged)) {
                "Folder provider did not retain the complete tagged file"
            }
            if (!verify(context, candidate.uri, track.proposed, extension)) {
                error("Folder provider did not retain the tagged lyrics")
            }

            val backupName = "${track.name}.lyrics-backup-${UUID.randomUUID()}"
            if (!original.renameTo(backupName)) return@withContext Outcome(
                "Tagged copy saved as ${candidate.name}; original unchanged", candidate.uri.toString(),
                candidate.name ?: candidateName,
                "${candidate.length()}:${candidate.lastModified()}:${candidate.name ?: candidateName}", false)
            if (!sameBytes(context, original.uri, input)) {
                original.renameTo(track.name)
                error("Folder provider did not retain the original backup; check Needs attention")
            }
            val renamed = try { candidate.renameTo(track.name) }
                catch (failure: Exception) {
                    original.renameTo(track.name)
                    throw failure
                }
            if (!renamed) {
                val restored = original.renameTo(track.name)
                return@withContext Outcome(if (restored)
                    "Tagged copy saved as ${candidate.name}; original restored"
                else "Tagged copy saved as ${candidate.name}; original remains at ${original.name}",
                    candidate.uri.toString(), candidate.name ?: candidateName,
                    "${candidate.length()}:${candidate.lastModified()}:${candidate.name ?: candidateName}", false)
            }
            require(candidate.length() == tagged.length() && sameBytes(context, candidate.uri, tagged) &&
                verify(context, candidate.uri, track.proposed, extension)) {
                "Renamed file failed verification; original retained as ${original.name}"
            }
            Outcome("Updated ${track.name}; original retained as ${original.name}", candidate.uri.toString(),
                candidate.name ?: track.name,
                "${candidate.length()}:${candidate.lastModified()}:${candidate.name ?: track.name}", true,
                original.uri.toString())
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
        return RawEmbeddedLyrics.read(context, uri, extension) == plain
    }

    private fun sameBytes(context: Context, uri: Uri, file: File): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val expected = file.inputStream().use { stream ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
            digest.digest()
        }
        digest.reset()
        val actual = context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
            digest.digest()
        } ?: return false
        return MessageDigest.isEqual(expected, actual)
    }
}
