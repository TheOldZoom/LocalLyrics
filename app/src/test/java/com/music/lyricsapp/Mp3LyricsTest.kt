package com.music.lyricsapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp3LyricsTest {
    @Test fun writesReadableLyricsWithoutChangingAudio() {
        val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x00, 1, 2, 3)
        val lyrics = "[00:01.00]Café\n[00:02.00]Next line"

        val first = Mp3Lyrics.write(audio, lyrics)
        assertEquals(lyrics, Mp3Lyrics.read(first))
        assertArrayEquals(audio, first.copyOfRange(first.size - audio.size, first.size))

        val second = Mp3Lyrics.write(first, "[00:03.00]Changed")
        assertEquals("[00:03.00]Changed", Mp3Lyrics.read(second))
        assertTrue(second.size < first.size + 100)
        assertArrayEquals(audio, second.copyOfRange(second.size - audio.size, second.size))
    }

    @Test fun replacesLyricsInOtherLanguagesToo() {
        val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x00)
        val old = Mp3Lyrics.write(audio, "Old lyrics")
        // The USLT frame starts after the 10-byte ID3 header; its three-byte
        // language code follows the 10-byte frame header and encoding byte.
        "spa".toByteArray(Charsets.US_ASCII).copyInto(old, 21)
        val updated = Mp3Lyrics.write(old, "New lyrics")
        assertEquals("New lyrics", Mp3Lyrics.read(updated))
        val marker = "USLT".toByteArray(Charsets.US_ASCII)
        assertEquals(1, updated.indices.count { at ->
            at + marker.size <= updated.size && marker.indices.all { updated[at + it] == marker[it] }
        })
    }
}
