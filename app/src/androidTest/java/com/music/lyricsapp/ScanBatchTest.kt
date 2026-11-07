package com.music.lyricsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.music.lyricsapp.core.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanBatchTest {
    @Test fun unchangedScanKeepsReviewAndChangedScanKeepsBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = "content://scan-test/audio/1"
        LyricStore(context).use { store ->
            store.delete(uri)
            val first = LocalTrack(uri, "song.mp3", "Song", "Artist", "Album", 1000,
                "1:1:song.mp3", "new", "", "", "", "", "", backupUri = "content://backup/1")
            store.scanBatch { store.scanned(first) }
            store.setCandidate(uri, ProviderCandidate(LyricsSource.LRCLIB, "found", "words"))
            store.selectCandidate(uri, LyricsSource.LRCLIB)
            store.setReview(uri, "correct")

            store.scanBatch { store.scanned(first) }
            assertEquals("correct", store.get(uri)?.review)
            assertEquals(1, store.candidates(uri).size)

            store.scanBatch { store.scanned(first.copy(signature = "2:2:song.mp3")) }
            assertEquals("content://backup/1", store.get(uri)?.backupUri)
            assertTrue(store.candidates(uri).isEmpty())
            store.delete(uri)
        }
    }
}
