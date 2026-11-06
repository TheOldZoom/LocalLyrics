package com.music.lyricsapp

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.lyricsapp.core.LyricsRepository
import com.music.lyricsapp.core.LyricsSource
import com.music.lyricsapp.core.PaxSenix
import com.music.lyricsapp.core.YouTubeVideoLookup
import com.music.lyricsapp.core.toEnhancedLrc
import com.music.lyricsapp.core.toLrc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookupScreen(onDraft: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { WorkspaceDatabase.get(context).workspaceDao() }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var searchedKey by remember { mutableStateOf("") }
    var searchedTitle by remember { mutableStateOf("") }
    var searchedArtist by remember { mutableStateOf("") }
    var searchedAlbum by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(LibrarySettings.privacyIntroSeen(context)) }
    val results by remember(searchedKey) { dao.observeCache(searchedKey) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val enabled = LibrarySettings.sources(context)
    val ordered = LibrarySettings.sourceOrder(context).filter { it in enabled && !it.hidden }

    fun search(refresh: Boolean) {
        if (title.isBlank() || artist.isBlank()) {
            message = context.getString(R.string.enter_title_artist)
            return
        }
        if (!consent) {
            message = context.getString(R.string.lookup_needs_consent)
            return
        }
        val searchTitle = title.trim()
        val searchArtist = artist.trim()
        val searchAlbum = album.trim()
        val key = lookupKey(searchTitle, searchArtist, searchAlbum)
        searchedKey = key
        searchedTitle = searchTitle
        searchedArtist = searchArtist
        searchedAlbum = searchAlbum
        selectedProvider = ""
        scope.launch {
            val previous = dao.cached(key).associateBy { it.provider }
            val due = ordered.filter { refresh || previous[it.name]?.fresh() != true }
            if (due.isEmpty()) {
                message = context.getString(R.string.showing_cached_results)
                return@launch
            }
            val network = context.getSystemService(ConnectivityManager::class.java)
            val online = network.getNetworkCapabilities(network.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (!online) {
                message = context.getString(R.string.offline_cached_results)
                return@launch
            }
            searching = true
            message = ""
            PaxSenix.setApiKey(LibrarySettings.paxKey(context))
            try {
                coroutineScope {
                    due.forEach { source -> launch {
                        val state = runCatching {
                            val videoId = if (source in setOf(LyricsSource.YOUTUBE_MUSIC,
                                    LyricsSource.YOUTUBE_TRANSCRIPT, LyricsSource.SIMP_MUSIC)) {
                                withTimeoutOrNull(5_000) { YouTubeVideoLookup.find(searchTitle, searchArtist, 0) }
                            } else null
                            val result = LyricsRepository.lyrics(videoId ?: key, searchTitle, searchArtist, 0,
                                album = searchAlbum.ifBlank { null }, sources = setOf(source),
                                order = listOf(source), collectAll = true)
                            LookupCacheEntity(key, source.name,
                                if (result == null) "missing" else "found",
                                result?.lines?.toLrc().orEmpty(),
                                result?.lines?.toEnhancedLrc().orEmpty())
                        }.getOrElse {
                            if (it is CancellationException) throw it
                            LookupCacheEntity(key, source.name, "error")
                        }
                        dao.saveCache(state)
                    } }
                }
            } finally { searching = false }
        }
    }

    val found = ordered.mapNotNull { source ->
        results.firstOrNull { it.provider == source.name }?.let { source to it }
    }
    val selected = found.firstOrNull { it.first.name == selectedProvider }
        ?: found.firstOrNull { it.second.state == "found" }
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.lookup)) },
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) }) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(stringResource(R.string.lookup_intro), color = MutedBlue)
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.title)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text(stringResource(R.string.artist)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text(stringResource(R.string.album_optional)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (!consent) {
                    Text(stringResource(R.string.lookup_privacy_details), color = MutedBlue)
                    OutlinedButton(onClick = {
                        LibrarySettings.setOnlineLookup(context, true); consent = true
                    }) { Text(stringResource(R.string.enable_online_lookup)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { search(false) }, enabled = !searching && consent && ordered.isNotEmpty()) {
                        Text(stringResource(R.string.search))
                    }
                    OutlinedButton(onClick = { search(true) }, enabled = !searching && consent && ordered.isNotEmpty()) {
                        Text(stringResource(R.string.refresh))
                    }
                }
                if (searching) Text(stringResource(R.string.searching_providers), color = WarmGold)
                if (message.isNotBlank()) Text(message, color = MutedBlue)
                if (ordered.isEmpty()) {
                    Text(stringResource(R.string.no_providers_enabled), color = MutedBlue)
                    OutlinedButton(onClick = onSettings) { Text(stringResource(R.string.sources)) }
                }
            }
        }
        if (searchedKey.isNotBlank()) {
            item {
                Text(stringResource(R.string.compare_results), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
            }
            items(found, key = { it.first.name }) { (source, row) ->
                val chosen = selected?.first == source
                Surface(onClick = { selectedProvider = source.name },
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(source.label, style = MaterialTheme.typography.titleMedium)
                        Text(when (row.state) {
                            "found" -> if (row.enhanced.isNotBlank()) stringResource(R.string.word_timed)
                                else stringResource(R.string.line_timed)
                            "missing" -> stringResource(R.string.no_result)
                            else -> stringResource(R.string.provider_error)
                        }, color = MutedBlue)
                        Text(if (row.fresh()) stringResource(R.string.cached_result)
                            else stringResource(R.string.expired_cache),
                            style = MaterialTheme.typography.labelSmall, color = MutedBlue)
                    }
                }
            }
            if (selected?.second?.state == "found") item {
                val row = selected.second
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(DraftFormat.text(DraftFormat.import(row.plain)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.heightIn(max = 280.dp))
                        Button(onClick = {
                            scope.launch {
                                val id = UUID.randomUUID().toString()
                                val lines = DraftFormat.import(row.enhanced.ifBlank { row.plain })
                                dao.saveDraft(DraftEntity(id, searchedTitle, searchedArtist, searchedAlbum,
                                    DraftFormat.encode(lines), origin = selected.first.label))
                                onDraft(id)
                            }
                        }) { Text(stringResource(R.string.use_as_draft)) }
                    }
                }
            }
        }
    }
    }
}
