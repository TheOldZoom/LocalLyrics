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
}
