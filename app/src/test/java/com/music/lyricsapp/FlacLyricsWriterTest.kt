package com.music.lyricsapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FlacLyricsWriterTest {
    @Test fun replacesLyricsAndKeepsOtherCommentsAndAudio() {
        val input = File.createTempFile("lyrics-original-", ".flac")
        val output = File.createTempFile("lyrics-result-", ".flac")
        try {
            val streamInfo = ByteArray(34)
            val comments = comments("TITLE=Keep this", "LYRICS=Old lyrics")
            val audio = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x12, 0x34)
            input.writeBytes("fLaC".toByteArray() + block(0, streamInfo, false) +
                block(4, comments, true) + audio)
            FlacLyricsWriter.write(input, output, "[00:01.00]New lyrics", "")
            val bytes = output.readBytes()
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains("TITLE=Keep this"))
            assertTrue(String(bytes, Charsets.ISO_8859_1).contains("LYRICS=[00:01.00]New lyrics"))
            assertTrue(!String(bytes, Charsets.ISO_8859_1).contains("LYRICS=Old lyrics"))
            assertArrayEquals(audio, bytes.takeLast(audio.size).toByteArray())
            assertTrue(String(input.readBytes(), Charsets.ISO_8859_1).contains("LYRICS=Old lyrics"))
        } finally { input.delete(); output.delete() }
    }

    private fun block(type: Int, bytes: ByteArray, last: Boolean) =
        byteArrayOf((type or if (last) 0x80 else 0).toByte(), 0,
            (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes

    private fun comments(vararg fields: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun integer(n: Int) {
            out.write(n); out.write(n ushr 8); out.write(n ushr 16); out.write(n ushr 24)
        }
        val vendor = "BitChord".toByteArray()
        integer(vendor.size); out.write(vendor); integer(fields.size)
        fields.forEach { field ->
            val bytes = field.toByteArray()
            integer(bytes.size); out.write(bytes)
        }
        return out.toByteArray()
    }
}
