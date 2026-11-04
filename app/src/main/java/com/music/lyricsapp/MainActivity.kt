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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DeepBlue = Color(0xFF142B3A)
private val CardBlue = Color(0xFF203B4C)
private val PaleBlue = Color(0xFFE7F2F0)
private val SeaGlass = Color(0xFF8BD4C6)
private val WarmGold = Color(0xFFFFCA81)
private val SoftCoral = Color(0xFFFFA99D)
private val MutedBlue = Color(0xFFAEC5CC)

private val LyricsColors = darkColorScheme(
    primary = SeaGlass,
    onPrimary = DeepBlue,
    secondary = WarmGold,
    background = DeepBlue,
    surface = CardBlue,
    onSurface = PaleBlue,
    onSurfaceVariant = MutedBlue,
    error = SoftCoral,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibrarySettings.schedule(this)
        if (LibrarySettings.folder(this).isNotBlank()) LibrarySettings.scanNow(this)
        setContent {
            MaterialTheme(colorScheme = LyricsColors) {
                Surface(Modifier.fillMaxSize(), color = DeepBlue) { LyricsScreen() }
            }
        }
    }
}

private enum class TrackFilter(val title: String) {
    ALL("All"), REVIEW("To review"), CORRECT("Correct"), WRONG("Wrong"),
    IN_FILE("In file"), NEEDS_HELP("Needs help")
}

private fun LocalTrack.inFilter(filter: TrackFilter): Boolean = when (filter) {
    TrackFilter.ALL -> true
    TrackFilter.REVIEW -> proposed.isNotBlank() && review.isBlank() && status != "dismissed"
    TrackFilter.CORRECT -> review == "correct"
    TrackFilter.WRONG -> review == "wrong"
    TrackFilter.IN_FILE -> existing.isNotBlank()
    TrackFilter.NEEDS_HELP -> status in setOf("miss", "error", "unsupported")
}

private fun LocalTrack.displayStatus(): Pair<String, Color> = when {
    review == "wrong" -> "Marked wrong" to SoftCoral
    review == "correct" -> "Marked correct" to SeaGlass
    status == "applied" -> "Added · review it" to WarmGold
    status == "ready" -> "Fetched · review it" to WarmGold
    status == "existing" -> "In file" to SeaGlass
    status == "miss" -> "No match found" to MutedBlue
    status == "error" -> "Needs attention" to SoftCoral
    status == "unsupported" -> "Format not supported" to MutedBlue
    status == "dismissed" -> "Dismissed" to MutedBlue
    else -> "Searching" to MutedBlue
}

