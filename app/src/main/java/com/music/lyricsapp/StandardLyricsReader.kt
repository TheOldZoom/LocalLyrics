package com.music.lyricsapp

import com.music.lyricsapp.tags.WebmLayout
import com.music.lyricsapp.core.WORD_LYRICS_FIELD
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Reads only metadata into memory, including when the audio payload is many gigabytes. */
internal object StandardLyricsReader {
    fun read(file: File, extension: String): String? = read(file, extension, true)

    fun readPreferred(file: File, extension: String): String? = read(file, extension, false)

    fun hasPrivateOnly(file: File, extension: String): Boolean {
        if (read(file, extension) != null) return false
        if (extension == "mp3" || extension == "aac") return mp3PrivateField(file)
        return readPreferred(file, extension) != null
    }

    private fun read(file: File, extension: String, standardOnly: Boolean): String? =
        RandomAccessFile(file, "r").use { input ->
        when (StandardLyricsField.forExtension(extension)) {
            StandardLyricsField.ID3 -> mp3(input)
            StandardLyricsField.VORBIS -> flac(input, standardOnly)
            StandardLyricsField.ITUNES -> mp4(input, standardOnly)
            StandardLyricsField.MATROSKA -> webm(input, standardOnly)
        }
    }

    private fun mp3(input: RandomAccessFile): String? {
        require(input.length() >= 4) { "Truncated MP3" }
        val header = ByteArray(10)
        input.readFully(header, 0, minOf(input.length(), 10L).toInt())
        if (String(header, 0, 3, Charsets.US_ASCII) != "ID3") return null
        val size = syncSafe(header, 6).toLong() + 10
        require(size <= 32L * 1024 * 1024 && size <= input.length()) { "Unsafe ID3 size" }
        input.seek(0)
        return Mp3Lyrics.read(ByteArray(size.toInt()).also { input.readFully(it) })
    }

    private fun flac(input: RandomAccessFile, standardOnly: Boolean): String? {
        require(input.length() >= 8) { "Truncated FLAC" }
        val magic = ByteArray(4).also { input.readFully(it) }
        require(String(magic, Charsets.US_ASCII) == "fLaC") { "Invalid FLAC header" }
        val metadata = ByteArrayOutputStream().apply { write(magic) }
        var last: Boolean
        do {
            require(input.filePointer + 4 <= input.length()) { "Truncated FLAC metadata" }
            val flags = input.readUnsignedByte()
            val size = (input.readUnsignedByte() shl 16) or
                (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            require(size <= 16 * 1024 * 1024 && metadata.size().toLong() + size + 4 <= 32L * 1024 * 1024) {
                "FLAC metadata is too large to inspect safely"
            }
            val block = ByteArray(size).also { input.readFully(it) }
            metadata.write(flags)
            metadata.write(size ushr 16); metadata.write(size ushr 8); metadata.write(size)
            metadata.write(block)
            last = flags and 0x80 != 0
        } while (!last)
        val bytes = metadata.toByteArray()
        return if (standardOnly) EmbeddedLyrics.fromBytesStandard(bytes) else EmbeddedLyrics.fromBytes(bytes)
    }

    private fun mp4(input: RandomAccessFile, standardOnly: Boolean): String? {
        var at = 0L
        require(input.length() >= 16) { "Truncated MP4" }
        while (at + 8 <= input.length()) {
            input.seek(at)
            val size32 = input.readInt().toLong() and 0xffffffffL
            val type = String(ByteArray(4).also { input.readFully(it) }, Charsets.ISO_8859_1)
            val header = if (size32 == 1L) 16L else 8L
            val size = when (size32) {
                0L -> input.length() - at
                1L -> input.readLong()
                else -> size32
            }
            require(size >= header && size <= input.length() - at) { "Invalid MP4 box size" }
            if (at == 0L) require(type == "ftyp") { "MP4 ftyp is missing" }
            if (type == "moov") {
                require(size <= 32L * 1024 * 1024) { "MP4 metadata is too large to inspect safely" }
                input.seek(at)
                val moov = ByteArray(size.toInt()).also { input.readFully(it) }
                val fakeFtyp = byteArrayOf(0, 0, 0, 16, 'f'.code.toByte(), 't'.code.toByte(),
                    'y'.code.toByte(), 'p'.code.toByte()) + ByteArray(8)
                val bytes = fakeFtyp + moov
                return if (standardOnly) EmbeddedLyrics.fromBytesStandard(bytes) else EmbeddedLyrics.fromBytes(bytes)
            }
            at += size
        }
        error("MP4 moov is missing")
    }

    private fun webm(input: RandomAccessFile, standardOnly: Boolean): String? {
        val layout = WebmLayout.inspect(input)
        for ((start, end) in layout.tagRanges) {
            val size = end - start
            require(size <= 8L * 1024 * 1024) { "WebM tags are too large to inspect safely" }
            input.seek(start)
            val tags = ByteArray(size.toInt()).also { input.readFully(it) }
            val bytes = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + tags
            (if (standardOnly) EmbeddedLyrics.fromBytesStandard(bytes) else EmbeddedLyrics.fromBytes(bytes))
                ?.let { return it }
        }
        return null
    }

    private fun mp3PrivateField(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 10) return@use false
        val header = ByteArray(10).also { input.readFully(it) }
        if (String(header, 0, 3, Charsets.US_ASCII) != "ID3") return@use false
        val version = header[3].toInt() and 255
        if (version !in 3..4) return@use false
        val end = syncSafe(header, 6).toLong() + 10
        require(end <= input.length() && end <= 32L * 1024 * 1024) { "Unsafe ID3 size" }
        var at = 10L
        while (at + 10 <= end) {
            input.seek(at)
            val frameHeader = ByteArray(10).also { input.readFully(it) }
            if (frameHeader[0] == 0.toByte()) break
            val id = String(frameHeader, 0, 4, Charsets.US_ASCII)
            val size = if (version == 4) syncSafe(frameHeader, 4).toLong() else
                ((frameHeader[4].toLong() and 255) shl 24) or
                    ((frameHeader[5].toLong() and 255) shl 16) or
                    ((frameHeader[6].toLong() and 255) shl 8) or
                    (frameHeader[7].toLong() and 255)
            require(size > 0 && size <= end - at - 10) { "Invalid ID3 frame size" }
            if (id == "TXXX" && size <= 64_000) {
                val payload = ByteArray(size.toInt()).also { input.readFully(it) }
                val text = when (payload.firstOrNull()?.toInt()) {
                    1 -> String(payload.copyOfRange(1, payload.size), Charsets.UTF_16)
                    2 -> String(payload.copyOfRange(1, payload.size), Charsets.UTF_16BE)
                    3 -> String(payload.copyOfRange(1, payload.size), Charsets.UTF_8)
                    else -> String(payload.copyOfRange(1, payload.size), Charsets.ISO_8859_1)
                }
                if (WORD_LYRICS_FIELD in text) return@use true
            }
            at += 10 + size
        }
        false
    }

    private fun syncSafe(bytes: ByteArray, at: Int): Int {
        require((at until at + 4).all { (bytes[it].toInt() and 0x80) == 0 }) { "Invalid ID3 size" }
        return ((bytes[at].toInt() and 127) shl 21) or ((bytes[at + 1].toInt() and 127) shl 14) or
            ((bytes[at + 2].toInt() and 127) shl 7) or (bytes[at + 3].toInt() and 127)
    }
}
