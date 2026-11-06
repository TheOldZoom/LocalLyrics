package com.music.lyricsapp

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MetadataEditorScreen(
    track: LocalTrack, onBack: () -> Unit, onSaved: (String) -> Unit,
    requestWrite: (Uri, () -> Unit, () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var before by remember(track.uri) { mutableStateOf<MetadataSnapshot?>(null) }
    var edited by remember(track.uri) { mutableStateOf(MetadataValues()) }
    var cover by remember(track.uri) { mutableStateOf<SelectedArtwork?>(null) }
    var removeCover by remember(track.uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    BackHandler(enabled = busy) { }
    fun chooseArtwork(uri: Uri?, fromAudio: Boolean) {
        if (uri == null) return
        busy = true
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    if (fromAudio) MetadataWriter.artworkFromAudio(context, uri)
                    else MetadataWriter.artworkFromImage(context, uri)
                }
                cover = selected
                removeCover = false
                message = ""
            } catch (e: Exception) {
                message = e.message ?: context.getString(R.string.metadata_artwork_read_failed)
            } finally { busy = false }
        }
    }
    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        chooseArtwork(uri, fromAudio = false)
    }
    val audioArtworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        chooseArtwork(uri, fromAudio = true)
    }
    LaunchedEffect(track.uri) {
        busy = true
        try {
            val loaded = withContext(Dispatchers.IO) { MetadataWriter.snapshot(context, track) }
            before = loaded; edited = loaded.values
        } catch (e: Exception) { message = e.message ?: context.getString(R.string.metadata_read_failed) }
        finally { busy = false }
    }
    val original = before
    val editable = MetadataWriter.editable(context, track)

    fun save() {
        if (busy || !editable || original == null) return
        busy = true
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    MetadataWriter.prepare(context, track, original, edited, cover, removeCover)
                }
                if (MediaLibrary.isIndexed(track.uri)) {
                    requestWrite(Uri.parse(track.uri), {
                            scope.launch {
                                try {
                                    val savedMessage = withContext(Dispatchers.IO) { MetadataWriter.commitMedia(context, prepared) }
                                    withContext(Dispatchers.IO) {
                                        LyricStore(context).use { it.recordMetadata(track.uri, prepared.after.values) }
                                    }
                                    onSaved(savedMessage)
                                } catch (e: Exception) {
                                    message = e.message ?: context.getString(R.string.metadata_save_failed)
                                } finally { busy = false }
                            }
                        }, {
                            scope.launch {
                                withContext(Dispatchers.IO) { MetadataWriter.discard(context, prepared) }
                                message = context.getString(R.string.media_permission_denied)
                                busy = false
                            }
                        }
                    )
                } else {
                    val savedMessage = withContext(Dispatchers.IO) { MetadataWriter.commitSaf(context, prepared) }
                    withContext(Dispatchers.IO) {
                        LyricStore(context).use { it.recordMetadata(track.uri, prepared.after.values) }
                    }
                    onSaved(savedMessage)
                    busy = false
                }
            } catch (e: Exception) {
                message = e.message ?: context.getString(R.string.metadata_save_failed)
                busy = false
            }
        }
    }

    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0), topBar = {
        CenterAlignedTopAppBar(title = { Text(stringResource(R.string.edit_track_metadata),
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            navigationIcon = { TextButton(onClick = onBack, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            } })
    }, bottomBar = {
        if (original != null) androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.surfaceContainer) {
            Button(onClick = { confirm = true }, enabled = editable && !busy &&
                (edited != original.values || cover != null || removeCover),
                modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.save))
            }
        }
    }) { scaffoldPadding ->
    Column(Modifier.fillMaxSize().padding(scaffoldPadding).verticalScroll(rememberScrollState())
        .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(track.name, style = MaterialTheme.typography.bodySmall)
        if (!editable) Text(stringResource(R.string.metadata_read_only_format), color = MutedBlue)
        if (message.isNotBlank()) Text(message, color = SoftCoral)
        if (original == null) {
            Text(if (busy) stringResource(R.string.metadata_loading) else stringResource(R.string.metadata_read_failed))
        } else {
            @Composable fun field(label: String, old: String, value: String, change: (String) -> Unit,
                                  multiline: Boolean = false) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(value = value, onValueChange = change,
                        label = { Text(label) }, enabled = editable && !busy,
                        minLines = if (multiline) 5 else 1,
                        maxLines = if (multiline) 12 else 1,
                        modifier = Modifier.fillMaxWidth())
                    if (value != old) Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.metadata_original_value, label, old.ifBlank {
                            context.getString(R.string.metadata_empty) }),
                            style = MaterialTheme.typography.bodySmall, color = MutedBlue,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { change(old) }, enabled = editable && !busy) {
                            Text(stringResource(R.string.timing_reset))
                        }
                    }
                }
            }
            SectionHeader(stringResource(R.string.section_basics))
            field(stringResource(R.string.metadata_title), original.values.title, edited.title,
                { edited = edited.copy(title = it) })
            field(stringResource(R.string.metadata_artist), original.values.artist, edited.artist,
                { edited = edited.copy(artist = it) })
            SectionHeader(stringResource(R.string.section_album))
            field(stringResource(R.string.metadata_album_artist), original.values.albumArtist, edited.albumArtist,
                { edited = edited.copy(albumArtist = it) })
            field(stringResource(R.string.metadata_album), original.values.album, edited.album,
                { edited = edited.copy(album = it) })
            field(stringResource(R.string.metadata_genre), original.values.genre, edited.genre,
                { edited = edited.copy(genre = it) })
            field(stringResource(R.string.metadata_year), original.values.year, edited.year,
                { edited = edited.copy(year = it) })
            field(stringResource(R.string.metadata_track_number), original.values.trackNumber, edited.trackNumber,
                { edited = edited.copy(trackNumber = it) })
            field(stringResource(R.string.metadata_disc_number), original.values.discNumber, edited.discNumber,
                { edited = edited.copy(discNumber = it) })
            SectionHeader(stringResource(R.string.section_credits))
            field(stringResource(R.string.metadata_composer), original.values.composer, edited.composer,
                { edited = edited.copy(composer = it) })
            field(stringResource(R.string.metadata_comment), original.values.comment, edited.comment,
                { edited = edited.copy(comment = it) }, true)
            SectionHeader(stringResource(R.string.metadata_lyrics))
            field(stringResource(R.string.metadata_lyrics), original.values.lyrics, edited.lyrics,
                { edited = edited.copy(lyrics = it) }, true)
            SectionHeader(stringResource(R.string.section_cover_art))
            Text(stringResource(R.string.metadata_original_artwork,
                if (original.coverDigest == null) stringResource(R.string.metadata_empty)
                else stringResource(R.string.metadata_present)))
            original.coverPreview?.let { preview -> Image(preview.asImageBitmap(),
                contentDescription = stringResource(R.string.metadata_original_artwork_description),
                modifier = Modifier.size(128.dp)) }
            Text(stringResource(R.string.metadata_edited_artwork,
                if (removeCover) stringResource(R.string.metadata_empty) else
                    cover?.sourceName ?: if (original.coverDigest == null)
                    stringResource(R.string.metadata_empty) else stringResource(R.string.metadata_unchanged)))
            cover?.preview?.let { preview -> Image(preview.asImageBitmap(),
                contentDescription = stringResource(R.string.metadata_edited_artwork_description),
                modifier = Modifier.size(128.dp)) }
            OutlinedButton(onClick = { artworkPicker.launch(arrayOf("image/jpeg", "image/png")) },
                enabled = editable && !busy) { Text(stringResource(R.string.metadata_choose_artwork)) }
            OutlinedButton(onClick = { audioArtworkPicker.launch(arrayOf("*/*")) },
                enabled = editable && !busy) { Text(stringResource(R.string.metadata_copy_artwork_from_audio)) }
            if (original.coverDigest != null || cover != null) TextButton(onClick = {
                cover = null; removeCover = original.coverDigest != null
            }, enabled = editable && !busy) { Text(stringResource(R.string.metadata_remove_artwork)) }
            Text(stringResource(R.string.metadata_save_explanation), color = MutedBlue,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    }
    if (confirm) ConfirmDialog(
        title = stringResource(R.string.metadata_confirm_title),
        body = stringResource(R.string.metadata_confirm_body, track.name),
        confirmLabel = stringResource(R.string.save),
        onConfirm = { confirm = false; save() },
        onDismiss = { confirm = false })
}