@Composable
private fun LyricsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var candidates by remember { mutableStateOf<List<ProviderCandidate>>(emptyList()) }
    var selectedUri by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf(LibrarySettings.folder(context)) }
    var autoAdd by remember { mutableStateOf(LibrarySettings.autoAdd(context)) }
    var enabledSources by remember { mutableStateOf(LibrarySettings.sources(context)) }
    var message by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(TrackFilter.ALL) }
    var exportPayload by remember { mutableStateOf("") }

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
                tracks = emptyList()
                filter = TrackFilter.ALL
                LibrarySettings.scanNow(context)
                message = "Scanning selected folder"
            } catch (e: Exception) {
                message = e.message ?: "Could not keep folder access"
            }
        }
    }
    LaunchedEffect(folder) {
        while (true) {
            tracks = withContext(Dispatchers.IO) { LyricStore(context).use { it.all() } }
            delay(2_000)
        }
    }
    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: run {
            candidates = emptyList()
            return@LaunchedEffect
        }
        while (true) {
            candidates = withContext(Dispatchers.IO) { LyricStore(context).use { it.candidates(uri) } }
            delay(900)
        }
    }

    val selectedTrack = selectedUri?.let { uri -> tracks.firstOrNull { it.uri == uri } }
    BackHandler(enabled = selectedTrack != null || settings) {
        if (settings) settings = false else selectedUri = null
    }

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
        if (!applying && !reviewing) {
            reviewing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { LyricStore(context).use { it.setReview(track.uri, verdict) } }
                    tracks = tracks.map { if (it.uri == track.uri) it.copy(review = verdict) else it }
                } catch (e: Exception) {
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
                        LyricStore(context).use { store ->
                            val savedCandidates = store.candidates(track.uri)
                            store.scanned(track.copy(uri = outcome.uri, name = outcome.name,
                                signature = outcome.signature,
                                status = "applied", existing = track.proposed))
                            savedCandidates.forEach { store.setCandidate(outcome.uri, it) }
                            store.update(outcome.uri, "applied", track.source, track.proposed, track.enhanced)
                            if (outcome.replaced) store.delete(track.uri)
                            else store.update(track.uri, "dismissed")
                        }
                    }
                    message = outcome.message
                    selectedUri = null
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
                val updated = withContext(Dispatchers.IO) {
                    LyricStore(context).use { it.selectCandidate(track.uri, source) }
                }
                tracks = tracks.map { if (it.uri == track.uri) updated else it }
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
                candidates = emptyList()
                tracks = tracks.map { if (it.uri == track.uri) it.copy(status = "new", source = "",
                    proposed = "", enhanced = "", review = "", selectedProvider = "", providerScan = "") else it }
                LibrarySettings.scanNow(context)
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
                message = "Result dismissed"
            } catch (e: Exception) {
                message = e.message ?: "Could not dismiss result"
            }
        }
    }

    if (selectedTrack == null) {
        LibraryHome(tracks, folder, autoAdd, filter, message,
            onFilter = { filter = it },
            onPickFolder = { folderPicker.launch(null) },
            onScan = { LibrarySettings.scanNow(context); message = "Scanning folder" },
            onSettings = { settings = true },
            onOpen = { selectedUri = it.uri },
            onClearMessage = { message = "" })
    } else {
        TrackDetail(selectedTrack, candidates, enabledSources, message, applying, reviewing,
            onBack = { selectedUri = null },
            onSelectProvider = { selectProvider(selectedTrack, it) },
            onRefreshProviders = { refreshProviders(selectedTrack) },
            onOpenSources = { settings = true },
            onReview = { markReview(selectedTrack, it) },
            onExportFetched = { asText -> exportLyrics(selectedTrack, false, asText) },
            onExportEmbedded = { asText -> exportLyrics(selectedTrack, true, asText) },
            onApply = { applyLyrics(selectedTrack) },
            onRetry = { title, artist -> retry(selectedTrack, title, artist) },
            onDismiss = { dismiss(selectedTrack) },
            onClearMessage = { message = "" })
    }

    if (settings) SourceSettings(onClose = { settings = false }, onSaved = { providersChanged ->
        autoAdd = LibrarySettings.autoAdd(context)
        enabledSources = LibrarySettings.sources(context)
        if (providersChanged) scope.launch {
            withContext(Dispatchers.IO) { LyricStore(context).use { it.invalidateProviderScans() } }
            LibrarySettings.scanNow(context)
        }
    })
}

