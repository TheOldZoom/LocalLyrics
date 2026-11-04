package com.music.lyricsapp

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.lyricsapp.core.LyricsRepository
import com.music.lyricsapp.core.LyricsSource
import com.music.lyricsapp.core.PaxSenix
import com.music.lyricsapp.core.toEnhancedLrc
import com.music.lyricsapp.core.toLrc
import com.music.lyricsapp.core.YouTubeVideoLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

object LibrarySettings {
    private const val PREFS = "lyrics_settings"
    fun folder(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("folder", "").orEmpty()
    fun setFolder(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("folder", uri).apply()
    }
    fun paxKey(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("pax_key", "").orEmpty()
    fun setPaxKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("pax_key", key).apply()
    }
    fun autoAdd(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("auto_add", true)
    fun setAutoAdd(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_add", enabled).apply()
    }
    fun sources(context: Context): Set<LyricsSource> {
        val saved = context.getSharedPreferences(PREFS, 0).getStringSet("sources", null)
        return saved?.mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name && !it.hidden } }?.toSet()
            ?: LyricsSource.offered.toSet()
    }
    fun setSources(context: Context, chosen: Set<LyricsSource>) {
        context.getSharedPreferences(PREFS, 0).edit().putStringSet("sources", chosen.map { it.name }.toSet()).apply()
    }
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork("lyrics_new_files", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LibraryWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build())
    }
    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("lyrics_scan_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LibraryWorker>().build())
    }
}

class LibraryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object { private val scanMutex = Mutex() }
    private data class AudioTags(val title: String, val artist: String, val album: String, val durationMs: Long)
    override suspend fun doWork(): Result = scanMutex.withLock { withContext(Dispatchers.IO) {
        val context = applicationContext
        val treeUri = LibrarySettings.folder(context).takeIf { it.isNotBlank() } ?: return@withContext Result.success()
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return@withContext Result.failure()
        if (!root.canRead()) return@withContext Result.failure()
        val store = LyricStore(context)
        try {
            PaxSenix.setApiKey(LibrarySettings.paxKey(context))
            val seen = mutableSetOf<String>()
            suspend fun walk(folder: DocumentFile) {
                for (child in folder.listFiles()) {
                    if (isStopped) return
                    if (child.isDirectory) { walk(child); continue }
                    if (!child.isFile) continue
                    var name = child.name.orEmpty()
                    val backedUpName = name.substringBefore(".lyrics-backup-")
                    if (backedUpName != name) {
                        val current = folder.findFile(backedUpName)
                        if (current == null) child.renameTo(backedUpName)
                        else if (RawEmbeddedLyrics.read(context, current.uri,
                                backedUpName.substringAfterLast('.', "").lowercase()) != null) child.delete()
                        name = child.name.orEmpty()
                        if (!child.exists()) continue
                    }
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff")) continue
                    val supported = extension in setOf("mp3", "flac", "m4a", "webm")
                    val uri = child.uri.toString()
                    seen += uri
                    val signature = "${child.length()}:${child.lastModified()}:$name"
                    if (store.get(uri)?.signature == signature) continue
                    val tags = if (supported) metadata(context, child.uri, name)
                        else AudioTags(name.substringBeforeLast('.'), "", "", 0L)
                    val existing = if (supported) RawEmbeddedLyrics.read(context, child.uri, extension).orEmpty() else ""
                    store.scanned(LocalTrack(uri, name, tags.title, tags.artist, tags.album, tags.durationMs,
                        signature, if (!supported) "unsupported" else if (existing.isNotBlank()) "existing" else "new",
                        "", "", "", existing, ""))
                }
            }
            walk(root)
            if (!isStopped) store.removeMissing(seen)
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                return@withContext Result.retry()
            }
            val sources = LibrarySettings.sources(context)
            val pending = store.all().filter { track ->
                track.name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "webm") &&
                    track.providerScan != lookupKey(track, sources)
            }
            for (track in pending.take(15)) {
                if (isStopped) break
                val key = lookupKey(track, sources)
                if (track.artist.isBlank()) {
                    store.update(track.uri, "error", error = "Artist missing from file tags; edit the file tags and rescan.")
                    store.setProviderScan(track.uri, key)
                    continue
                }
                val previousCandidates = store.candidates(track.uri).associateBy { it.source }
                val previousSelection = track.selectedProvider
                store.clearCandidateLookup(track.uri)
                val videoId = if (LyricsSource.YOUTUBE_MUSIC in sources ||
                    LyricsSource.YOUTUBE_TRANSCRIPT in sources) {
                    withTimeoutOrNull(5_000L) {
                        YouTubeVideoLookup.find(track.title, track.artist, track.durationMs)
                    }
                } else null
                val found = withTimeoutOrNull(60_000L) {
                    LyricsRepository.lyrics(videoId ?: track.uri, track.title, track.artist, track.durationMs,
                        album = track.album.ifBlank { null }, sources = sources,
                        order = LyricsSource.offered, prioritizeSyllableSync = true,
                        collectAll = true,
                        onSourceStarted = { source ->
                            store.setCandidate(track.uri, ProviderCandidate(source, "fetching"))
                        },
                        onSourceResult = { source, result ->
                            val plain = result?.lines?.toLrc().orEmpty()
                            store.setCandidate(track.uri, if (plain.isNotBlank() && plain.length <= 64_000)
                                ProviderCandidate(source, "found", plain, result!!.lines.toEnhancedLrc(),
                                    previousCandidates[source]?.takeIf { it.plain == plain }?.review.orEmpty())
                            else ProviderCandidate(source, "missing"))
                        },
                        onSourceFailure = { source, _ ->
                            store.setCandidate(track.uri, ProviderCandidate(source, "error"))
                        },
                        onSourceCancelled = { source ->
                            if (store.candidates(track.uri).firstOrNull { it.source == source }?.state == "fetching") {
                                store.setCandidate(track.uri, ProviderCandidate(source, "timeout"))
                            }
                        })
                }
                store.setProviderScan(track.uri, key)
                val candidates = store.candidates(track.uri)
                val current = store.get(track.uri) ?: continue
                val previousChoice = candidates.firstOrNull { candidate ->
                    candidate.source.name == previousSelection && candidate.state == "found" &&
                        previousCandidates[candidate.source]?.plain == candidate.plain
                }
                val userSelected = current.selectedProvider.isNotBlank() || previousChoice != null
                val chosen = candidates.firstOrNull { it.source.name == current.selectedProvider && it.state == "found" }
                    ?: previousChoice
                    ?: candidates.firstOrNull { it.source == found?.source && it.state == "found" }
                    ?: candidates.firstOrNull { it.state == "found" }
                if (chosen != null) {
                    val ready = if (current.selectedProvider.isNotBlank()) current
                        else store.selectCandidate(track.uri, chosen.source)
                    val plain = ready.proposed
                    val enhanced = ready.enhanced
                    if (!userSelected && ready.review != "wrong" &&
                        LibrarySettings.autoAdd(context) && track.existing.isBlank()) {
                        try {
                            val outcome = ApprovedWriter.apply(context, ready)
                            val savedCandidates = store.candidates(track.uri)
                            if (!outcome.replaced) store.update(track.uri, "ready", ready.source, plain, enhanced,
                                error = outcome.message)
                            store.scanned(ready.copy(uri = outcome.uri, name = outcome.name,
                                signature = outcome.signature,
                                status = "applied", existing = plain))
                            savedCandidates.forEach { store.setCandidate(outcome.uri, it) }
                            store.update(outcome.uri, "applied", ready.source, plain, enhanced)
                            if (outcome.replaced) store.delete(track.uri)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            store.update(track.uri, "ready", ready.source, plain, enhanced,
                                error = "Automatic write failed: ${e.message.orEmpty()}")
                        }
                    }
                } else {
                    store.update(track.uri, if (track.existing.isBlank()) "miss" else "existing",
                        error = "No selected provider found lyrics for this file.")
                }
            }
            if (pending.size > 15 || isStopped) Result.retry() else Result.success()
        } catch (e: SecurityException) {
            Result.failure()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        } finally { store.close() }
    } }

    private fun lookupKey(track: LocalTrack, sources: Set<LyricsSource>): String = listOf(
        track.title, track.artist, track.album, track.durationMs.toString(),
        sources.map { it.name }.sorted().joinToString(","),
    ).joinToString("") { "${it.length}:$it" }

    private fun metadata(context: Context, uri: Uri, name: String): AudioTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            AudioTags(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: name.substringBeforeLast('.'),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) { AudioTags(name.substringBeforeLast('.'), "", "", 0L) }
        finally { retriever.release() }
    }
}
