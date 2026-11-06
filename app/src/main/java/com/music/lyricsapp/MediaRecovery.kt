package com.music.lyricsapp

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import android.util.Base64

/** Durable, app-private originals for writes through MediaStore. No interrupted write is resumed automatically. */
internal object MediaRecovery {
    private fun directory(context: Context) = File(context.filesDir, "metadata_recovery").apply { mkdirs() }
    private fun file(context: Context, id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "Invalid recovery ID" }
        return File(directory(context), "$id.json")
    }

    private fun read(file: File) = JSONObject(file.readText())

    private fun update(context: Context, id: String, state: String) {
        val source = file(context, id)
        val record = read(source).put("state", state)
        val temp = File(source.parentFile, "$id.tmp")
        FileOutputStream(temp).use { output ->
            output.write(record.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        require(temp.renameTo(source)) { "Could not update recovery record" }
    }

    fun prepare(context: Context, id: String, uri: String, name: String, extension: String,
                original: File, candidate: File): String {
        require(original.isFile && candidate.isFile)
        val record = JSONObject().put("id", id).put("uri", uri).put("name", name)
            .put("extension", extension).put("created", System.currentTimeMillis())
            .put("state", "prepared")
            .put("original_hash", Base64.encodeToString(MetadataWriter.shaFile(original), Base64.NO_WRAP))
            .put("candidate_hash", Base64.encodeToString(MetadataWriter.shaFile(candidate), Base64.NO_WRAP))
        val target = file(context, id)
        FileOutputStream(target).use { output ->
            output.write(record.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        return id
    }

    fun markWriting(context: Context, id: String) = update(context, id, "writing")
    fun markBackup(context: Context, id: String) = update(context, id, "backup")
    fun markRestoreNeeded(context: Context, id: String) = update(context, id, "restore_needed")

    fun discardPrepared(context: Context, id: String) {
        val record = file(context, id)
        if (record.exists() && read(record).optString("state") == "prepared") record.delete()
    }

    data class Entry(val id: String, val targetUri: String, val name: String,
                     val original: File, val state: String, val created: Long)

    fun entry(context: Context, id: String): Entry {
        val data = read(file(context, id))
        val ext = data.getString("extension")
        return Entry(id, data.getString("uri"), data.getString("name"),
            File(directory(context), "$id-original.$ext"), data.getString("state"),
            data.getLong("created"))
    }

    fun list(context: Context): List<Entry> = directory(context).listFiles().orEmpty()
        .asSequence().filter { it.name.matches(Regex("[0-9a-fA-F-]{36}\\.json")) }
        .mapNotNull { runCatching { entry(context, it.name.removeSuffix(".json")) }.getOrNull() }
        .filter { it.state != "prepared" }.toList()

    fun cleanupAbandonedPreparations(context: Context) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory(context).listFiles().orEmpty()
            .filter { it.name.matches(Regex("[0-9a-fA-F-]{36}\\.json")) }
            .forEach { record ->
                val entry = runCatching { entry(context, record.name.removeSuffix(".json")) }.getOrNull()
                    ?: return@forEach
                if (entry.state == "prepared" && entry.created < cutoff) {
                    entry.original.delete()
                    File(directory(context), "${entry.id}-candidate.${entry.name.substringAfterLast('.', "")}").delete()
                    record.delete()
                }
            }
    }

    fun restore(context: Context, id: String): String {
        val entry = entry(context, id)
        require(entry.original.isFile && entry.original.length() > 0) { "Recovery original is missing" }
        val expectedOriginal = Base64.decode(read(file(context, id)).getString("original_hash"), Base64.NO_WRAP)
        require(MetadataWriter.shaFile(entry.original).contentEquals(expectedOriginal)) {
            "Recovery original failed its checksum; no write was attempted"
        }
        markRestoreNeeded(context, id)
        val uri = Uri.parse(entry.targetUri)
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    entry.original.inputStream().use { it.copyTo(output, 64 * 1024) }
                    output.fd.sync()
                }
            } ?: error("Could not open approved audio for restoration")
            require(MetadataWriter.shaUri(context, uri).contentEquals(MetadataWriter.shaFile(entry.original))) {
                "Restored bytes did not match the original"
            }
            update(context, id, "restored")
            return "Restored ${entry.name}; original copy remains in Needs attention"
        } catch (e: Exception) {
            throw IllegalStateException("Restore was interrupted. The original copy remains available. ${e.message.orEmpty()}", e)
        }
    }

    fun delete(context: Context, id: String): String {
        val entry = entry(context, id)
        require(entry.state == "backup" || entry.state == "restored") {
            "Restore the original before deleting this recovery copy"
        }
        val uri = Uri.parse(entry.targetUri)
        val current = runCatching { MetadataWriter.shaUri(context, uri) }.getOrNull()
        val record = read(file(context, id))
        val expected = Base64.decode(record.getString(if (entry.state == "backup")
            "candidate_hash" else "original_hash"), Base64.NO_WRAP)
        require(current != null && current.contentEquals(expected)) {
            "Keep this recovery copy: the current audio cannot be verified"
        }
        require(entry.original.delete()) { "Could not delete recovery copy" }
        file(context, id).delete()
        return "Deleted recovery copy for ${entry.name}"
    }
}
