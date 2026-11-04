package com.music.lyricsapp

import java.io.ByteArrayOutputStream

/** Reads and updates the standard ID3 USLT field without changing audio frames. */
object Mp3Lyrics {
    private data class Tag(val version: Int, val audioStart: Int, val frames: List<ByteArray>)

    fun read(bytes: ByteArray): String? {
        val tag = parse(bytes) ?: return null
        var otherLanguage: String? = null
        for (frame in tag.frames) {
            if (String(frame, 0, 4, Charsets.ISO_8859_1) != "USLT") continue
            val payload = frame.copyOfRange(10, frame.size)
            if (payload.size < 6) continue
            val encoding = payload[0].toInt() and 255
            val language = String(payload, 1, 3, Charsets.US_ASCII)
            val text = when (encoding) {
                0, 3 -> {
                    val end = payload.indexOf(0, 4)
                    if (end < 0) continue
                    String(payload, end + 1, payload.size - end - 1,
                        if (encoding == 0) Charsets.ISO_8859_1 else Charsets.UTF_8)
                }
                1, 2 -> {
                    var end = 4
                    while (end + 1 < payload.size && !(payload[end] == 0.toByte() && payload[end + 1] == 0.toByte())) end += 2
                    if (end + 1 >= payload.size) continue
                    String(payload, end + 2, payload.size - end - 2,
                        if (encoding == 1) Charsets.UTF_16 else Charsets.UTF_16BE)
                }
                else -> continue
            }
            if (text.isBlank()) continue
            if (language == "eng") return text
            if (otherLanguage == null) otherLanguage = text
        }
        return otherLanguage
    }

    fun write(bytes: ByteArray, lyrics: String): ByteArray {
        require(lyrics.isNotBlank() && lyrics.length <= 64_000) { "Invalid lyrics" }
        val old = parse(bytes)
        require(old != null || (bytes.size >= 2 && (bytes[0].toInt() and 255) == 0xff &&
            (bytes[1].toInt() and 0xe0) == 0xe0)) { "Invalid MP3 file" }
        val version = old?.version ?: 3
        // TagLib exposes every USLT frame as a LYRICS value. An older frame in
        // another language could otherwise win PixelPlayer's first-valid pick.
        val kept = old?.frames.orEmpty().filterNot { frame ->
            String(frame, 0, 4, Charsets.ISO_8859_1) == "USLT"
        }
        val content = ByteArrayOutputStream().apply {
            if (version == 4) {
                write(3); write("eng".toByteArray(Charsets.US_ASCII)); write(0)
                write(lyrics.toByteArray(Charsets.UTF_8))
            } else {
                write(1); write("eng".toByteArray(Charsets.US_ASCII)); write(byteArrayOf(0, 0))
                write(lyrics.toByteArray(Charsets.UTF_16))
            }
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            kept.forEach { write(it) }
            write("USLT".toByteArray(Charsets.US_ASCII))
            write(if (version == 4) syncSafe(content.size) else bigEndian(content.size))
            write(byteArrayOf(0, 0))
            write(content)
        }.toByteArray()
        val out = ByteArrayOutputStream(10 + body.size + bytes.size - (old?.audioStart ?: 0))
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(version.toByte(), 0, 0))
        out.write(syncSafe(body.size))
        out.write(body)
        out.write(bytes, old?.audioStart ?: 0, bytes.size - (old?.audioStart ?: 0))
        return out.toByteArray()
    }

    private fun parse(bytes: ByteArray): Tag? {
        if (bytes.size < 10 || String(bytes, 0, 3, Charsets.US_ASCII) != "ID3") return null
        val version = bytes[3].toInt() and 255
        require(version == 3 || version == 4) { "Only ID3v2.3 and v2.4 are supported" }
        require(bytes[5] == 0.toByte()) { "This ID3 header uses unsupported flags" }
        val size = readSyncSafe(bytes, 6)
        require(size >= 0 && 10L + size <= bytes.size) { "Invalid ID3 size" }
        val end = 10 + size
        val frames = mutableListOf<ByteArray>()
        var pos = 10
        while (pos + 10 <= end && bytes[pos] != 0.toByte()) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            require(id.matches(Regex("[A-Z0-9]{4}"))) { "Invalid ID3 frame" }
            val frameSize = if (version == 4) readSyncSafe(bytes, pos + 4) else readBigEndian(bytes, pos + 4)
            require(frameSize > 0 && pos.toLong() + 10 + frameSize <= end) { "Invalid ID3 frame size" }
            frames += bytes.copyOfRange(pos, pos + 10 + frameSize)
            pos += 10 + frameSize
        }
        return Tag(version, end, frames)
    }

    private fun readSyncSafe(bytes: ByteArray, at: Int): Int {
        require((at until at + 4).all { (bytes[it].toInt() and 0x80) == 0 }) { "Invalid ID3 size" }
        return ((bytes[at].toInt() and 127) shl 21) or ((bytes[at + 1].toInt() and 127) shl 14) or
            ((bytes[at + 2].toInt() and 127) shl 7) or (bytes[at + 3].toInt() and 127)
    }

    private fun readBigEndian(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 255) shl 24) or ((bytes[at + 1].toInt() and 255) shl 16) or
            ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)

    private fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 127).toByte(), ((value ushr 14) and 127).toByte(),
        ((value ushr 7) and 127).toByte(), (value and 127).toByte(),
    )

    private fun bigEndian(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte(),
    )

    private fun ByteArray.indexOf(value: Byte, start: Int): Int {
        for (i in start until size) if (this[i] == value) return i
        return -1
    }
}
