package com.music.lyricsapp

import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
internal fun ArtworkTile(title: String, modifier: Modifier = Modifier, audioUri: String = "") {
    val context = LocalContext.current
    val artwork by produceState<Bitmap?>(initialValue = null, audioUri) {
        value = if (audioUri.isBlank()) null else withContext(Dispatchers.IO) {
            LocalArtwork.load(context, audioUri)
        }
    }
    val colors = listOf(
        Color(0xFF245F76) to Color(0xFF8AD7C5),
        Color(0xFF705B87) to Color(0xFFE8ACB6),
        Color(0xFF7C5842) to Color(0xFFFFCE88),
        Color(0xFF3A6958) to Color(0xFFB7D987),
    )
    val pair = colors[(title.hashCode().toLong().let { kotlin.math.abs(it) } % colors.size).toInt()]
    Box(modifier.clearAndSetSemantics { }.clip(MaterialTheme.shapes.large),
        contentAlignment = Alignment.Center) {
        if (artwork != null) Image(artwork!!.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize()
            .background(Brush.linearGradient(listOf(pair.first, pair.second))),
            contentAlignment = Alignment.Center) {
            Text(title.trim().firstOrNull()?.uppercase() ?: "♪", color = Color.White,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private object LocalArtwork {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses = mutableSetOf<String>()

    @Synchronized fun load(context: Context, uri: String): Bitmap? {
        cache.get(uri)?.let { return it }
        if (uri in misses) return null
        val bitmap = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                val bytes = retriever.embeddedPicture ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val sample = generateSequence(1) { it * 2 }
                    .first { bounds.outWidth / it <= 160 && bounds.outHeight / it <= 160 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { retriever.release() }
        }.getOrNull()
        if (bitmap == null) misses += uri else cache.put(uri, bitmap)
        return bitmap
    }
}

@Composable
internal fun DraftShelf(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val dao = remember { WorkspaceDatabase.get(context).workspaceDao() }
    val drafts by dao.observeDrafts().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.maker), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.maker_intro), color = MutedBlue)
                Button(onClick = {
                    val id = UUID.randomUUID().toString()
                    scope.launch { dao.saveDraft(DraftEntity(id)); onOpen(id) }
                }) { Text(stringResource(R.string.new_draft)) }
            }
        }
        if (drafts.isEmpty()) item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                Text(stringResource(R.string.no_drafts), Modifier.padding(20.dp))
            }
        }
        items(drafts, key = { it.id }) { draft ->
            Surface(onClick = { onOpen(draft.id) }, color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ArtworkTile(draft.title.ifBlank { draft.artist }, Modifier.size(54.dp), draft.audioUri)
                    Column(Modifier.weight(1f)) {
                        Text(draft.title.ifBlank { stringResource(R.string.untitled_draft) },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(draft.artist.ifBlank { stringResource(R.string.add_artist) },
                            color = MutedBlue, maxLines = 1)
                    }
                    Text("${DraftFormat.decode(draft.linesJson).size}", color = MutedBlue)
                }
            }
        }
    }
}

@Composable
internal fun MakerEditor(draftId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { WorkspaceDatabase.get(context).workspaceDao() }
    val fromRoom by remember(draftId) { dao.observeDraft(draftId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val initial = fromRoom
    if (initial == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.loading_draft))
        }
        return
    }
    MakerEditorContent(initial, dao, onBack)
}

