package com.music.lyricsapp

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.saveable.rememberSaveable
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

internal enum class TrackFilter(val titleRes: Int) {
    ALL(R.string.filter_all), REVIEW(R.string.filter_to_review),
    CORRECT(R.string.filter_correct), WRONG(R.string.filter_wrong),
    IN_FILE(R.string.filter_in_file), NEEDS_HELP(R.string.filter_needs_help)
}

private enum class TrackSort(val titleRes: Int) {
    TITLE(R.string.sort_title), ARTIST(R.string.sort_artist),
    DURATION(R.string.sort_duration), STATUS(R.string.sort_status)
}

internal fun LocalTrack.inFilter(filter: TrackFilter): Boolean = when (filter) {
    TrackFilter.ALL -> true
    TrackFilter.REVIEW -> proposed.isNotBlank() && review.isBlank() && status != "dismissed"
    TrackFilter.CORRECT -> review == "correct"
    TrackFilter.WRONG -> review == "wrong"
    TrackFilter.IN_FILE -> existing.isNotBlank()
    TrackFilter.NEEDS_HELP -> status in setOf("miss", "error", "metadata_error", "unsupported")
}

@Composable internal fun LocalTrack.displayStatus(): Pair<String, Color> = when {
    review == "wrong" -> stringResource(R.string.status_marked_wrong) to SoftCoral
    review == "correct" -> stringResource(R.string.status_marked_correct) to SeaGlass
    status == "applied" -> stringResource(R.string.status_added_review) to WarmGold
    status == "ready" -> stringResource(R.string.status_fetched_review) to WarmGold
    status == "indexed" -> stringResource(R.string.status_indexed) to SeaGlass
    status == "existing" -> stringResource(R.string.filter_in_file) to SeaGlass
    status == "miss" -> stringResource(R.string.status_no_match) to MutedBlue
    status == "error" -> stringResource(R.string.status_needs_attention) to SoftCoral
    status == "metadata_error" -> stringResource(R.string.status_unsafe_metadata) to SoftCoral
    status == "unsupported" -> stringResource(R.string.status_unsupported) to MutedBlue
    status == "dismissed" -> stringResource(R.string.status_dismissed) to MutedBlue
    status == "discovered" -> stringResource(R.string.discovered_status) to MutedBlue
    status == "inspecting" -> stringResource(R.string.inspecting_status) to MutedBlue
    else -> stringResource(R.string.status_searching) to MutedBlue
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun NeedsAttentionScreen(
    artifacts: List<RecoveryManager.Artifact>, backupDays: Int, message: String, busy: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit,
    onRestore: (RecoveryManager.Artifact) -> Unit,
    onDelete: (RecoveryManager.Artifact) -> Unit,
    onCleanup: () -> Unit, onClearMessage: () -> Unit,
) {
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.ui_needs_attention)) },
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.library)) } }) }) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ui_these_files_were_kept_for_recovery_after_a_write),
                    color = MutedBlue)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.refresh)) }
                    if (backupDays > 0) OutlinedButton(onClick = onCleanup, enabled = !busy) {
                        Text(stringResource(R.string.clean_up_after_backupdays_days, backupDays))
                    }
                }
                if (message.isNotBlank()) MessageBanner(message, onClearMessage)
            }
        }
        if (artifacts.isEmpty()) item {
            EmptyState(stringResource(R.string.ui_no_recovery_files_found),
                stringResource(R.string.recovery_empty_hint)) {
                TextButton(onClick = onRefresh, enabled = !busy) { Text(stringResource(R.string.refresh)) }
            }
        }
        items(artifacts, key = { it.uri }) { artifact ->
            Surface(color = CardBlue, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(artifact.name, fontWeight = FontWeight.SemiBold)
                    Text("${if (artifact.needsRestore) stringResource(R.string.metadata_recovery_needed)
                        else if (artifact.kind == RecoveryManager.Kind.BACKUP) stringResource(R.string.original_backup)
                        else stringResource(R.string.tagged_copy)} · ${stringResource(R.string.file_size_bytes, artifact.size)}",
                        color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (artifact.kind == RecoveryManager.Kind.BACKUP) {
                            OutlinedButton(onClick = { onRestore(artifact) }, enabled = !busy) {
                                Text(stringResource(R.string.restore_original))
                            }
                        }
                        TextButton(onClick = { onDelete(artifact) }, enabled = !busy) { Text(stringResource(R.string.delete)) }
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryHome(
    tracks: List<LocalTrack>, folder: String, autoAdd: Boolean, mediaAccess: Boolean,
    scanProgress: LibrarySettings.ScanProgress, filter: TrackFilter, message: String, busy: Boolean,
    onFilter: (TrackFilter) -> Unit, onPickFolder: () -> Unit, onRequestMedia: () -> Unit, onScan: () -> Unit,
    onSettings: () -> Unit, onAttention: () -> Unit,
    onOpen: (LocalTrack) -> Unit, onMarkSelectedCorrect: (Set<String>) -> Unit,
    onWriteHighConfidence: () -> Unit, onClearMessage: () -> Unit,
) {
    val openedAt = remember { android.os.SystemClock.elapsedRealtime() }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(TrackSort.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var showTools by rememberSaveable { mutableStateOf(folder.isBlank() && !mediaAccess) }
    BackHandler(selectedAlbum != null) { selectedAlbum = null }
    var selecting by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkWrite by remember { mutableStateOf(false) }
    val visible by produceState(initialValue = emptyList<LocalTrack>(), tracks, filter, query, sort) {
        value = withContext(Dispatchers.Default) {
        val matching = tracks.filter { track ->
            track.inFilter(filter) && (query.isBlank() || listOf(track.title, track.artist,
                track.album, track.name).any { it.contains(query.trim(), ignoreCase = true) })
        }
        when (sort) {
            TrackSort.TITLE -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            TrackSort.ARTIST -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
            TrackSort.DURATION -> matching.sortedByDescending { it.durationMs }
            TrackSort.STATUS -> matching.sortedWith(compareBy<LocalTrack> { it.status }.thenBy { it.title })
        }
        }
    }
    val albums by produceState(initialValue = emptyList<Pair<String, List<LocalTrack>>>(), visible) {
        value = withContext(Dispatchers.Default) {
            visible.groupBy { track ->
                "${track.album.ifBlank { "\u0000" }.lowercase()}\u0001${track.artist.lowercase()}"
            }.toList().sortedBy { (_, songs) -> songs.first().album.ifBlank { "\uffff" }.lowercase() }
        }
    }
    LaunchedEffect(albums.isNotEmpty()) {
        if (albums.isNotEmpty()) android.util.Log.i("LyricsScan",
            "First cached album prepared in ${android.os.SystemClock.elapsedRealtime() - openedAt} ms")
    }
    val albumTracks = albums.firstOrNull { it.first == selectedAlbum }?.second.orEmpty()
    val settingsLabel = stringResource(R.string.ui_settings)
    LazyColumn(Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (selectedAlbum != null) TextButton(onClick = { selectedAlbum = null }) {
                            Text(stringResource(R.string.back_to_albums))
                        }
                        Text(if (selectedAlbum == null) stringResource(R.string.your_albums)
                            else albumTracks.firstOrNull()?.album?.ifBlank { stringResource(R.string.unknown_album) }
                                ?: stringResource(R.string.unknown_album),
                            style = MaterialTheme.typography.headlineLarge, color = PaleBlue)
                        Text(if (selectedAlbum == null) stringResource(R.string.ui_your_music_with_the_words_in_view)
                            else stringResource(R.string.album_track_count, albumTracks.size), color = MutedBlue)
                    }
                    if (selectedAlbum == null) IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = settingsLabel)
                    }
                }
                if (selectedAlbum == null && !mediaAccess) Button(onClick = onRequestMedia) {
                    Text(stringResource(R.string.media_library_permission))
                }
                if (selectedAlbum == null) TextButton(onClick = { showTools = !showTools }) {
                    Text(if (showTools) stringResource(R.string.hide_library_tools)
                        else stringResource(R.string.show_library_tools))
                }
                if (selectedAlbum == null && showTools) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPickFolder) { Text(stringResource(
                        if (folder.isBlank()) R.string.choose_music_folder else R.string.change_music_folder)) }
                    OutlinedButton(onClick = onScan, enabled = folder.isNotBlank() || mediaAccess) { Text(stringResource(R.string.scan)) }
                }
                if (selectedAlbum == null && showTools && !mediaAccess) Text(stringResource(R.string.media_library_permission_explanation),
                    style = MaterialTheme.typography.bodySmall, color = MutedBlue)
                if (selectedAlbum == null && showTools) TextButton(onClick = onAttention) { Text(stringResource(R.string.needs_attention_recovery_files)) }
                if (scanProgress.active || scanProgress.discovered > 0) Text(
                    (if (scanProgress.active) stringResource(R.string.scanning) + " · " else "") +
                        stringResource(R.string.scan_counts, scanProgress.discovered, scanProgress.inspected),
                    color = WarmGold, style = MaterialTheme.typography.bodySmall)
                if (selectedAlbum == null && showTools && (folder.isNotBlank() || mediaAccess)) {
                    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
                        FlowRow(Modifier.fillMaxWidth().padding(18.dp),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.REVIEW) },
                                stringResource(R.string.filter_to_review), WarmGold)
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.WRONG) },
                                stringResource(R.string.filter_wrong), SoftCoral)
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.IN_FILE) },
                                stringResource(R.string.filter_in_file), SeaGlass)
                        }
                    }
                    if (folder.isNotBlank() && showTools) {
                        Text(stringResource(if (autoAdd) R.string.auto_writing_on_hint
                            else R.string.auto_writing_off_hint),
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                        val next = tracks.firstOrNull { it.inFilter(TrackFilter.REVIEW) }
                        if (next != null) OutlinedButton(onClick = { onOpen(next) }) { Text(stringResource(R.string.review_next)) }
                        OutlinedButton(onClick = { confirmBulkWrite = true }, enabled = !busy) {
                            Text(stringResource(R.string.write_all_high_confidence))
                        }
                    }
                }
                if (message.isNotBlank()) MessageBanner(message, onClearMessage)
            }
        }
        if (folder.isNotBlank() || mediaAccess) {
            if (selectedAlbum == null) item {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search_title_artist_album_or_file)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (selectedAlbum == null) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.material3.TextButton(onClick = { sortMenu = true }) {
                        Text(stringResource(R.string.sort_by, stringResource(sort.titleRes)))
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        TrackSort.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(stringResource(option.titleRes)) },
                                onClick = { sort = option; sortMenu = false })
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TrackFilter.entries) { option ->
                        FilterChip(selected = filter == option, onClick = { onFilter(option) },
                            label = { Text(stringResource(R.string.option_title_tracks_count_it_infilter_option,
                                stringResource(option.titleRes), tracks.count { it.inFilter(option) })) })
                    }
                }
            }
            if (selectedAlbum == null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selecting = !selecting; selectedUris = emptySet() }) {
                        Text(if (selecting) "Cancel selection" else "Select tracks")
                    }
                    if (selecting && selectedUris.isNotEmpty()) {
                        Button(onClick = {
                            onMarkSelectedCorrect(selectedUris)
                            selecting = false
                            selectedUris = emptySet()
                        }, enabled = !busy) { Text(stringResource(R.string.mark_selecteduris_size_correct, selectedUris.size)) }
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                val needsSource = folder.isBlank() && !mediaAccess
                EmptyState(
                    title = if (needsSource) stringResource(R.string.empty_library_source)
                        else if (tracks.isEmpty()) stringResource(R.string.empty_library_scanned)
                        else stringResource(R.string.nothing_in_filter, stringResource(filter.titleRes)),
                    guidance = if (needsSource) stringResource(R.string.empty_library_source_hint)
                        else if (tracks.isEmpty()) stringResource(R.string.empty_library_scan_hint)
                        else stringResource(R.string.empty_library_filter_hint)) {
                    TextButton(onClick = {
                        if (needsSource) onPickFolder()
                        else if (tracks.isEmpty()) onScan()
                        else onFilter(TrackFilter.ALL)
                    }) { Text(stringResource(if (needsSource) R.string.choose_music_folder
                        else if (tracks.isEmpty()) R.string.scan else R.string.filter_all)) }
                }
            }
        } else if (selectedAlbum == null && query.isBlank()) {
            items(albums.chunked(2), key = { it.first().first }) { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (key, songs) ->
                        AlbumCard(songs, Modifier.weight(1f)) { selectedAlbum = key }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(if (selectedAlbum == null) visible else albumTracks, key = { it.uri }) { track ->
                TrackRow(track, selecting, track.uri in selectedUris,
                    onClick = {
                        if (selecting) selectedUris = if (track.uri in selectedUris) selectedUris - track.uri
                            else selectedUris + track.uri
                        else onOpen(track)
                    })
            }
        }
    }
    if (confirmBulkWrite) ConfirmDialog(
        title = stringResource(R.string.write_high_confidence_lyrics),
        body = stringResource(R.string.only_tracks_with_no_embedded_lyrics_matching_pro),
        confirmLabel = stringResource(R.string.write_eligible_tracks),
        onConfirm = { confirmBulkWrite = false; onWriteHighConfidence() },
        onDismiss = { confirmBulkWrite = false })
}

