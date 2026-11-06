package com.music.lyricsapp

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** A blank value clears that field; unchanged values are never passed to the tag library. */
internal data class MetadataValues(
    val title: String = "", val artist: String = "", val albumArtist: String = "",
    val album: String = "", val genre: String = "", val year: String = "",
    val trackNumber: String = "", val discNumber: String = "", val composer: String = "",
    val comment: String = "", val lyrics: String = "",
) {
    fun fields(): List<Pair<FieldKey, String>> = listOf(
        FieldKey.TITLE to title, FieldKey.ARTIST to artist,
        FieldKey.ALBUM_ARTIST to albumArtist, FieldKey.ALBUM to album,
        FieldKey.GENRE to genre, FieldKey.YEAR to year, FieldKey.TRACK to trackNumber,
        FieldKey.DISC_NO to discNumber, FieldKey.COMPOSER to composer,
        FieldKey.COMMENT to comment, FieldKey.LYRICS to lyrics)

    fun value(key: FieldKey): String = fields().first { it.first == key }.second
}

internal data class MetadataSnapshot(val values: MetadataValues, val coverDigest: String?,
                                     val coverPreview: Bitmap? = null)

internal data class SelectedArtwork(val bytes: ByteArray, val mimeType: String,
                                    val sourceName: String, val preview: Bitmap,
                                    val width: Int, val height: Int)

internal object MetadataWriter {
    val editableExtensions = setOf("mp3", "flac", "m4a", "mp4")

    data class Prepared(
        val track: LocalTrack, val original: File, val candidate: File,
        val before: MetadataSnapshot, val after: MetadataSnapshot,
        val extension: String, val mediaRecord: String? = null,
    )

    fun extension(track: LocalTrack): String = track.name.substringAfterLast('.', "").lowercase()