@Composable
private fun MakerEditorContent(initial: DraftEntity, dao: WorkspaceDao, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var lines by remember(initial.id) { mutableStateOf(DraftFormat.decode(initial.linesJson)) }
    var mode by remember(initial.id) { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedLine by remember(initial.id) { mutableStateOf(0) }
    var exportBody by remember { mutableStateOf("") }
    var exportName by remember { mutableStateOf("") }
    var undoStack by remember(initial.id) { mutableStateOf<List<Pair<DraftEntity, List<DraftLine>>>>(emptyList()) }
    var redoStack by remember(initial.id) { mutableStateOf<List<Pair<DraftEntity, List<DraftLine>>>>(emptyList()) }
    val currentDraft by rememberUpdatedState(draft.copy(linesJson = DraftFormat.encode(lines)))
    fun checkpoint() {
        undoStack = (undoStack + (draft to lines)).takeLast(100)
        redoStack = emptyList()
    }
    fun updateDraft(change: DraftEntity) {
        if (change == draft) return
        checkpoint()
        draft = change.copy(updatedAt = System.currentTimeMillis())
    }
    fun updateLines(change: List<DraftLine>) {
        if (change == lines) return
        checkpoint()
        lines = change
    }
    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + (draft to lines)).takeLast(100)
        draft = previous.first; lines = previous.second
    }
    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + (draft to lines)).takeLast(100)
        draft = next.first; lines = next.second
    }

    LaunchedEffect(draft, lines) {
        delay(350)
        dao.saveDraft(draft.copy(linesJson = DraftFormat.encode(lines)))
    }
    DisposableEffect(initial.id) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch { dao.saveDraft(currentDraft) }
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val body = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error(context.getString(R.string.could_not_read_file))
                }
                updateLines(DraftFormat.import(body))
                Regex("""(?m)^\[ti:(.*)]$""").find(body)?.groupValues?.get(1)?.let {
                    updateDraft(draft.copy(title = it))
                }
                Regex("""(?m)^\[ar:(.*)]$""").find(body)?.groupValues?.get(1)?.let {
                    updateDraft(draft.copy(artist = it))
                }
                message = context.getString(R.string.imported_lines)
            }.onFailure { message = it.message ?: context.getString(R.string.could_not_read_file) }
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                updateDraft(draft.copy(audioUri = uri.toString()))
            }.onFailure { message = it.message ?: context.getString(R.string.could_not_read_file) }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                        it.write(exportBody)
                    } ?: error(context.getString(R.string.could_not_export))
                }
                message = context.getString(R.string.lyrics_exported)
            }.onFailure { message = it.message ?: context.getString(R.string.could_not_export) }
        }
    }
    val parentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val audioUri = draft.audioUri.takeIf { it.isNotBlank() }
                    ?: error(context.getString(R.string.attach_audio_first))
                val file = DocumentFile.fromSingleUri(context, Uri.parse(audioUri))
                    ?: error(context.getString(R.string.could_not_read_file))
                val name = file.name ?: error(context.getString(R.string.could_not_read_file))
                val existing = withContext(Dispatchers.IO) {
                    RawEmbeddedLyrics.read(context, file.uri, name.substringAfterLast('.').lowercase())
                }.orEmpty()
                val portable = DraftFormat.portable(draft.title, draft.artist, lines)
                val enhanced = DraftFormat.enhanced(draft.title, draft.artist, lines)
                    .takeIf { lines.any { line -> line.wordTimes.any { it != null } } }.orEmpty()
                val candidate = LocalTrack(audioUri, name, draft.title, draft.artist, draft.album,
                    0, "${file.length()}:${file.lastModified()}:$name", "ready", "Maker",
                    portable, enhanced, existing, "")
                val outcome = ApprovedWriter.apply(context, candidate, tree.toString())
                message = outcome.message
                if (outcome.replaced) updateDraft(draft.copy(audioUri = outcome.uri))
            }.onFailure { message = it.message ?: context.getString(R.string.could_not_embed) }
        }
    }

    val player = remember(draft.audioUri) {
        draft.audioUri.takeIf { it.isNotBlank() }?.let { LocalAudioPlayer(context, Uri.parse(it)) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player) { onDispose { player?.release() } }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) { while (player != null) { player.tick(); delay(80) } }
    val currentIndex = lines.indices.filter { index ->
        val time = lines[index].timeMs
        time != null && time <= (player?.positionMs ?: 0)
    }.maxByOrNull { lines[it].timeMs ?: -1L } ?: -1
    val readyForLrc = draft.title.isNotBlank() && draft.artist.isNotBlank() &&
        lines.isNotEmpty() && lines.all { it.text.isNotBlank() && it.timeMs != null }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch { dao.saveDraft(currentDraft); onBack() }
            }) { Text(stringResource(R.string.maker)) }
            TextButton(onClick = ::undo, enabled = undoStack.isNotEmpty()) {
                Text(stringResource(R.string.undo))
            }
            TextButton(onClick = ::redo, enabled = redoStack.isNotEmpty()) {
                Text(stringResource(R.string.redo))
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ArtworkTile(draft.title.ifBlank { draft.artist }, Modifier.size(88.dp), draft.audioUri)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lyric_draft),
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(draft.origin.ifBlank { stringResource(R.string.your_words) }, color = MutedBlue)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.title, { updateDraft(draft.copy(title = it)) },
                        label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                    OutlinedTextField(draft.artist, { updateDraft(draft.copy(artist = it)) },
                        label = { Text(stringResource(R.string.artist)) }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                    OutlinedTextField(draft.album, { updateDraft(draft.copy(album = it)) },
                        label = { Text(stringResource(R.string.album_optional)) }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true)
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(!mode, { mode = false }, label = { Text(stringResource(R.string.text_view)) }) }
                    item { FilterChip(mode, { mode = true }, label = { Text(stringResource(R.string.timing_view)) }) }
                    item { OutlinedButton(onClick = { importPicker.launch(arrayOf("text/*", "application/octet-stream")) }) {
                        Text(stringResource(R.string.import_lrc))
                    } }
                    item { OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                        if (pasted.isNotBlank()) updateLines(lines + DraftFormat.import(pasted))
                        else message = context.getString(R.string.clipboard_empty)
                    }) { Text(stringResource(R.string.paste_text)) } }
                }
            }
            if (!mode) {
                item {
                    OutlinedButton(onClick = { updateLines(lines + DraftLine(text = "")) }) {
                        Text(stringResource(R.string.add_line))
                    }
                }
                itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            OutlinedTextField(line.text, { value ->
                                updateLines(lines.toMutableList().apply {
                                    this[index] = line.copy(text = value, wordTimes = emptyList())
                                })
                            }, label = { Text(stringResource(R.string.line_number, index + 1)) },
                                modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                TextButton(onClick = {
                                    if (index > 0) updateLines(lines.toMutableList().apply {
                                        val item = removeAt(index); add(index - 1, item)
                                    })
                                }, enabled = index > 0,
                                    modifier = Modifier.semantics {
                                        contentDescription = context.getString(R.string.move_line_up, index + 1)
                                    }) { Text("↑") }
                                TextButton(onClick = {
                                    if (index < lines.lastIndex) updateLines(lines.toMutableList().apply {
                                        val item = removeAt(index); add(index + 1, item)
                                    })
                                }, enabled = index < lines.lastIndex,
                                    modifier = Modifier.semantics {
                                        contentDescription = context.getString(R.string.move_line_down, index + 1)
                                    }) { Text("↓") }
                                TextButton(onClick = { updateLines(lines.filterIndexed { i, _ -> i != index }) }) {
                                    Text(stringResource(R.string.delete))
                                }
                            }
                        }
                    }
                }
            } else {
                if (lines.isEmpty()) item { Text(stringResource(R.string.add_words_in_text)) }
                itemsIndexed(lines, key = { _, line -> line.id }) { index, line ->
                    Surface(onClick = { selectedLine = index },
                        color = if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer
                            else if (index == selectedLine) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${line.timeMs?.let { DraftFormat.stamp(it, false) } ?: "--:--"}  ${line.text}",
                                style = if (index == currentIndex) MaterialTheme.typography.titleLarge
                                    else MaterialTheme.typography.bodyLarge,
                                fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Normal)
                            if (index == selectedLine) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    itemsIndexed(DraftFormat.words(line)) { wordIndex, word ->
                                        FilterChip(line.wordTimes.getOrNull(wordIndex) != null,
                                            onClick = {
                                                val now = player?.positionMs ?: 0L
                                                updateLines(lines.toMutableList().apply {
                                                    this[index] = line.copy(wordTimes =
                                                        MutableList(DraftFormat.words(line).size) { i ->
                                                            if (i == wordIndex) now else line.wordTimes.getOrNull(i)
                                                        })
                                                })
                                            }, enabled = player?.ready == true,
                                            label = { Text(word) },
                                            modifier = Modifier.semantics {
                                                contentDescription = context.getString(R.string.stamp_word, word)
                                            })
                                    }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    item {
                                    TextButton(onClick = {
                                        player?.seekTo((line.timeMs ?: 0L).coerceAtLeast(0))
                                    }, enabled = player?.ready == true && line.timeMs != null) {
                                        Text(stringResource(R.string.seek_line))
                                    }
                                    }
                                    item {
                                    TextButton(onClick = {
                                        updateLines(lines.toMutableList().apply {
                                            this[index] = line.copy(timeMs = line.timeMs?.minus(100)?.coerceAtLeast(0),
                                                wordTimes = line.wordTimes.map { it?.minus(100)?.coerceAtLeast(0) })
                                        })
                                    }, enabled = line.timeMs != null) { Text(stringResource(R.string.timing_minus_100)) }
                                    }
                                    item {
                                    TextButton(onClick = {
                                        updateLines(lines.toMutableList().apply {
                                            this[index] = line.copy(timeMs = line.timeMs?.plus(100),
                                                wordTimes = line.wordTimes.map { it?.plus(100) })
                                        })
                                    }, enabled = line.timeMs != null) { Text(stringResource(R.string.timing_plus_100)) }
                                    }
                                    item {
                                    TextButton(onClick = {
                                        updateLines(lines.toMutableList().apply {
                                            this[index] = line.copy(timeMs = player?.positionMs)
                                        })
                                    }, enabled = player?.ready == true) { Text(stringResource(R.string.correct_stamp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!readyForLrc) Text(stringResource(R.string.complete_lrc_hint),
                        color = MutedBlue, style = MaterialTheme.typography.bodySmall)
                    if (message.isNotBlank()) MessageBanner(message) { message = "" }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                            Text(stringResource(R.string.attach_audio))
                        } }
                        item { OutlinedButton(onClick = {
                            exportBody = DraftFormat.portable(draft.title, draft.artist, lines)
                            exportName = "${draft.title.ifBlank { "lyrics" }}.lrc"
                            exportPicker.launch(exportName)
                        }, enabled = readyForLrc) { Text(stringResource(R.string.export_lrc)) } }
                        item { OutlinedButton(onClick = {
                            exportBody = DraftFormat.text(lines)
                            exportPicker.launch("${draft.title.ifBlank { "lyrics" }}.txt")
                        }, enabled = lines.isNotEmpty()) { Text(stringResource(R.string.export_txt)) } }
                        item { OutlinedButton(onClick = {
                            exportBody = DraftFormat.enhanced(draft.title, draft.artist, lines)
                            exportPicker.launch("${draft.title.ifBlank { "lyrics" }}-enhanced.lrc")
                        }, enabled = readyForLrc && lines.any { it.wordTimes.any { time -> time != null } }) {
                            Text(stringResource(R.string.export_enhanced))
                        } }
                        item { Button(onClick = { parentPicker.launch(null) },
                            enabled = draft.audioUri.isNotBlank() && readyForLrc) {
                            Text(stringResource(R.string.embed_audio))
                        } }
                    }
                }
            }
        }
        if (mode) {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val active = lines.getOrNull(currentIndex)?.text ?: lines.getOrNull(selectedLine)?.text.orEmpty()
                Text(active.ifBlank { stringResource(R.string.select_line_to_stamp) },
                    style = MaterialTheme.typography.titleMedium, maxLines = 2,
                    fontWeight = FontWeight.Bold)
                if (player != null) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = player::toggle, enabled = player.ready) {
                            Text(if (player.playing) stringResource(R.string.pause) else stringResource(R.string.play))
                        }
                        TextButton(onClick = { player.changeSpeed(if (player.speed == 1f) 0.75f else 1f) },
                            enabled = player.ready) { Text("${player.speed}×") }
                        Button(onClick = {
                            val index = selectedLine.coerceIn(0, lines.lastIndex)
                            updateLines(lines.toMutableList().apply {
                                this[index] = this[index].copy(timeMs = player.positionMs)
                            })
                            selectedLine = (index + 1).coerceAtMost(lines.lastIndex)
                        }, enabled = player.ready && lines.isNotEmpty()) {
                            Text(stringResource(R.string.stamp_line))
                        }
                    }
                    Slider(value = player.positionMs.toFloat().coerceIn(0f,
                        player.durationMs.coerceAtLeast(1).toFloat()),
                        onValueChange = { player.seekTo(it.toLong()) },
                        valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                        enabled = player.ready)
                    if (player.error.isNotBlank()) Text(player.error, color = SoftCoral,
                        style = MaterialTheme.typography.bodySmall)
                } else Text(stringResource(R.string.attach_audio_timing), color = MutedBlue)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { updateLines(lines.map { line ->
                        line.copy(timeMs = line.timeMs?.let { (it - 100).coerceAtLeast(0) },
                            wordTimes = line.wordTimes.map { it?.minus(100)?.coerceAtLeast(0) })
                    }) }) { Text(stringResource(R.string.timing_minus_100)) }
                    TextButton(onClick = { updateLines(lines.map { line ->
                        line.copy(timeMs = line.timeMs?.plus(100),
                            wordTimes = line.wordTimes.map { it?.plus(100) })
                    }) }) { Text(stringResource(R.string.timing_plus_100)) }
                }
            }
        }
    }
}
