package com.music.lyricsapp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import com.music.lyricsapp.core.WORD_LYRICS_FIELD

/** Replaces only FLAC lyrics comments, retaining every other metadata block and audio frame. */
object FlacLyricsWriter {
    private data class Block(val type: Int, val data: ByteArray)

    fun write(source: File, target: File, plain: String, enhanced: String) {
        StandardLyricsField.requireLyrics(plain)
        DataInputStream(FileInputStream(source)).use { input ->
            val magic = ByteArray(4).also { input.readFully(it) }
            require(String(magic, Charsets.US_ASCII) == "fLaC") { "Invalid FLAC header" }
            val blocks = mutableListOf<Block>()
            var flags: Int
            do {
                flags = input.readUnsignedByte()
                val size = (input.readUnsignedByte() shl 16) or
                    (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
                require(size <= 16 * 1024 * 1024) { "FLAC metadata block is too large" }
                blocks += Block(flags and 0x7F, ByteArray(size).also { input.readFully(it) })
            } while (flags and 0x80 == 0)
            require(blocks.firstOrNull()?.type == 0) { "FLAC STREAMINFO is missing" }
            val existing = blocks.firstOrNull { it.type == 4 }?.data
            val comments = replaceComments(existing, plain, enhanced)
            val updated = blocks.filterNot { it.type == 4 }.toMutableList()
            updated.add(1, Block(4, comments))
            FileOutputStream(target).use { out ->
                out.write(magic)
                updated.forEachIndexed { index, block ->
                    val size = block.data.size
                    require(size <= 0xFFFFFF) { "Lyrics exceed FLAC metadata limit" }
                    out.write(block.type or if (index == updated.lastIndex) 0x80 else 0)
                    out.write(size ushr 16); out.write(size ushr 8); out.write(size)
                    out.write(block.data)
                }
                input.copyTo(out, 64 * 1024)
                out.fd.sync()
            }
        }
    }

    private fun replaceComments(existing: ByteArray?, plain: String, enhanced: String): ByteArray {
        var vendor = "Lyrics".toByteArray(Charsets.UTF_8)
        val fields = mutableListOf<String>()
        if (existing != null) {
            var at = 0
            fun readInt(): Int {
                require(at + 4 <= existing.size) { "Truncated FLAC comments" }
                val n = (existing[at].toInt() and 255) or ((existing[at + 1].toInt() and 255) shl 8) or
                    ((existing[at + 2].toInt() and 255) shl 16) or ((existing[at + 3].toInt() and 255) shl 24)
                at += 4
                return n
            }
            fun readBytes(): ByteArray {
                val n = readInt()
                require(n >= 0 && at + n <= existing.size) { "Invalid FLAC comment length" }
                return existing.copyOfRange(at, at + n).also { at += n }
            }
            vendor = readBytes()
            val count = readInt()
            require(count in 0..100_000) { "Invalid FLAC comment count" }
            repeat(count) {
                val field = String(readBytes(), Charsets.UTF_8)
                if (!field.substringBefore('=').equals(StandardLyricsField.VORBIS.fieldName, true) &&
                    !field.substringBefore('=').equals(WORD_LYRICS_FIELD, true)) fields += field
            }
        }
        fields += "${StandardLyricsField.VORBIS.fieldName}=$plain"
        if (enhanced.isNotBlank()) fields += "$WORD_LYRICS_FIELD=$enhanced"
        val out = ByteArrayOutputStream()
        fun putInt(n: Int) { out.write(n); out.write(n ushr 8); out.write(n ushr 16); out.write(n ushr 24) }
        putInt(vendor.size); out.write(vendor); putInt(fields.size)
        fields.forEach { field ->
            val value = field.toByteArray(Charsets.UTF_8)
            putInt(value.size); out.write(value)
        }
        return out.toByteArray()
    }
}
