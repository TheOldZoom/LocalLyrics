package com.music.lyricsapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.lyricsapp.core.LyricsSource
import com.music.lyricsapp.core.LrcLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LibrarySettings.schedule(this)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { LyricsScreen() } } }
    }
}

@Composable
private fun LyricsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }
    var selected by remember { mutableStateOf<LocalTrack?>(null) }
    var folder by remember { mutableStateOf(LibrarySettings.folder(context)) }
    var message by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var exportPayload by remember { mutableStateOf("") }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(exportPayload)
                } ?: error("Could not create the lyrics file")
                message = "Lyrics exported"
            } catch (e: Exception) { message = e.message ?: "Could not export lyrics" }
            selected = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if (folder != uri.toString()) LyricStore(context).use { it.clear() }
                LibrarySettings.setFolder(context, uri.toString())
                folder = uri.toString()
                LibrarySettings.scanNow(context)
                message = "Scanning selected folder"
            } catch (e: Exception) { message = e.message ?: "Could not keep folder access" }
        }
    }
    LaunchedEffect(folder) {
        while (true) {
            tracks = withContext(Dispatchers.IO) { LyricStore(context).use { it.all() } }
            delay(2_000)
        }
    }

    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Lyrics", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (folder.isBlank()) "Choose a music folder to begin" else "Local music · ${tracks.size} files")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(null) }) { Text("Choose folder") }
            Button(onClick = { LibrarySettings.scanNow(context); message = "Scanning folder" }, enabled = folder.isNotBlank()) { Text("Scan") }
            TextButton(onClick = { settings = true }) { Text("Sources") }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text("New files are scanned and lyrics are added automatically. Open a track to review or retry.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(tracks, key = { it.uri }) { track ->
                Column(Modifier.fillMaxWidth().clickable { selected = track }.padding(vertical = 10.dp)) {
                    Text(track.title, fontWeight = FontWeight.SemiBold)
                    Text("${track.artist.ifBlank { "Unknown artist" }} · ${track.status} · ${track.name}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    selected?.let { track ->
        var matchTitle by remember(track.uri) { mutableStateOf(track.title) }
        var matchArtist by remember(track.uri) { mutableStateOf(track.artist) }
        var exportError by remember(track.uri) { mutableStateOf("") }
        val exportEmbedded: (Boolean) -> Unit = { asText ->
            scope.launch {
                try {
                    exportError = ""
                    val extension = track.name.substringAfterLast('.', "").lowercase()
                    val embedded = RawEmbeddedLyrics.read(context, Uri.parse(track.uri), extension)
                        ?.takeIf { it.isNotBlank() }
                        ?: error("No embedded lyrics remain in this file; scan the folder again")
                    val timed = LrcLib.parseLrc(embedded).isNotEmpty()
                    exportPayload = if (asText) lyricsAsText(embedded) else embedded
                    val fileType = if (asText || !timed) "txt" else "lrc"
                    val fileName = track.name.substringBeforeLast('.', track.name)
                    exportPicker.launch("$fileName.$fileType")
                } catch (e: Exception) {
                    exportError = e.message ?: "Could not export embedded lyrics"
                }
            }
        }
        AlertDialog(onDismissRequest = { if (!applying) selected = null },
            title = { Text(track.title) },
            text = {
                Column(Modifier.height(390.dp).verticalScroll(rememberScrollState())) {
                    Text("${track.artist} · ${track.name}")
                    OutlinedTextField(value = matchTitle, onValueChange = { matchTitle = it },
                        label = { Text("Search title") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = matchArtist, onValueChange = { matchArtist = it },
                        label = { Text("Search artist") }, modifier = Modifier.fillMaxWidth())
                    if (track.existing.isNotBlank()) {
                        Spacer(Modifier.height(10.dp)); Text("Existing lyrics", fontWeight = FontWeight.Bold)
                        Text(track.existing)
                        Row {
                            TextButton(onClick = { exportEmbedded(false) }) { Text("Export embedded") }
                            TextButton(onClick = { exportEmbedded(true) }) { Text("Export text") }
                        }
                        if (exportError.isNotBlank()) Text(exportError, color = MaterialTheme.colorScheme.error)
                    }
                    if (track.proposed.isNotBlank() || track.existing.isBlank()) {
                        val timing = when {
                            track.enhanced.isNotBlank() -> "word timed"
                            track.proposed.contains(Regex("\\[\\d+:\\d+")) -> "line timed"
                            else -> "plain"
                        }
                        Spacer(Modifier.height(10.dp)); Text("Found from ${track.source.ifBlank { "—" }} · $timing", fontWeight = FontWeight.Bold)
                        Text(track.proposed.ifBlank { track.error.ifBlank { "Lyrics are still being fetched." } })
                        if (track.proposed.isNotBlank()) {
                            Row {
                                TextButton(onClick = {
                                    exportPayload = track.proposed
                                    exportPicker.launch("${track.title}.lrc")
                                }) { Text("Export LRC") }
                                TextButton(onClick = {
                                    exportPayload = lyricsAsText(track.proposed)
                                    exportPicker.launch("${track.title}.txt")
                                }) { Text("Export text") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(enabled = track.status == "ready" && !applying, onClick = {
                    applying = true
                    scope.launch {
                        try {
                            val outcome = ApprovedWriter.apply(context, track)
                            withContext(Dispatchers.IO) {
                                LyricStore(context).use { store ->
                                    if (outcome.replaced) store.delete(track.uri)
                                    else store.update(track.uri, "dismissed")
                                    store.scanned(track.copy(uri = outcome.uri, signature = outcome.signature,
                                        status = "applied", existing = track.proposed))
                                    store.update(outcome.uri, "applied", track.source, track.proposed, track.enhanced)
                                }
                            }
                            message = outcome.message
                            selected = null
                        } catch (e: Exception) { message = e.message ?: "Could not write lyrics" }
                        finally { applying = false }
                    }
                }) { Text(if (track.existing.isBlank()) "Add lyrics" else "Replace lyrics") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                LyricStore(context).use { it.editMatch(track.uri, matchTitle, matchArtist) }
                            }
                            LibrarySettings.scanNow(context)
                            selected = null
                        }
                    }) { Text("Retry") }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) { LyricStore(context).use { it.update(track.uri, "dismissed") } }
                        selected = null
                    }) { Text("Dismiss") }
                }
            })
    }

    if (settings) SourceSettings(onClose = { settings = false })
}