@Composable
private fun LibraryHome(
    tracks: List<LocalTrack>, folder: String, autoAdd: Boolean, filter: TrackFilter, message: String,
    onFilter: (TrackFilter) -> Unit, onPickFolder: () -> Unit, onScan: () -> Unit,
    onSettings: () -> Unit, onOpen: (LocalTrack) -> Unit, onClearMessage: () -> Unit,
) {
    val visible = remember(tracks, filter) { tracks.filter { it.inFilter(filter) } }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lyrics", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = PaleBlue)
                        Text("Your music, with the words in view", color = MutedBlue)
                    }
                    TextButton(onClick = onSettings) { Text("Sources") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPickFolder) { Text(if (folder.isBlank()) "Choose music folder" else "Change folder") }
                    OutlinedButton(onClick = onScan, enabled = folder.isNotBlank()) { Text("Scan") }
                }
                if (folder.isNotBlank()) {
                    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.REVIEW) }, "To review", WarmGold)
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.WRONG) }, "Wrong", SoftCoral)
                            SummaryCount(tracks.count { it.inFilter(TrackFilter.IN_FILE) }, "In files", SeaGlass)
                        }
                    }
                    Text(if (autoAdd) "Automatic writing is on. Review added lyrics for mistakes."
                        else "Automatic writing is off. Review a result before adding it.",
                        color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                }
                if (message.isNotBlank()) MessageBanner(message, onClearMessage)
            }
        }
        if (folder.isNotBlank()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TrackFilter.entries) { option ->
                        FilterChip(selected = filter == option, onClick = { onFilter(option) },
                            label = { Text("${option.title} ${tracks.count { it.inFilter(option) }}") })
                    }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (folder.isBlank()) "Start with your music folder"
                            else if (tracks.isEmpty()) "No music scanned yet"
                            else "Nothing in ${filter.title.lowercase()}",
                            style = MaterialTheme.typography.titleMedium)
                        Text(if (folder.isBlank()) "Choose a folder to find lyrics already in your files and fetch missing ones."
                            else if (tracks.isEmpty()) "Tap Scan to look through the selected folder."
                            else "Choose another filter to see the rest of your music.", color = MutedBlue)
                    }
                }
            }
        } else {
            items(visible, key = { it.uri }) { track -> TrackRow(track, onClick = { onOpen(track) }) }
        }
    }
}

@Composable
private fun SummaryCount(count: Int, label: String, color: Color) {
    Column {
        Text(count.toString(), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MutedBlue)
    }
}

