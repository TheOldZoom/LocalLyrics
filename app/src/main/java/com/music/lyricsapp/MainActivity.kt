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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibrarySettings.schedule(this)
        if (LibrarySettings.folder(this).isNotBlank()) LibrarySettings.scanNow(this)
        setContent {
            var theme by remember { mutableStateOf(ThemePreference.get(this)) }
            LyricsAppTheme(this, theme) {
                Surface(Modifier.fillMaxSize(), color = DeepBlue) {
                    LyricsScreen(onThemeChanged = { theme = ThemePreference.get(this) })
                }
            }
        }
    }
}
@Composable
private fun LyricsScreen(onThemeChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navigation = rememberNavController()
    val libraryModel: LyricsViewModel = viewModel()
    val tracks by libraryModel.tracks.collectAsStateWithLifecycle()
    val candidates by libraryModel.candidates.collectAsStateWithLifecycle()
    var pendingReviews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val visibleTracks = remember(tracks, pendingReviews) {
        tracks.map { track -> pendingReviews[track.uri]?.let { track.copy(review = it) } ?: track }
    }
    LaunchedEffect(tracks) {
        pendingReviews = pendingReviews.filter { (uri, review) ->
            tracks.firstOrNull { it.uri == uri }?.review != review
        }
    }
    val scanProgress by LibrarySettings.scanProgress.collectAsStateWithLifecycle()
    var selectedUri by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf(LibrarySettings.folder(context)) }
    var autoAdd by remember { mutableStateOf(LibrarySettings.autoAdd(context)) }
    var enabledSources by remember { mutableStateOf(LibrarySettings.sources(context)) }
    var message by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var artifacts by remember { mutableStateOf<List<RecoveryManager.Artifact>>(emptyList()) }
    var artifactBusy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RecoveryManager.Artifact?>(null) }
    var filter by remember { mutableStateOf(TrackFilter.ALL) }
    var exportPayload by remember { mutableStateOf("") }
    var showAutoIntro by remember { mutableStateOf(!LibrarySettings.autoIntroSeen(context)) }
    var showPrivacyIntro by remember { mutableStateOf(!LibrarySettings.privacyIntroSeen(context)) }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(exportPayload)
                } ?: error("Could not create the lyrics file")
                message = "Lyrics exported"
            } catch (e: Exception) {
                message = e.message ?: "Could not export lyrics"
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (folder != uri.toString()) LyricStore(context).use { it.clear() }
                LibrarySettings.setFolder(context, uri.toString())
                folder = uri.toString()
                selectedUri = null
                filter = TrackFilter.ALL
                LibrarySettings.scanNow(context)
                message = "Scanning selected folder"
            } catch (e: Exception) {
                message = e.message ?: "Could not keep folder access"
            }
        }
    }
    LaunchedEffect(selectedUri) { libraryModel.select(selectedUri) }

    val selectedTrack = selectedUri?.let { uri -> visibleTracks.firstOrNull { it.uri == uri } }

    val exportLyrics: (LocalTrack, Boolean, Boolean) -> Unit = { track, fromFile, asText ->
        scope.launch {
            try {
                val raw = if (fromFile) {
                    val extension = track.name.substringAfterLast('.', "").lowercase()
                    RawEmbeddedLyrics.read(context, Uri.parse(track.uri), extension)
                        ?.takeIf { it.isNotBlank() }
                        ?: error("No embedded lyrics remain in this file. Scan the folder again.")
                } else track.proposed.takeIf { it.isNotBlank() } ?: error("No fetched lyrics to export")
                val timed = LrcLib.parseLrc(raw).isNotEmpty()
                exportPayload = if (asText) lyricsAsText(raw) else raw
                val fileType = if (asText || !timed) "txt" else "lrc"
                val fileName = track.name.substringBeforeLast('.', track.name)
                exportPicker.launch("$fileName.$fileType")
            } catch (e: Exception) {
                message = e.message ?: "Could not export lyrics"
            }
        }
    }
    val markReview: (LocalTrack, String) -> Unit = { track, verdict ->
        if (!applying && !reviewing && track.review != verdict) {
            reviewing = true
            pendingReviews = pendingReviews + (track.uri to verdict)
            scope.launch {
                try {
                    libraryModel.setReview(track.uri, verdict)
                } catch (e: Exception) {
                    pendingReviews = pendingReviews - track.uri
                    message = e.message ?: "Could not save review"
                } finally {
                    reviewing = false
                }
            }
        }
    }
    val applyLyrics: (LocalTrack) -> Unit = { track ->
        if (!applying && !reviewing && track.status == "ready" && track.review != "wrong") {
            applying = true
            scope.launch {
                try {
                    val outcome = ApprovedWriter.apply(context, track)
                    withContext(Dispatchers.IO) {
                        LyricStore(context).use { it.recordWrite(track, outcome) }
                    }
                    message = outcome.message
                    if (outcome.replaced) {
                        selectedUri = null
                        navigation.popBackStack()
                    }
                } catch (e: Exception) {
                    message = e.message ?: "Could not write lyrics"
                } finally {
                    applying = false
                }
            }
        }
    }
    val retry: (LocalTrack, String, String) -> Unit = { track, title, artist ->
        if (reviewing || applying) {
            message = "Wait for the current change to finish"
        } else if (title.isBlank() || artist.isBlank()) {
            message = "Enter a title and artist to search again"
        } else {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        LyricStore(context).use { it.editMatch(track.uri, title, artist) }
                    }
                    LibrarySettings.scanNow(context)
                    selectedUri = null
                    navigation.popBackStack()
                    message = "Searching again for $title"
                } catch (e: Exception) {
                    message = e.message ?: "Could not restart the search"
                }
            }
        }
    }
    val selectProvider: (LocalTrack, LyricsSource) -> Unit = { track, source ->
        if (!applying && !reviewing) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LyricStore(context).use { it.selectCandidate(track.uri, source) }
                }
                message = "Showing ${source.label} lyrics"
            } catch (e: Exception) {
                message = e.message ?: "Could not select provider"
            }
        }
    }
    val refreshProviders: (LocalTrack) -> Unit = { track ->
        if (!applying && !reviewing) scope.launch {
            try {
                withContext(Dispatchers.IO) { LyricStore(context).use { it.clearCandidateLookup(track.uri) } }
                LibrarySettings.scanNow(context, track.uri)
                message = "Fetching selected providers again"
            } catch (e: Exception) {
                message = e.message ?: "Could not restart provider lookup"
            }
        }
    }
    val dismiss: (LocalTrack) -> Unit = { track ->
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LyricStore(context).use {
                        it.setReview(track.uri, "")
                        it.update(track.uri, "dismissed")
                    }
                }
                selectedUri = null
                navigation.popBackStack()
                message = "Result dismissed"
            } catch (e: Exception) {
                message = e.message ?: "Could not dismiss result"
            }
        }
    }
    val restoreOriginal: (LocalTrack) -> Unit = { track ->
        if (!applying && track.backupUri.isNotBlank()) {
            applying = true
            scope.launch {
                try {
                    message = RecoveryManager.restore(context, track.backupUri)
                    withContext(Dispatchers.IO) {
                        LyricStore(context).use { it.clearBackup(track.uri) }
                    }
                    selectedUri = null
                    navigation.popBackStack()
                    LibrarySettings.scanNow(context)
                } catch (e: Exception) {
                    message = e.message ?: "Could not restore original"
                } finally { applying = false }
            }
        }
    }

    val refreshArtifacts: () -> Unit = {
        if (folder.isNotBlank()) scope.launch {
            try { artifacts = RecoveryManager.list(context) }
            catch (e: Exception) { message = e.message ?: "Could not inspect recovery files" }
        }
    }
    val restoreArtifact: (RecoveryManager.Artifact) -> Unit = { artifact ->
        if (!artifactBusy && artifact.kind == RecoveryManager.Kind.BACKUP) scope.launch {
            artifactBusy = true
            try {
                message = RecoveryManager.restore(context, artifact.uri)
                artifacts = RecoveryManager.list(context)
                LibrarySettings.scanNow(context)
            } catch (e: Exception) {
                message = e.message ?: "Could not restore backup"
            } finally { artifactBusy = false }
        }
    }
    val deleteArtifact: (RecoveryManager.Artifact) -> Unit = { artifact ->
        if (!artifactBusy) scope.launch {
            artifactBusy = true
            try {
                message = RecoveryManager.delete(context, artifact.uri)
                artifacts = RecoveryManager.list(context)
            } catch (e: Exception) {
                message = e.message ?: "Could not delete recovery file"
            } finally { artifactBusy = false }
        }
    }
    val cleanupArtifacts: () -> Unit = {
        if (!artifactBusy) scope.launch {
            artifactBusy = true
            try {
                val count = RecoveryManager.cleanupExpired(context)
                artifacts = RecoveryManager.list(context)
                message = "Removed $count expired backups"
            } catch (e: Exception) {
                message = e.message ?: "Could not clean up backups"
            } finally { artifactBusy = false }
        }
    }
    val markSelectedCorrect: (Set<String>) -> Unit = { uris ->
        if (!applying && !reviewing) scope.launch {
            reviewing = true
            try {
                val changed = libraryModel.markCorrect(uris)
                message = "Marked $changed results correct"
            } catch (e: Exception) {
                message = e.message ?: "Could not update reviews"
            } finally { reviewing = false }
        }
    }
    val writeHighConfidence: () -> Unit = {
        if (!applying && !reviewing) scope.launch {
            applying = true
            try {
                val eligible = withContext(Dispatchers.IO) {
                    LyricStore(context).use { store ->
                        store.all().filter { track ->
                            if (track.status != "ready" || track.existing.isNotBlank() || track.review == "wrong") false
                            else {
                                val candidates = store.candidates(track.uri)
                                val selected = candidates.firstOrNull { it.source.name == track.selectedProvider }
                                selected != null && LyricConfidence.canAutoWrite(track, selected, candidates)
                            }
                        }
                    }
                }
                var written = 0
                var kept = 0
                eligible.forEachIndexed { index, track ->
                    message = "Writing ${index + 1} of ${eligible.size} high-confidence tracks"
                    try {
                        val outcome = ApprovedWriter.apply(context, track)
                        withContext(Dispatchers.IO) { LyricStore(context).use { it.recordWrite(track, outcome) } }
                        if (outcome.replaced) written++ else kept++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { kept++ }
                }
                message = "Wrote $written tracks; $kept need individual attention"
            } catch (e: Exception) {
                message = e.message ?: "Bulk write could not start"
            } finally { applying = false }
        }
    }

    NavHost(navController = navigation, startDestination = "library") {
        composable("library") {
            LibraryHome(visibleTracks, folder, autoAdd, scanProgress, filter, message, applying || reviewing,
                onFilter = { filter = it },
                onPickFolder = { folderPicker.launch(null) },
                onScan = { LibrarySettings.scanNow(context); message = "Scanning folder" },
                onSettings = { navigation.navigate("settings") },
                onAttention = { refreshArtifacts(); navigation.navigate("attention") },
                onOpen = { selectedUri = it.uri; navigation.navigate("track") },
                onMarkSelectedCorrect = markSelectedCorrect,
                onWriteHighConfidence = writeHighConfidence,
                onClearMessage = { message = "" })
        }
        composable("track") {
            if (selectedTrack == null) {
                TextButton(onClick = { navigation.popBackStack() }) { Text(stringResource(R.string.return_to_library)) }
            } else {
                TrackDetail(selectedTrack, candidates, enabledSources, message, applying, reviewing,
                    onBack = { selectedUri = null; navigation.popBackStack() },
                    onSelectProvider = { selectProvider(selectedTrack, it) },
                    onRefreshProviders = { refreshProviders(selectedTrack) },
                    onOpenSources = { navigation.navigate("settings") },
                    onReview = { markReview(selectedTrack, it) },
                    onExportFetched = { asText -> exportLyrics(selectedTrack, false, asText) },
                    onExportEmbedded = { asText -> exportLyrics(selectedTrack, true, asText) },
                    onApply = { applyLyrics(selectedTrack) },
                    onEditLyrics = { plain, enhanced ->
                        if (!applying && !reviewing) scope.launch {
                            reviewing = true
                            try {
                                withContext(Dispatchers.IO) {
                                    LyricStore(context).use { it.editProposed(selectedTrack.uri, plain, enhanced) }
                                }
                                message = "Edited lyrics saved as a proposal"
                            } catch (e: Exception) {
                                message = e.message ?: "Could not save edited lyrics"
                            } finally { reviewing = false }
                        }
                    },
                    onRestore = { restoreOriginal(selectedTrack) },
                    onNextReview = {
                        val next = tracks.firstOrNull { it.uri != selectedTrack.uri &&
                            it.inFilter(TrackFilter.REVIEW) }
                        if (next != null) selectedUri = next.uri
                        else { selectedUri = null; navigation.popBackStack() }
                    },
                    onRetry = { title, artist -> retry(selectedTrack, title, artist) },
                    onDismiss = { dismiss(selectedTrack) },
                    onClearMessage = { message = "" })
            }
        }
        composable("attention") {
            NeedsAttentionScreen(artifacts, LibrarySettings.backupDays(context), message, artifactBusy,
                onBack = { navigation.popBackStack() }, onRefresh = refreshArtifacts,
                onRestore = restoreArtifact, onDelete = { pendingDelete = it },
                onCleanup = cleanupArtifacts, onClearMessage = { message = "" })
        }
        composable("settings") {
            SourceSettings(onClose = { navigation.popBackStack() }, onSaved = { providersChanged ->
                autoAdd = LibrarySettings.autoAdd(context)
                enabledSources = LibrarySettings.sources(context)
                onThemeChanged()
                if (providersChanged) LibrarySettings.scanNow(context)
            })
        }
    }
    if (showPrivacyIntro) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.choose_where_lookup_data_goes)) },
        text = { Text(stringResource(R.string.for_each_music_file_you_choose_the_app_sends_its)) },
        confirmButton = { Button(onClick = {
            LibrarySettings.setOnlineLookup(context, true)
            showPrivacyIntro = false
            if (folder.isNotBlank()) LibrarySettings.scanNow(context)
        }) { Text(stringResource(R.string.enable_online_lookup)) } },
        dismissButton = { TextButton(onClick = { showPrivacyIntro = false }) { Text(stringResource(R.string.not_now)) } },
    )
    if (showAutoIntro && !showPrivacyIntro) AlertDialog(
        onDismissRequest = { LibrarySettings.setAutoIntroSeen(context); showAutoIntro = false },
        title = { Text(stringResource(R.string.your_music_stays_in_your_control)) },
        text = { Text(stringResource(R.string.automatic_writing_starts_off_the_app_searches_yo)) },
        confirmButton = { TextButton(onClick = {
            LibrarySettings.setAutoIntroSeen(context); showAutoIntro = false
        }) { Text(stringResource(R.string.continue_action)) } },
    )
    pendingDelete?.let { artifact ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_recovery_file)) },
            text = { Text(stringResource(R.string.delete_artifact_name_this_cannot_be_undone, artifact.name)) },
            confirmButton = { Button(onClick = {
                pendingDelete = null
                deleteArtifact(artifact)
            }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
internal fun MessageBanner(message: String, onClear: () -> Unit) {
    Surface(color = SeaGlass.copy(alpha = 0.13f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = PaleBlue,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text(stringResource(R.string.close)) }
        }
    }
}