/** Keep the original text when the metadata contains lyrics without LRC timestamps. */
private fun lyricsAsText(raw: String): String =
    LrcLib.parseLrc(raw).map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
        .ifBlank { raw }

@Composable
private fun SourceSettings(onClose: () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(LibrarySettings.sources(context)) }
    var key by remember { mutableStateOf(LibrarySettings.paxKey(context)) }
    var autoAdd by remember { mutableStateOf(LibrarySettings.autoAdd(context)) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Lyrics sources") },
        text = {
            Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().clickable { autoAdd = !autoAdd }) {
                    Checkbox(checked = autoAdd, onCheckedChange = { autoAdd = it })
                    Text("Automatically add lyrics to files without them", Modifier.padding(top = 12.dp))
                }
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("PaxSenix API key (optional)") })
                LyricsSource.offered.forEach { source ->
                    Row(Modifier.fillMaxWidth().clickable {
                        chosen = if (source in chosen) chosen - source else chosen + source
                    }) {
                        Checkbox(checked = source in chosen, onCheckedChange = {
                            chosen = if (it) chosen + source else chosen - source
                        })
                        Text(source.label, Modifier.padding(top = 12.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = {
            LibrarySettings.setSources(context, chosen)
            LibrarySettings.setPaxKey(context, key)
            LibrarySettings.setAutoAdd(context, autoAdd)
            onClose()
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