@Composable
private fun StatusPill(track: LocalTrack) {
    val (label, color) = track.displayStatus()
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

@Composable
private fun TrackRow(track: LocalTrack, onClick: () -> Unit) {
    val preview = remember(track.proposed, track.existing, track.error, track.status) {
        val raw = track.proposed.ifBlank { track.existing }
        if (raw.isNotBlank()) lyricsAsText(raw).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        else track.error.ifBlank { if (track.status == "new") "Waiting for a lyric result" else "Open for details" }
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 5.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist.ifBlank { "Unknown artist" }, color = MutedBlue,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                StatusPill(track)
            }
            if (preview.isNotBlank()) Text(preview, color = PaleBlue.copy(alpha = 0.86f),
                fontFamily = if (track.proposed.isNotBlank() || track.existing.isNotBlank()) FontFamily.Serif else FontFamily.Default,
                fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (track.source.isNotBlank()) Text("From ${track.source}", color = MutedBlue,
                style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider(color = MutedBlue.copy(alpha = 0.2f))
    }
}

@Composable
private fun TrackDetail(
    track: LocalTrack, candidates: List<ProviderCandidate>, enabledSources: Set<LyricsSource>,
    message: String, applying: Boolean, reviewing: Boolean, onBack: () -> Unit,
    onSelectProvider: (LyricsSource) -> Unit, onRefreshProviders: () -> Unit,
    onOpenSources: () -> Unit,
    onReview: (String) -> Unit, onExportFetched: (Boolean) -> Unit,
    onExportEmbedded: (Boolean) -> Unit, onApply: () -> Unit,
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
    val fetchedIsInFile = track.proposed.isNotBlank() && track.existing.trim() == track.proposed.trim()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !applying) { Text("‹  Library") }
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
                LyricsPanel(if (fetchedIsInFile) "Fetched lyrics · in file" else "Fetched lyrics",
                    track.proposed, track.source.ifBlank { "Source unknown" },
                    onExportRaw = { if (fetchedIsInFile) onExportEmbedded(false) else onExportFetched(false) },
                    onExportText = { if (fetchedIsInFile) onExportEmbedded(true) else onExportFetched(true) },
                    enhanced = track.enhanced, player = player)
                if (track.status != "dismissed") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Are these the right words?", style = MaterialTheme.typography.titleMedium)
                        Text("Only your review marks a result correct or wrong. A provider match is not a guarantee.",
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = track.review == "correct", onClick = { onReview("correct") },
                                enabled = !applying && !reviewing,
                                label = { Text("Correct") })
                            FilterChip(selected = track.review == "wrong", onClick = { onReview("wrong") },
                                enabled = !applying && !reviewing,
                                label = { Text("Wrong") })
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
                    player = player)
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
                        Text("Search again", style = MaterialTheme.typography.titleMedium)
                        Text("Change the title or artist if the result was for another recording.",
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = matchTitle, onValueChange = { matchTitle = it },
                            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = matchArtist, onValueChange = { matchArtist = it },
                            label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { onRetry(matchTitle, matchArtist) },
                            enabled = !applying && !reviewing && matchTitle.isNotBlank() && matchArtist.isNotBlank()) {
                            Text("Search again")
                        }
                    }
                }
            }
            if (track.status == "ready" && track.proposed.isNotBlank()) {
                TextButton(onClick = onDismiss, enabled = !applying && !reviewing) { Text("Dismiss result") }
            }
            Spacer(Modifier.height(20.dp))
        }
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
                    Text("Listen and compare", style = MaterialTheme.typography.titleSmall)
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
    val bySource = remember(candidates) { candidates.associateBy { it.source } }
    Surface(color = CardBlue, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Compare providers", style = MaterialTheme.typography.titleMedium)
            Text("Choose a result to read while the track plays. Your correct and wrong marks stay with each provider.",
                color = MutedBlue, style = MaterialTheme.typography.bodySmall)
            Column(Modifier.fillMaxWidth().heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
                LyricsSource.offered.forEach { source ->
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
                TextButton(onClick = onOpenSources) { Text("Choose providers") }
                TextButton(onClick = onRefresh) { Text("Fetch again") }
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
        clipboard.setPrimaryClip(ClipData.newPlainText("Lyrics", text))
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
                    Text("Copy words")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onExportRaw) { Text(if (lines.isEmpty()) "Export lyrics" else "Export LRC") }
                if (lines.isNotEmpty()) TextButton(onClick = onExportText) { Text("Export text") }
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
private fun lyricsAsText(raw: String): String =
    LrcLib.parseLrc(raw).map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
        .ifBlank { raw }

@Composable
private fun MessageBanner(message: String, onClear: () -> Unit) {
    Surface(color = SeaGlass.copy(alpha = 0.13f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = PaleBlue,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClear) { Text("Close") }
        }
    }
}

@Composable
private fun SourceSettings(onClose: () -> Unit, onSaved: (Boolean) -> Unit) {
    val context = LocalContext.current
    val initialSources = remember { LibrarySettings.sources(context) }
    val initialKey = remember { LibrarySettings.paxKey(context) }
    var chosen by remember { mutableStateOf(initialSources) }
    var key by remember { mutableStateOf(initialKey) }
    var autoAdd by remember { mutableStateOf(LibrarySettings.autoAdd(context)) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Lyrics sources") },
        text = {
            Column(Modifier.height(430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().clickable { autoAdd = !autoAdd },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Write lyrics automatically", fontWeight = FontWeight.Medium)
                        Text("New lyrics may be added before you review them.",
                            color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoAdd, onCheckedChange = { autoAdd = it })
                }
                OutlinedTextField(value = key, onValueChange = { key = it },
                    label = { Text("PaxSenix API key (optional)") }, modifier = Modifier.fillMaxWidth())
                Text("Providers", style = MaterialTheme.typography.titleSmall)
                Text("Every enabled provider is checked for each supported music file. Compare their results on the track screen.",
                    color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                LyricsSource.offered.forEach { source ->
                    Row(Modifier.fillMaxWidth().clickable {
                        chosen = if (source in chosen) chosen - source else chosen + source
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Text(source.label, modifier = Modifier.weight(1f))
                        Switch(checked = source in chosen, onCheckedChange = {
                            chosen = if (it) chosen + source else chosen - source
                        })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = {
            LibrarySettings.setSources(context, chosen)
            LibrarySettings.setPaxKey(context, key)
            LibrarySettings.setAutoAdd(context, autoAdd)
            onSaved(chosen != initialSources || key != initialKey)
            onClose()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
