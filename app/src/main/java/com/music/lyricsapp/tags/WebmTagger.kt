package com.music.lyricsapp.tags

import com.music.lyricsapp.core.WORD_LYRICS_FIELD
import com.music.lyricsapp.StandardLyricsField
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Appends standard Matroska lyrics after validating the EBML structure. */
object WebmTagger {

    private val ID_TAGS = byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67)
    private val ID_TAG = byteArrayOf(0x73, 0x73)
    private val ID_TARGETS = byteArrayOf(0x63, 0xC0.toByte())
    private val ID_SIMPLETAG = byteArrayOf(0x67, 0xC8.toByte())
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
    private val ID_ATTACHMENTS = byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69)
    private val ID_ATTACHEDFILE = byteArrayOf(0x61, 0xA7.toByte())
    private val ID_FILENAME = byteArrayOf(0x46, 0x6E)
    private val ID_FILEDESCRIPTION = byteArrayOf(0x46, 0x7E)
    private val ID_FILEMIMETYPE = byteArrayOf(0x46, 0x60)
    private val ID_FILEDATA = byteArrayOf(0x46, 0x5C)
    private val ID_FILEUID = byteArrayOf(0x46, 0xAE.toByte())

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String,
        cover: ByteArray?,
        coverMime: String,
        /** The A2 form, under a name of this app's own — see [WORD_LYRICS_FIELD]. */
        wordLyrics: String? = null,
    ): ByteArray {
        StandardLyricsField.requireLyrics(lyrics)
        return runCatching {
            val tail = buildTail(title, artist, album, lyrics, cover, coverMime, wordLyrics)
            val layout = WebmLayout.inspect(bytes)
            val newSize = layout.sizeAfter(tail.size.toLong())
            require(bytes.size.toLong() + tail.size <= Int.MAX_VALUE) { "WebM is too large for byte-array tagging" }
            val out = bytes.copyOf(bytes.size + tail.size)
            tail.copyInto(out, bytes.size)
            layout.oldLyricNames.forEach { out[it.toInt()] = 'X'.code.toByte() }
            if (!layout.unknownSize) writeVint(out, layout.sizeAt.toInt(), newSize, layout.sizeWidth)
            out
        }.getOrDefault(bytes)
    }

    /** Copies audio and attachments in chunks; only validated tag names and Segment size change. */
    fun tagFile(
        source: File, target: File, title: String, artist: String, album: String?,
        lyrics: String, cover: ByteArray?, coverMime: String, wordLyrics: String? = null,
    ) {
        StandardLyricsField.requireLyrics(lyrics)
        require(source.canonicalFile != target.canonicalFile) { "Source and target must differ" }
        val tail = buildTail(title, artist, album, lyrics, cover, coverMime, wordLyrics)
        RandomAccessFile(source, "r").use { input ->
            val layout = WebmLayout.inspect(input)
            val newSize = layout.sizeAfter(tail.size.toLong())
            FileOutputStream(target).use { output ->
                input.seek(0)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.write(tail)
                output.fd.sync()
            }
            RandomAccessFile(target, "rw").use { output ->
                layout.oldLyricNames.forEach { at ->
                    output.seek(at)
                    output.writeByte('X'.code)
                }
                if (!layout.unknownSize) {
                    val sizeBytes = ByteArray(layout.sizeWidth)
                    writeVint(sizeBytes, 0, newSize, layout.sizeWidth)
                    output.seek(layout.sizeAt)
                    output.write(sizeBytes)
                }
                output.fd.sync()
            }
        }
    }

    private fun buildTail(
        title: String,
        artist: String,
        album: String?,
        lyrics: String,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String?,
    ): ByteArray {
        var out = ByteArray(0)

        val simple = mutableListOf<ByteArray>()
        if (title.isNotBlank()) simple += simpleTag("TITLE", title)
        if (artist.isNotBlank()) simple += simpleTag("ARTIST", artist)
        if (!album.isNullOrBlank()) simple += simpleTag("ALBUM", album)
        // `LYRICS` is Matroska's own name for the field, and `TagString` is a
        // UTF-8 element with an explicit length — so the LRC's newlines need no
        // escaping and there is no ceiling worth worrying about here.
        simple += simpleTag(StandardLyricsField.MATROSKA.fieldName, lyrics)
        // Beside `LYRICS`, never instead of it: a SimpleTag with a name a
        // player doesn't know is skipped, so the portable field is untouched.
        if (!wordLyrics.isNullOrBlank()) simple += simpleTag(WORD_LYRICS_FIELD, wordLyrics)
        if (simple.isNotEmpty()) {
            // An empty Targets applies the tag to the whole file — there is no
            // track/chapter to single out in a single audio stream.
            val targets = elem(ID_TARGETS, ByteArray(0))
            val tagPayload = simple.fold(targets) { acc, s -> acc + s }
            out += elem(ID_TAGS, elem(ID_TAG, tagPayload))
        }

        if (cover != null && cover.isNotEmpty()) {
            val fileName = elem(ID_FILENAME, "cover.jpg".toByteArray(Charsets.UTF_8))
            val description = elem(ID_FILEDESCRIPTION, "Cover (front)".toByteArray(Charsets.UTF_8))
            val fileMime = elem(ID_FILEMIMETYPE, coverMime.toByteArray(Charsets.US_ASCII))
            // A fixed id is fine: one attachment, and nothing here needs to
            // reference it back from a Tag.
            val fileUid = elem(ID_FILEUID, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
            val fileData = elem(ID_FILEDATA, cover)
            val attachedFile = elem(ID_ATTACHEDFILE, fileName + description + fileMime + fileUid + fileData)
            out += elem(ID_ATTACHMENTS, attachedFile)
        }

        return out
    }

    private fun simpleTag(name: String, value: String): ByteArray {
        val nameElem = elem(ID_TAGNAME, name.toByteArray(Charsets.US_ASCII))
        val stringElem = elem(ID_TAGSTRING, value.toByteArray(Charsets.UTF_8))
        return elem(ID_SIMPLETAG, nameElem + stringElem)
    }

    private fun elem(id: ByteArray, payload: ByteArray): ByteArray =
        id + encodeVint(payload.size.toLong()) + payload

    /** The narrowest vint that fits [value], reserving the all-ones value as "unknown". */
    private fun encodeVint(value: Long): ByteArray {
        var width = 1
        while (width < 8 && value > (1L shl (7 * width)) - 2) width++
        return ByteArray(width).also { writeVint(it, 0, value, width) }
    }

    private fun writeVint(bytes: ByteArray, offset: Int, value: Long, width: Int) {
        var v = value
        for (i in width - 1 downTo 0) {
            bytes[offset + i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        bytes[offset] = (bytes[offset].toInt() or (0x80 shr (width - 1))).toByte()
    }

}
