package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Recovery files are never library tracks and are never removed during scanning. */
object RecoveryManager {
    enum class Kind { BACKUP, CANDIDATE }
    data class Artifact(
        val uri: String, val name: String, val originalName: String,
        val kind: Kind, val size: Long, val modified: Long,
    )

    suspend fun list(context: Context): List<Artifact> = withContext(Dispatchers.IO) {
        val root = root(context)
        buildList {
            fun walk(folder: DocumentFile) {
                folder.listFiles().forEach { child ->
                    if (child.isDirectory) walk(child)
                    else if (child.isFile) classify(child)?.let(::add)
                }
            }
            walk(root)
        }.sortedWith(compareByDescending<Artifact> { it.modified }.thenBy { it.name })
    }

    suspend fun restore(context: Context, backupUri: String): String = withContext(Dispatchers.IO) {
        val (parent, backup) = locate(root(context), backupUri) ?: error("Backup is no longer available")
        val artifact = classify(backup)?.takeIf { it.kind == Kind.BACKUP }
            ?: error("This document is not a lyrics backup")
        require(backup.length() > 0 && looksLikeAudio(context, backup, artifact.originalName)) {
            "Backup does not appear to be a complete audio file"
        }
        val current = parent.findFile(artifact.originalName)
        require(current == null || current.isFile) {
            "A folder has the original filename; restore cannot proceed safely"
        }
        val candidateName = artifact.originalName.substringBeforeLast('.') +
            ".tagged-${UUID.randomUUID()}." + artifact.originalName.substringAfterLast('.')
        if (current != null && !current.renameTo(candidateName)) {
            error("Could not move the current file; backup remains unchanged")
        }
        if (!backup.renameTo(artifact.originalName)) {
            current?.renameTo(artifact.originalName)
            error("Could not restore the backup; both files were retained")
        }
        "Restored ${artifact.originalName}" +
            if (current != null) "; replaced version retained as ${current.name}" else ""
    }

    suspend fun delete(context: Context, artifactUri: String): String = withContext(Dispatchers.IO) {
        val (parent, file) = locate(root(context), artifactUri) ?: error("Recovery file is no longer available")
        val artifact = classify(file) ?: error("This document is not a recovery file")
        val current = parent.findFile(artifact.originalName)
        val currentUsable = current?.isFile == true && current.length() > 0 &&
            looksLikeAudio(context, current, artifact.originalName)
        val otherBackupUsable = artifact.kind == Kind.CANDIDATE && parent.listFiles().any { other ->
            if (other.uri == file.uri || !other.isFile) false
            else {
                val backup = classify(other)
                backup?.kind == Kind.BACKUP && backup.originalName == artifact.originalName &&
                    other.length() > 0 && looksLikeAudio(context, other, artifact.originalName)
            }
        }
        require(currentUsable || otherBackupUsable) {
            "Keep this recovery file: no other usable audio copy was found"
        }
        require(file.delete()) { "Folder provider could not delete ${artifact.name}" }
        "Deleted ${artifact.name}"
    }

    suspend fun cleanupExpired(context: Context): Int = withContext(Dispatchers.IO) {
        val days = LibrarySettings.backupDays(context)
        if (days <= 0) return@withContext 0
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        var removed = 0
        list(context).filter { it.kind == Kind.BACKUP && it.modified in 1 until cutoff }
            .forEach { artifact ->
                if (runCatching { delete(context, artifact.uri) }.isSuccess) removed++
            }
        removed
    }

    private fun classify(file: DocumentFile): Artifact? {
        val name = file.name ?: return null
        val backupAt = name.indexOf(".lyrics-backup-")
        if (backupAt > 0 && name.substring(backupAt + 15).isNotBlank()) {
            return Artifact(file.uri.toString(), name, name.substring(0, backupAt),
                Kind.BACKUP, file.length(), file.lastModified())
        }
        val match = Regex("^(.+)\\.tagged-[0-9a-fA-F-]{36}\\.([^.]+)$").matchEntire(name) ?: return null
        return Artifact(file.uri.toString(), name, "${match.groupValues[1]}.${match.groupValues[2]}",
            Kind.CANDIDATE, file.length(), file.lastModified())
    }

    private fun looksLikeAudio(context: Context, file: DocumentFile, originalName: String): Boolean {
        val head = context.contentResolver.openInputStream(file.uri)?.use { stream ->
            val bytes = ByteArray(16)
            var used = 0
            while (used < bytes.size) {
                val n = stream.read(bytes, used, bytes.size - used)
                if (n < 0) break
                used += n
            }
            bytes.copyOf(used)
        } ?: return false
        return when (originalName.substringAfterLast('.').lowercase()) {
            "mp3", "aac" -> head.take(3).toByteArray().contentEquals("ID3".toByteArray()) ||
                (head.size >= 2 && head[0] == 0xff.toByte() && (head[1].toInt() and 0xe0) == 0xe0)
            "flac" -> head.take(4).toByteArray().contentEquals("fLaC".toByteArray())
            "m4a", "mp4" -> head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp"
            "webm", "mkv" -> head.size >= 4 && head.take(4).toByteArray()
                .contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()))
            else -> false
        }
    }

    private fun root(context: Context): DocumentFile =
        DocumentFile.fromTreeUri(context, Uri.parse(LibrarySettings.folder(context)))
            ?: error("Choose the music folder again")

    private fun locate(folder: DocumentFile, uri: String): Pair<DocumentFile, DocumentFile>? {
        for (child in folder.listFiles()) {
            if (child.uri.toString() == uri) return folder to child
            if (child.isDirectory) locate(child, uri)?.let { return it }
        }
        return null
    }
}
