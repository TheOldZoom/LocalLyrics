package com.music.lyricsapp

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.lyricsapp.core.LrcLib
import com.music.lyricsapp.core.LyricsSource
import com.music.lyricsapp.core.toEnhancedLrc
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun TrackDetail(
    track: LocalTrack, candidates: List<ProviderCandidate>, enabledSources: Set<LyricsSource>,
    message: String, applying: Boolean, reviewing: Boolean, onBack: () -> Unit,
    onSelectProvider: (LyricsSource) -> Unit, onRefreshProviders: () -> Unit,
    onOpenSources: () -> Unit,
    onReview: (String) -> Unit, onExportFetched: (Boolean) -> Unit,
    onExportEmbedded: (Boolean) -> Unit, onApply: () -> Unit, onRestore: () -> Unit,
    onNextReview: () -> Unit, onEditLyrics: (String, String) -> Unit,
    onRetry: (String, String) -> Unit, onDismiss: () -> Unit, onClearMessage: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(track.uri) { LocalAudioPlayer(context, Uri.parse(track.uri)) }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (true) {
            player.tick()
            delay(100)
        }
    }
    var matchTitle by remember(track.uri) { mutableStateOf(track.title) }
    var matchArtist by remember(track.uri) { mutableStateOf(track.artist) }
    var showEditor by remember(track.uri) { mutableStateOf(false) }
    var editedText by remember(track.uri) { mutableStateOf("") }
    var offsetMs by remember(track.uri) { mutableStateOf(0) }
    var embeddedEnhanced by remember(track.uri) { mutableStateOf("") }
    LaunchedEffect(track.uri, track.existing) {
        if (track.existing.isNotBlank()) {
            val lines = EmbeddedLyrics.forUri(context, track.uri, track.name.substringAfterLast('.').lowercase())
            embeddedEnhanced = lines?.toEnhancedLrc().orEmpty()
        }
    }
    val selectedCandidate = candidates.firstOrNull { it.source.name == track.selectedProvider }
    val confidence = selectedCandidate?.let { LyricConfidence.score(track, it, candidates) }
    val fetchedIsInFile = track.proposed.isNotBlank() && track.existing.trim() == track.proposed.trim()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !applying) { Text(stringResource(R.string.library_2)) }
            Spacer(Modifier.weight(1f))
            StatusPill(track)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column {
                Text(track.title, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                Text(track.artist.ifBlank { "Unknown artist" }, color = MutedBlue, fontSize = 16.sp)
                Text(track.name, color = MutedBlue, style = MaterialTheme.typography.bodySmall)
            }
            if (message.isNotBlank()) MessageBanner(message, onClearMessage)
            PlayerPanel(player, track.durationMs)
            ProviderPicker(track, candidates, enabledSources, onSelectProvider,
                onRefreshProviders, onOpenSources)
            if (track.review == "wrong") {
                Surface(color = SoftCoral.copy(alpha = 0.14f), shape = RoundedCornerShape(16.dp)) {
                    Text(if (fetchedIsInFile) "You marked these lyrics wrong. They are still in the audio file. Search again to find a replacement."
                        else "You marked this result wrong. It will not be written to the file. Adjust the search and try again.",
                        color = SoftCoral, modifier = Modifier.padding(16.dp))
                }
            }
            if (track.proposed.isNotBlank()) {
                if (confidence != null) Text(
                    "Confidence ${confidence.total}/100 · metadata ${confidence.metadata}, duration ${confidence.duration}, provider agreement ${confidence.agreement}, timing ${confidence.timing}",
                    color = if (confidence.total >= LyricConfidence.AUTO_WRITE_THRESHOLD) SeaGlass else WarmGold,
                    style = MaterialTheme.typography.bodySmall,
                )
                LyricsPanel(if (fetchedIsInFile) "Fetched lyrics · in file" else "Fetched lyrics",
                    track.proposed, track.source.ifBlank { "Source unknown" },
                    onExportRaw = { if (fetchedIsInFile) onExportEmbedded(false) else onExportFetched(false) },
                    onExportText = { if (fetchedIsInFile) onExportEmbedded(true) else onExportFetched(true) },
                    enhanced = track.enhanced, player = player)
                OutlinedButton(onClick = {
                    editedText = track.proposed
                    offsetMs = 0
                    showEditor = true
                }, enabled = !applying && !reviewing) { Text(stringResource(R.string.edit_lyrics_or_timing)) }
                if (track.status != "dismissed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.ui_are_these_the_right_words), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ui_only_your_review_marks_a_result_correct_or_wrong),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = track.review == "correct", onClick = { onReview("correct") },
                                enabled = !applying && !reviewing,
                                label = { Text(stringResource(R.string.correct)) })
                            FilterChip(selected = track.review == "wrong", onClick = { onReview("wrong") },
                                enabled = !applying && !reviewing,
                                label = { Text(stringResource(R.string.wrong)) })
                        }
                        TextButton(onClick = onNextReview, enabled = !applying && !reviewing) {
                            Text(stringResource(R.string.next_to_review))
                        }
                    }
                }
                if (track.status == "ready") {
                    Button(onClick = onApply, enabled = !applying && !reviewing && track.review != "wrong",
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (applying) "Writing lyrics…" else if (track.existing.isBlank()) "Add lyrics to file" else "Replace lyrics in file")
                    }
                }
            }
            if (track.existing.isNotBlank() && !fetchedIsInFile) {
                LyricsPanel("Lyrics already in file", track.existing, "Embedded metadata",
                    onExportRaw = { onExportEmbedded(false) }, onExportText = { onExportEmbedded(true) },
                    enhanced = embeddedEnhanced, player = player)
            }
            if (track.proposed.isBlank() && track.existing.isBlank()) {
                Surface(color = CardBlue, shape = RoundedCornerShape(18.dp)) {
                    Text(track.error.ifBlank { when (track.status) {
                        "unsupported" -> "This audio format cannot be tagged by the app yet."
                        "new" -> "The app is looking for lyrics."
                        else -> "No lyrics are available for this file yet."
                    } }, modifier = Modifier.padding(18.dp), color = MutedBlue)
                }
            }
            if (track.status != "unsupported") {
                Surface(color = CardBlue, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.ui_search_again), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.ui_change_the_title_or_artist_if_the_result_was_for),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = matchTitle, onValueChange = { matchTitle = it },
                            label = { Text(stringResource(R.string.title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = matchArtist, onValueChange = { matchArtist = it },
                            label = { Text(stringResource(R.string.artist)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { onRetry(matchTitle, matchArtist) },
                            enabled = !applying && !reviewing && matchTitle.isNotBlank() && matchArtist.isNotBlank()) {
                            Text(stringResource(R.string.search_again))
                        }
                    }
                }
            }
            if (track.status == "ready" && track.proposed.isNotBlank()) {
                TextButton(onClick = onDismiss, enabled = !applying && !reviewing) { Text(stringResource(R.string.dismiss_result)) }
            }
            if (track.backupUri.isNotBlank()) {
                OutlinedButton(onClick = onRestore, enabled = !applying && !reviewing) {
                    Text(stringResource(R.string.restore_original))
                }
                Text(stringResource(R.string.ui_the_updated_version_will_be_kept_as_a_recovery_f),
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (showEditor) {
        val previewLines = remember(editedText) {
            editedText.lineSequence().filter(LyricTiming::hasTimestamps).take(3).joinToString("\n")
        }
        val offsetLabel = when {
            offsetMs < 0 -> stringResource(R.string.timing_earlier_ms, abs(offsetMs))
            offsetMs > 0 -> stringResource(R.string.timing_later_ms, offsetMs)
            else -> stringResource(R.string.timing_no_offset)
        }
        val sliderDescription = stringResource(R.string.timing_slider_label)
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(stringResource(R.string.edit_proposed_lyrics)) },
            text = {
                Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.changes_stay_in_the_app_until_you_choose_to_writ))
                    OutlinedTextField(
                        value = editedText, onValueChange = { editedText = it },
                        label = { Text(stringResource(R.string.lyrics_plain_text_or_lrc)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 220.dp),
                        maxLines = 10,
                    )
                    Text(offsetLabel, style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = offsetMs.toFloat(),
                        onValueChange = { offsetMs = (it / 10f).roundToInt() * 10 },
                        valueRange = -10_000f..10_000f,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = sliderDescription
                            stateDescription = offsetLabel
                        },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { offsetMs = (offsetMs - 100).coerceAtLeast(-10_000) }) {
                            Text(stringResource(R.string.timing_minus_100))
                        }
                        TextButton(onClick = { offsetMs = 0 }) {
                            Text(stringResource(R.string.timing_reset))
                        }
                        TextButton(onClick = { offsetMs = (offsetMs + 100).coerceAtMost(10_000) }) {
                            Text(stringResource(R.string.timing_plus_100))
                        }
                    }
                    if (previewLines.isBlank()) {
                        Text(stringResource(R.string.timing_no_timestamps),
                            style = MaterialTheme.typography.bodySmall, color = MutedBlue)
                    } else {
                        Text(stringResource(R.string.timing_preview), style = MaterialTheme.typography.labelMedium)
                        Text(LyricTiming.shift(previewLines, offsetMs),
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val shifted = LyricTiming.shift(editedText, offsetMs)
                    val enhanced = if (editedText == track.proposed)
                        LyricTiming.shift(track.enhanced, offsetMs) else ""
                    onEditLyrics(shifted, enhanced)
                    showEditor = false
                }, enabled = editedText.isNotBlank() && !applying && !reviewing) {
                    Text(stringResource(R.string.save_proposal))
                }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PlayerPanel(player: LocalAudioPlayer, fallbackDurationMs: Long) {
    val duration = player.durationMs.takeIf { it > 0 } ?: fallbackDurationMs.coerceAtLeast(0L)
    var dragging by remember { mutableStateOf(false) }
    var draggedPosition by remember { mutableStateOf(0f) }
    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = player::toggle, enabled = player.ready) {
                    Text(if (player.playing) "Pause" else "Play")
                }
                Column {
                    Text(stringResource(R.string.ui_listen_and_compare), style = MaterialTheme.typography.titleSmall)
                    Text(if (player.ready) "Tap a lyric line to seek" else "Preparing local audio…",
                        color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                }
            }
            Slider(value = (if (dragging) draggedPosition else player.positionMs.toFloat())
                    .coerceIn(0f, duration.coerceAtLeast(1L).toFloat()),
                onValueChange = { dragging = true; draggedPosition = it },
                onValueChangeFinished = { player.seekTo(draggedPosition.toLong()); dragging = false },
                valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                enabled = player.ready && player.durationMs > 0L)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timestamp(player.positionMs), color = MutedBlue, style = MaterialTheme.typography.labelMedium)
                Text(timestamp(duration), color = MutedBlue, style = MaterialTheme.typography.labelMedium)
            }
            if (player.error.isNotBlank()) Text(player.error, color = SoftCoral,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProviderPicker(
    track: LocalTrack, candidates: List<ProviderCandidate>, enabledSources: Set<LyricsSource>,
    onSelect: (LyricsSource) -> Unit, onRefresh: () -> Unit, onOpenSources: () -> Unit,
) {
    val context = LocalContext.current
    val bySource = remember(candidates) { candidates.associateBy { it.source } }
    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.ui_compare_providers), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ui_choose_a_result_to_read_while_the_track_plays_yo),
                color = MutedBlue, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth().heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
                LibrarySettings.sourceOrder(context).forEach { source ->
                    val result = bySource[source]
                    val selected = track.selectedProvider == source.name
                    val state = when {
                        source !in enabledSources -> if (result?.state == "found") "Off · saved" else "Off"
                        result?.review == "correct" -> "Correct"
                        result?.review == "wrong" -> "Wrong"
                        result?.state == "found" -> if (result.enhanced.isNotBlank()) "Word timed" else "Found"
                        result?.state == "fetching" -> "Fetching"
                        result?.state == "timeout" -> "Timed out"
                        result?.state == "error" -> "Error"
                        result?.state == "missing" -> "No result"
                        else -> "Waiting"
                    }
                    Column(Modifier.fillMaxWidth()
                        .clickable(enabled = result?.state == "found") { onSelect(source) }
                        .padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(source.label, modifier = Modifier.weight(1f),
                                color = if (selected) SeaGlass else PaleBlue,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            Text(if (selected) "Selected · $state" else state,
                                color = when (result?.review) { "wrong" -> SoftCoral; "correct" -> SeaGlass; else -> MutedBlue },
                                style = MaterialTheme.typography.labelMedium)
                        }
                        result?.takeIf { it.state == "found" }?.let { candidate ->
                            val preview = remember(candidate.plain) {
                                lyricsAsText(candidate.plain).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                            }
                            if (preview.isNotBlank()) Text(preview, color = MutedBlue,
                                fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider(color = MutedBlue.copy(alpha = 0.14f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenSources) { Text(stringResource(R.string.choose_providers)) }
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.fetch_again)) }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    title: String, raw: String, source: String,
    onExportRaw: () -> Unit, onExportText: () -> Unit,
    enhanced: String = "", player: LocalAudioPlayer? = null,
) {
    val context = LocalContext.current
    val lines = remember(raw, enhanced) {
        LrcLib.parseLrc(enhanced.ifBlank { raw })
    }
    val position = player?.positionMs ?: 0L
    val activeIndex = if (player?.ready == true) lines.indexOfLast { it.timeMs <= position } else -1
    val listState = rememberLazyListState()
    var copied by remember(raw) { mutableStateOf("") }
    LaunchedEffect(activeIndex, raw) {
        if (activeIndex >= 0) {
            listState.animateScrollToItem((activeIndex - 1).coerceAtLeast(0))
        }
    }
    fun copy(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.ui_lyrics), text))
        copied = label
    }
    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("$source · ${if (lines.isEmpty()) "Plain text" else if (enhanced.isNotBlank()) "Word timed" else "Line timed"}",
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
            }
            if (lines.isEmpty()) {
                Text(raw, fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 28.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(350.dp), state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(lines) { index, line ->
                        val active = index == activeIndex
                        val rendered = if (line.isGap) AnnotatedString("♪")
                            else if (active && line.words.isNotEmpty()) buildAnnotatedString {
                            append(line.text)
                            line.wordSpans.forEachIndexed { wordIndex, span ->
                                if (line.words[wordIndex].startMs <= position &&
                                    span.first >= 0 && span.last < line.text.length) {
                                    addStyle(SpanStyle(color = SeaGlass, fontWeight = FontWeight.Bold),
                                        span.first, span.last + 1)
                                }
                            }
                        } else AnnotatedString(line.text)
                        Surface(color = if (active) SeaGlass.copy(alpha = 0.13f) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)) {
                            Row(Modifier.fillMaxWidth()
                                .clickable(enabled = player?.ready == true) { player?.seekTo(line.timeMs) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(timestamp(line.timeMs), color = if (active) SeaGlass else MutedBlue,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.width(43.dp).padding(top = 5.dp))
                                Text(rendered, color = if (active && line.words.isEmpty()) SeaGlass else PaleBlue,
                                    fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 27.sp,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copy(raw, "Copied lyrics") }) {
                    Text(if (lines.isEmpty()) "Copy lyrics" else "Copy LRC")
                }
                if (lines.isNotEmpty()) TextButton(onClick = { copy(lyricsAsText(raw), "Copied words") }) {
                    Text(stringResource(R.string.copy_words))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExportRaw) { Text(if (lines.isEmpty()) "Export lyrics" else "Export LRC") }
                if (lines.isNotEmpty()) TextButton(onClick = onExportText) { Text(stringResource(R.string.export_text)) }
            }
            if (copied.isNotBlank()) Text(copied, color = SeaGlass,
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun timestamp(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1_000
    return "${(totalSeconds / 60).toString().padStart(2, '0')}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/** Keep plain embedded text as-is; strip LRC timestamps from timed lyrics. */
internal fun lyricsAsText(raw: String): String =
    LrcLib.parseLrc(raw).map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
        .ifBlank { raw }