@Composable
internal fun SummaryCount(count: Int, label: String, color: Color) {
    Column {
        Text(count.toString(), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MutedBlue)
    }
}

@Composable
private fun AlbumCard(songs: List<LocalTrack>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val first = songs.first()
    Surface(onClick = onClick, modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtworkTile(first.album.ifBlank { first.artist },
                Modifier.fillMaxWidth().aspectRatio(1f), first.uri, first.signature)
            Column(Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
                Text(first.album.ifBlank { stringResource(R.string.unknown_album) },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(first.artist.ifBlank { stringResource(R.string.unknown_artist) },
                    color = MutedBlue, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.album_track_count, songs.size),
                    style = MaterialTheme.typography.bodySmall, color = MutedBlue)
            }
        }
    }
}

@Composable
internal fun StatusPill(track: LocalTrack) {
    val (label, color) = track.displayStatus()
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

@Composable
internal fun TrackRow(track: LocalTrack, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    val preview = remember(track.proposed, track.existing, track.error, track.status) {
        val raw = track.proposed.ifBlank { track.existing }
        if (raw.isNotBlank()) lyricsAsText(raw).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        else track.error.ifBlank { "" }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                ArtworkTile(track.title, Modifier.size(44.dp), track.uri, track.signature)
                Spacer(Modifier.width(10.dp))
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onClick() },
                    modifier = Modifier.semantics { contentDescription = "Select ${track.title}" })
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifBlank { "Unknown artist" }, color = MutedBlue,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    StatusPill(track)
                }
            }
            if (preview.isNotBlank()) Text(preview, color = PaleBlue.copy(alpha = 0.86f),
                fontFamily = if (track.proposed.isNotBlank() || track.existing.isNotBlank()) FontFamily.Serif else FontFamily.Default,
                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MutedBlue.copy(alpha = 0.2f))
    }
}

@Preview(name = "Track row · light", showBackground = true, widthDp = 360)
@Preview(name = "Track row · large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun TrackRowPreview() {
    LyricsAppTheme(LocalContext.current, ThemeChoice.LIGHT) {
        TrackRow(LocalTrack("", "demo.mp3", "A Quiet Hour", "Northbound", "Night Maps",
            212000, "", "ready", "", "[Verse 1]\nThe streets are still", "", "", ""),
            selecting = false, selected = false, onClick = {})
    }
}