    fun artworkFromImage(context: Context, uri: Uri): SelectedArtwork {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(10 * 1024 * 1024) }
            ?: error("Could not read selected artwork")
        return selectedArtwork(bytes, displayName(context, uri))
    }

    /** Reads only the chosen audio file. Its embedded image is staged in memory until Save. */
    fun artworkFromAudio(context: Context, uri: Uri): SelectedArtwork {
        val name = displayName(context, uri)
        val retriever = MediaMetadataRetriever()
        val platformBytes = try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (_: Exception) { null } finally { retriever.release() }
        val bytes = platformBytes ?: run {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in editableExtensions) null else {
                val copy = File.createTempFile("artwork-source-", ".$ext", context.cacheDir)
                try {
                    copyFromUri(context, uri, copy)
                    AudioFileIO.read(copy).tag?.firstArtwork?.binaryData
                } finally { copy.delete() }
            }
        } ?: error("The selected audio file has no embedded cover art")
        return selectedArtwork(bytes, name)
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().ifBlank { "Selected file" }

    private fun selectedArtwork(bytes: ByteArray, sourceName: String): SelectedArtwork {
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "Artwork is larger than 10 MB or empty" }
        val mime = when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
            else -> error("Choose a file with JPEG or PNG cover art")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..10000 && bounds.outHeight in 1..10000) {
            "Choose cover art no larger than 10,000 pixels per side"
        }
        val preview = thumbnail(bytes) ?: error("Could not display selected cover art")
        return SelectedArtwork(bytes, mime, sourceName, preview, bounds.outWidth, bounds.outHeight)
    }

    fun editable(context: Context, track: LocalTrack): Boolean {
        val ext = extension(track)
        return ext in editableExtensions && (ext != "mp4" ||
            context.contentResolver.getType(Uri.parse(track.uri))?.startsWith("audio/") == true)
    }

    fun snapshot(context: Context, track: LocalTrack): MetadataSnapshot {
        val ext = extension(track)
        if (!editable(context, track)) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(track.uri))
                fun get(key: Int) = retriever.extractMetadata(key).orEmpty()
                val values = MetadataValues(
                    get(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { track.title },
                    get(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { track.artist },
                    get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    get(MediaMetadataRetriever.METADATA_KEY_ALBUM).ifBlank { track.album },
                    get(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    get(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                    get(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                    get(MediaMetadataRetriever.METADATA_KEY_COMPOSER), "", track.existing)
                val artwork = retriever.embeddedPicture
                return MetadataSnapshot(values, artwork?.let {
                    hex(MessageDigest.getInstance("SHA-256").digest(it)) }, artwork?.let(::thumbnail))
            } catch (_: Exception) {
                return MetadataSnapshot(MetadataValues(track.title, track.artist,
                    album = track.album, lyrics = track.existing), null)
            } finally { retriever.release() }
        }
        val copy = File.createTempFile("metadata-read-", ".$ext", context.cacheDir)
        try {
            copyFromUri(context, Uri.parse(track.uri), copy)
            return read(copy)
        } finally { copy.delete() }
    }

    fun prepare(context: Context, track: LocalTrack, expected: MetadataSnapshot,
                edited: MetadataValues, cover: SelectedArtwork?, removeCover: Boolean = false): Prepared {
        require(editable(context, track)) { "This format cannot be safely edited" }
        val ext = extension(track)
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "metadata_recovery").apply { mkdirs() }
        val original = File(dir, "$id-original.$ext")
        val candidate = File(dir, "$id-candidate.$ext")
        try {
            copyFromUri(context, Uri.parse(track.uri), original)
            val before = read(original)
            require(before.values == expected.values && before.coverDigest == expected.coverDigest) {
                "Track details changed while editing; reopen the editor"
            }
            validate(edited)
            if (edited.lyrics != before.values.lyrics) require(!StandardLyricsReader.hasPrivateOnly(original, ext)) {
                "Private timing exists without portable lyrics; repair this file before editing its lyrics"
            }
            original.copyTo(candidate, overwrite = false)
            val audioBefore = audioDigest(original, ext)
            val audio = AudioFileIO.read(candidate)
            val tag = audio.tag ?: audio.createDefaultTag()
            edited.fields().forEach { (key, value) ->
                if (before.values.value(key) != value) {
                    if (value.isEmpty()) tag.deleteField(key) else tag.setField(key, value)
                }
            }
            var selectedArtworkDigest: String? = null
            if (removeCover) tag.deleteArtworkField()
            if (cover != null) {
                val bytes = cover.bytes
                require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "Invalid selected artwork" }
                selectedArtworkDigest = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
                tag.deleteArtworkField()
                tag.setField(AndroidArtwork().apply {
                    binaryData = bytes; mimeType = cover.mimeType; pictureType = 3
                    width = cover.width; height = cover.height
                })
            }
            audio.commit()
            require(audioDigest(candidate, ext).contentEquals(audioBefore)) {
                "The tagged copy changed the audio payload"
            }
            val after = read(candidate)
            edited.fields().forEach { (key, expected) ->
                require(after.values.value(key).trim() == expected.trim()) {
                    "Tagged copy failed ${key.name.lowercase()} verification"
                }
            }
            if (removeCover) require(after.coverDigest == null) {
                "Tagged copy still contains artwork"
            } else if (cover == null) require(after.coverDigest == before.coverDigest) {
                "Tagged copy changed existing artwork"
            } else require(after.coverDigest == selectedArtworkDigest) {
                "Tagged copy failed artwork verification"
            }
            if (edited.lyrics.isNotBlank()) require(StandardLyricsReader.read(candidate, ext) == edited.lyrics) {
                "Tagged copy has no portable lyrics field"
            }
            verifyUnchangedFields(original, candidate, before.values, edited, cover != null || removeCover)
            if (ext == "mp3") verifyId3v1(original, candidate, before.values, edited)
            // The same values are checked again after the approved destination write.
            val record = if (MediaLibrary.isIndexed(track.uri)) MediaRecovery.prepare(context, id,
                track.uri, track.name, ext, original, candidate) else null
            return Prepared(track, original, candidate, before, after, ext, record)
        } catch (e: Exception) {
            original.delete(); candidate.delete()
            throw e
        }
    }

    fun commitMedia(context: Context, prepared: Prepared): String {
        val record = requireNotNull(prepared.mediaRecord)
        val uri = Uri.parse(prepared.track.uri)
        if (!shaUri(context, uri).contentEquals(shaFile(prepared.original))) {
            discard(context, prepared)
            error("Audio changed while the edit was being reviewed; prepare it again")
        }
        MediaRecovery.markWriting(context, record)
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
                FileOutputStream(descriptor.fileDescriptor).use { output ->
                    prepared.candidate.inputStream().use { it.copyTo(output, 64 * 1024) }
                    output.fd.sync()
                }
            } ?: error("Could not open approved audio for writing")
            require(shaUri(context, uri).contentEquals(shaFile(prepared.candidate))) {
                "Written file differs from the verified candidate"
            }
            MediaRecovery.markBackup(context, record)
            prepared.candidate.delete()
            if (Build.VERSION.SDK_INT >= 29) runCatching { context.contentResolver.notifyChange(uri, null) }
            return "Saved ${prepared.track.name}; original kept in Needs attention"
        } catch (e: Exception) {
            MediaRecovery.markRestoreNeeded(context, record)
            throw IllegalStateException("Save could not be verified. Restore the original from Needs attention. ${e.message.orEmpty()}", e)
        }
    }

    fun commitSaf(context: Context, prepared: Prepared): String {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(LibrarySettings.folder(context)))
            ?: error("Choose the music folder again")
        val (parent, source) = locate(root, prepared.track.uri)
            ?: error("File is no longer in the chosen folder")
        require(shaUri(context, source.uri).contentEquals(shaFile(prepared.original))) {
            "Audio changed while the edit was being reviewed; prepare it again"
        }
        val candidateName = "${prepared.track.name.substringBeforeLast('.')}.tagged-${UUID.randomUUID()}.${prepared.extension}"
        val candidate = parent.createFile(source.type ?: "audio/*", candidateName)
            ?: error("Folder provider could not create a tagged copy")
        context.contentResolver.openOutputStream(candidate.uri, "wt")?.use { output ->
            prepared.candidate.inputStream().use { it.copyTo(output, 64 * 1024) }
        } ?: error("Folder provider could not write the tagged copy")
        require(shaUri(context, candidate.uri).contentEquals(shaFile(prepared.candidate))) {
            "Tagged copy is incomplete; check Needs attention"
        }
        val backupName = "${prepared.track.name}.lyrics-backup-${UUID.randomUUID()}"
        require(source.renameTo(backupName)) { "Original could not be renamed; tagged copy retained in Needs attention" }
        require(shaUri(context, source.uri).contentEquals(shaFile(prepared.original))) {
            "Original backup is incomplete; check Needs attention"
        }
        if (!candidate.renameTo(prepared.track.name)) {
            source.renameTo(prepared.track.name)
            error("Tagged copy could not take the original name; check Needs attention")
        }
        require(shaUri(context, candidate.uri).contentEquals(shaFile(prepared.candidate))) {
            "Renamed file failed verification; original retained in Needs attention"
        }
        prepared.original.delete(); prepared.candidate.delete()
        return "Saved ${prepared.track.name}; original retained as $backupName"
    }

    fun discard(context: Context, prepared: Prepared) {
        prepared.mediaRecord?.let { MediaRecovery.discardPrepared(context, it) }
        prepared.original.delete(); prepared.candidate.delete()
    }

    private fun read(file: File): MetadataSnapshot {
        val tag = AudioFileIO.read(file).tag
        fun get(key: FieldKey) = tag?.getFirst(key).orEmpty()
        val values = MetadataValues(get(FieldKey.TITLE), get(FieldKey.ARTIST),
            get(FieldKey.ALBUM_ARTIST), get(FieldKey.ALBUM), get(FieldKey.GENRE),
            get(FieldKey.YEAR), get(FieldKey.TRACK), get(FieldKey.DISC_NO),
            get(FieldKey.COMPOSER), get(FieldKey.COMMENT), get(FieldKey.LYRICS))
        val artwork = tag?.firstArtwork?.binaryData
        return MetadataSnapshot(values,
            artwork?.let { hex(MessageDigest.getInstance("SHA-256").digest(it)) },
            artwork?.let(::thumbnail))
    }

    private fun thumbnail(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun validate(values: MetadataValues) {
        values.fields().forEach { (key, value) ->
            require(value.length <= if (key == FieldKey.LYRICS) 64_000 else 2_000) {
                "${key.name.lowercase()} is too long"
            }
        }
        listOf(values.year, values.trackNumber, values.discNumber).forEach { raw ->
            require(raw.isBlank() || raw.toIntOrNull()?.let { it in 1..9999 } == true) {
                "Year, track, and disc must be positive numbers"
            }
        }
    }

    private fun verifyUnchangedFields(original: File, candidate: File,
                                      before: MetadataValues, edited: MetadataValues,
                                      coverChanged: Boolean) {
        val first = AudioFileIO.read(original).tag
        val second = AudioFileIO.read(candidate).tag
        val changed = edited.fields().filter { (key, value) -> before.value(key) != value }
            .map { it.first } + if (coverChanged) listOf(FieldKey.COVER_ART) else emptyList()
        fun Tag?.fingerprints(): MutableList<String> = this?.fields?.asSequence()?.map { field ->
            val digest = MessageDigest.getInstance("SHA-256").digest(field.rawContent)
            "${field.id}:${hex(digest)}"
        }?.toMutableList() ?: mutableListOf()
        fun Tag?.unchanged(): List<String> {
            val all = fingerprints()
            changed.forEach { key ->
                this?.getFields(key).orEmpty().forEach { field ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(field.rawContent)
                    all.remove("${field.id}:${hex(digest)}")
                }
            }
            return all.sorted()
        }
        require(first.unchanged() == second.unchanged()) {
            "Tagged copy changed an unrelated metadata field"
        }
    }

    private fun verifyId3v1(original: File, candidate: File,
                            before: MetadataValues, edited: MetadataValues) {
        fun trailer(file: File): ByteArray? = RandomAccessFile(file, "r").use { input ->
            if (input.length() < 128) return@use null
            input.seek(input.length() - 128)
            val bytes = ByteArray(128).also { input.readFully(it) }
            bytes.takeIf { String(it, 0, 3, Charsets.US_ASCII) == "TAG" }
        }
        val old = trailer(original)
        val new = trailer(candidate)
        require((old == null) == (new == null)) { "Tagged copy changed the ID3v1 tag" }
        if (old == null || new == null) return
        val fieldRanges = listOf(
            Triple(before.title == edited.title, 3, 33),
            Triple(before.artist == edited.artist, 33, 63),
            Triple(before.album == edited.album, 63, 93),
            Triple(before.year == edited.year, 93, 97),
            Triple(before.comment == edited.comment, 97, 125),
            Triple(before.trackNumber == edited.trackNumber, 125, 127),
            Triple(before.genre == edited.genre, 127, 128),
        )
        fieldRanges.forEach { (unchanged, from, to) ->
            if (unchanged) require(old.copyOfRange(from, to).contentEquals(new.copyOfRange(from, to))) {
                "Tagged copy changed an unrelated ID3v1 field"
            }
        }
    }

    private fun locate(folder: DocumentFile, uri: String): Pair<DocumentFile, DocumentFile>? {
        for (child in folder.listFiles()) {
            if (child.uri.toString() == uri) return folder to child
            if (child.isDirectory) locate(child, uri)?.let { return it }
        }
        return null
    }

    private fun copyFromUri(context: Context, uri: Uri, file: File) {
        context.contentResolver.openInputStream(uri)?.use { source ->
            FileOutputStream(file).use { output ->
                source.copyTo(output, 64 * 1024); output.fd.sync()
            }
        } ?: error("Could not read the selected audio")
        require(file.length() > 0) { "Selected audio is empty" }
    }

    internal fun shaFile(file: File): ByteArray = file.inputStream().use(::digest)
    internal fun shaUri(context: Context, uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use(::digest) ?: error("Could not verify audio")

    private fun digest(stream: java.io.InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val used = stream.read(buffer)
            if (used < 0) break
            hash.update(buffer, 0, used)
        }
        return hash.digest()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Hashes MPEG frames, FLAC frames, or MP4 mdat contents without reading a whole file into RAM. */
    private fun audioDigest(file: File, extension: String): ByteArray = RandomAccessFile(file, "r").use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        fun range(start: Long, end: Long) {
            require(start >= 0 && end > start && end <= input.length()) { "Invalid audio payload" }
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var left = end - start
            while (left > 0) {
                val used = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                require(used > 0) { "Truncated audio payload" }
                hash.update(buffer, 0, used); left -= used
            }
        }
        when (extension) {
            "mp3" -> {
                val header = ByteArray(10)
                input.readFully(header)
                var start = 0L
                if (String(header, 0, 3, Charsets.US_ASCII) == "ID3") {
                    require((6..9).all { header[it].toInt() and 0x80 == 0 }) { "Invalid ID3 size" }
                    start = 10 + ((header[6].toLong() and 127) shl 21) +
                        ((header[7].toLong() and 127) shl 14) +
                        ((header[8].toLong() and 127) shl 7) + (header[9].toLong() and 127)
                    if (header[5].toInt() and 0x10 != 0) start += 10
                }
                var end = input.length()
                if (end >= 128) {
                    input.seek(end - 128)
                    val footer = ByteArray(3).also { input.readFully(it) }
                    if (String(footer, Charsets.US_ASCII) == "TAG") end -= 128
                }
                range(start, end)
            }
            "flac" -> {
                val magic = ByteArray(4).also { input.readFully(it) }
                require(String(magic, Charsets.US_ASCII) == "fLaC") { "Invalid FLAC" }
                var last = false
                while (!last) {
                    val flags = input.readUnsignedByte()
                    val size = (input.readUnsignedByte() shl 16) or
                        (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                    last = flags and 0x80 != 0
                    require(input.filePointer + size <= input.length()) { "Truncated FLAC metadata" }
                    input.seek(input.filePointer + size)
                }
                range(input.filePointer, input.length())
            }
            "m4a", "mp4" -> {
                var at = 0L
                var found = false
                while (at + 8 <= input.length()) {
                    input.seek(at)
                    val size32 = input.readInt().toLong() and 0xffffffffL
                    val type = ByteArray(4).also { input.readFully(it) }
                    val header = if (size32 == 1L) 16L else 8L
                    val size = when (size32) { 0L -> input.length() - at; 1L -> input.readLong(); else -> size32 }
                    require(size >= header && size <= input.length() - at) { "Invalid MP4 box" }
                    if (String(type, Charsets.US_ASCII) == "mdat") {
                        range(at + header, at + size); found = true
                    }
                    at += size
                }
                require(found) { "No MP4 audio payload" }
            }
        }
        hash.digest()
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            require(out.size() + n <= max) { "Artwork is larger than 10 MB" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
