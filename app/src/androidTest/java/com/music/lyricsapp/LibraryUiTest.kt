package com.music.lyricsapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun albumsOpenTracksAtLargeTextSize() {
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 2f)) {
                LyricsAppTheme(LocalContext.current, ThemeChoice.LIGHT) {
                    Box(Modifier.width(360.dp)) {
                        LibraryHome(
                            tracks = listOf(LocalTrack("", "song.mp3", "A Quiet Hour", "Northbound",
                                "Night Maps", 212000, "1", "indexed", "", "", "", "", "")),
                            folder = "", autoAdd = false, mediaAccess = true,
                            scanProgress = LibrarySettings.ScanProgress(), filter = TrackFilter.ALL,
                            message = "", busy = false,
                            onFilter = {}, onPickFolder = {}, onRequestMedia = {}, onScan = {},
                            onSettings = {}, onAttention = {}, onOpen = {},
                            onMarkSelectedCorrect = {}, onWriteHighConfidence = {}, onClearMessage = {})
                    }
                }
            }
        }
        rule.onNodeWithText("Your albums").assertIsDisplayed()
        rule.onNodeWithText("Night Maps").performScrollTo().performClick()
        rule.onNodeWithText("A Quiet Hour").performScrollTo().assertIsDisplayed()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val titleBounds = rule.onNodeWithText("A Quiet Hour").fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.left >= rootBounds.left && titleBounds.right <= rootBounds.right)
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test fun lyricMarkersAndArabicRemainVisibleAtLargeTextSize() {
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 2f)) {
                LyricsAppTheme(LocalContext.current, ThemeChoice.DARK) {
                    Box(Modifier.width(360.dp)) {
                        LyricsTextBlock("[Verse]\nThe city is quiet\n\n[Chorus]\nيا ليل")
                    }
                }
            }
        }
        rule.onNodeWithText("[Verse]").assertIsDisplayed()
        rule.onNodeWithText("يا ليل").assertIsDisplayed()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test fun settingsSaveStaysVisibleAtLargeTextSize() {
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(currentDensity.density, 2f)) {
                LyricsAppTheme(LocalContext.current, ThemeChoice.LIGHT) {
                    Box(Modifier.width(360.dp)) {
                        SourceSettings(onClose = {}, onSaved = {})
                    }
                }
            }
        }
        rule.onNodeWithText("Save").assertIsDisplayed()
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val saveBounds = rule.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        assertTrue(saveBounds.left >= rootBounds.left && saveBounds.right <= rootBounds.right)
    }
}
