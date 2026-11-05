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
import androidx.work.workDataOf
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

object LibrarySettings {
    private const val PREFS = "lyrics_settings"
    data class ScanProgress(val processed: Int = 0, val active: Boolean = false)
    val scanProgress = MutableStateFlow(ScanProgress())
    fun folder(context: Context): String = context.getSharedPreferences(PREFS, 0).getString("folder", "").orEmpty()
    fun setFolder(context: Context, uri: String) {
        context.getSharedPreferences(PREFS, 0).edit().putString("folder", uri).apply()
    }
    fun paxKey(context: Context): String = PaxKeyVault.read(context)
    fun setPaxKey(context: Context, key: String) = PaxKeyVault.write(context, key)
    fun autoAdd(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("auto_add", false)
    fun setAutoAdd(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_add", enabled).apply()
    }
    fun autoIntroSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("auto_intro_seen", false)
    fun setAutoIntroSeen(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_intro_seen", true).apply()
    }
    fun privacyIntroSeen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("privacy_intro_seen", false)
    fun setOnlineLookup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("privacy_intro_seen", enabled).apply()
    }
    fun backupDays(context: Context): Int =
        context.getSharedPreferences(PREFS, 0).getInt("backup_days", 0).takeIf { it in setOf(0, 7, 30, 90) } ?: 0
    fun setBackupDays(context: Context, days: Int) {
        require(days in setOf(0, 7, 30, 90))
        context.getSharedPreferences(PREFS, 0).edit().putInt("backup_days", days).apply()
    }
    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean("wifi_only", false)
    fun setWifiOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("wifi_only", enabled).apply()
        schedule(context)
    }
    fun sources(context: Context): Set<LyricsSource> {
        val saved = context.getSharedPreferences(PREFS, 0).getStringSet("sources", null)
        return saved?.mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name && !it.hidden } }?.toSet()
            ?: setOf(LyricsSource.LRCLIB, LyricsSource.LRC_RED)
    }
    fun setSources(context: Context, chosen: Set<LyricsSource>) {
        context.getSharedPreferences(PREFS, 0).edit().putStringSet("sources", chosen.map { it.name }.toSet()).apply()
    }
    fun sourceOrder(context: Context): List<LyricsSource> {
        val saved = context.getSharedPreferences(PREFS, 0).getString("source_order", "").orEmpty()
        return LyricsSource.ordered(saved.split(',').mapNotNull { name ->
            LyricsSource.entries.firstOrNull { it.name == name }
        })
    }
    fun setSourceOrder(context: Context, order: List<LyricsSource>) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString("source_order", order.map { it.name }.joinToString(",")).apply()
    }
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()
        manager.enqueueUniquePeriodicWork("lyrics_new_files", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LibraryWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build())
    }
    fun scanNow(context: Context, priorityUri: String? = null) {
        WorkManager.getInstance(context).enqueueUniqueWork("lyrics_scan_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LibraryWorker>()
                .setInputData(workDataOf("priority_uri" to priorityUri.orEmpty())).build())
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
        LibrarySettings.scanProgress.value = LibrarySettings.ScanProgress(active = true)
        try {
            PaxSenix.setApiKey(LibrarySettings.paxKey(context))
            val seen = mutableSetOf<String>()
            var processed = 0
            suspend fun walk(folder: DocumentFile) {
                for (child in folder.listFiles()) {
                    if (isStopped) return
                    if (child.isDirectory) { walk(child); continue }
                    if (!child.isFile) continue
                    val name = child.name.orEmpty()
                    // Recovery artifacts belong to the user until they explicitly remove them.
                    // Never index them as music or mutate them during a scan.
                    if (".lyrics-backup-" in name || ".tagged-" in name) continue
                    processed++
                    if (processed % 50 == 0) {
                        LibrarySettings.scanProgress.value = LibrarySettings.ScanProgress(processed, true)
                        setProgress(workDataOf("scanned" to processed))
                    }
                    val extension = name.substringAfterLast('.', "").lowercase()
                    if (extension !in setOf("mp3", "m4a", "mp4", "flac", "ogg", "opus", "aac", "webm", "dsf", "dff")) continue
                    val supported = extension in setOf("mp3", "flac", "m4a", "webm") ||
                        (extension == "mp4" && child.type?.startsWith("audio/") == true)
                    val uri = child.uri.toString()
                    seen += uri
                    val signature = "${child.length()}:${child.lastModified()}:$name"
                    val prior = store.get(uri)
                    if (prior?.signature == signature && prior.status != "metadata_error") continue
                    val tags = if (supported) metadata(context, child.uri, name, folder.name.orEmpty())
                        else AudioTags(name.substringBeforeLast('.'), "", "", 0L)
                    val inspected: kotlin.Result<String?> = if (supported) kotlin.runCatching {
                        RawEmbeddedLyrics.read(context, child.uri, extension)
                    } else kotlin.Result.success(null)
                    if (inspected.isFailure) {
                        store.scanned(LocalTrack(uri, name, tags.title, tags.artist, tags.album,
                            tags.durationMs, signature, "metadata_error", "", "", "", "",
                            "Could not safely inspect embedded lyrics: ${inspected.exceptionOrNull()?.message.orEmpty()}"))
                        continue
                    }
                    val existing = inspected.getOrNull().orEmpty()
                    store.scanned(LocalTrack(uri, name, tags.title, tags.artist, tags.album, tags.durationMs,
                        signature, if (!supported) "unsupported" else if (existing.isNotBlank()) "existing" else "new",
                        "", "", "", existing, ""))
                }
            }
            walk(root)
            LibrarySettings.scanProgress.value = LibrarySettings.ScanProgress(processed, true)
            setProgress(workDataOf("scanned" to processed))
            if (!isStopped) store.removeMissing(seen)
            if (!LibrarySettings.privacyIntroSeen(context)) return@withContext Result.success()
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                return@withContext Result.retry()
            }
            val sources = LibrarySettings.sources(context)
            val now = System.currentTimeMillis()
            val pending = store.all().filter { track ->
                track.status != "metadata_error" &&
                    track.name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "mp4", "webm") &&
                    dueSources(track, sources, store.candidates(track.uri), now).isNotEmpty()
            }.sortedWith(compareBy<LocalTrack> {
                when {
                    it.uri == inputData.getString("priority_uri") -> 0
                    it.status == "new" -> 1
                    it.status == "error" -> 2
                    else -> 3
                }
            }.thenBy { it.name })
            for (track in pending.take(15)) {
                if (isStopped) break
                val key = lookupKey(track)
                val previousCandidates = store.candidates(track.uri).associateBy { it.source }
                val due = dueSources(track, sources, previousCandidates.values.toList(), now)
                    .filterTo(mutableSetOf()) { ProviderHealth.allowed(context, it, now) }
                if (due.isEmpty()) continue
                if (track.artist.isBlank()) {
                    store.update(track.uri, "error", error = "Artist unknown. Enter one in Search again to continue; audio tags will not be changed.")
                    continue
                }
                val previousSelection = track.selectedProvider
                val videoId = if (LyricsSource.YOUTUBE_MUSIC in due ||
                    LyricsSource.YOUTUBE_TRANSCRIPT in due || LyricsSource.SIMP_MUSIC in due) {
                    withTimeoutOrNull(5_000L) {
                        YouTubeVideoLookup.find(track.title, track.artist, track.durationMs)
                    }
                } else null
                val found = withTimeoutOrNull(60_000L) {
                    LyricsRepository.lyrics(videoId ?: track.uri, track.title, track.artist, track.durationMs,
                        album = track.album.ifBlank { null }, sources = due,
                        order = LibrarySettings.sourceOrder(context), prioritizeSyllableSync = true,
                        collectAll = true,
                        onSourceStarted = { source ->
                            store.setCandidate(track.uri, ProviderCandidate(source, "fetching", lookupKey = key))
                        },
                        onSourceResult = { source, result ->
                            ProviderHealth.success(context, source)
                            val plain = result?.lines?.toLrc().orEmpty()
                            store.setCandidate(track.uri, if (plain.isNotBlank() && plain.length <= 64_000)
                                ProviderCandidate(source, "found", plain, result!!.lines.toEnhancedLrc(),
                                    previousCandidates[source]?.takeIf { it.plain == plain }?.review.orEmpty(),
                                    lookupKey = key)
                            else ProviderCandidate(source, "missing", lookupKey = key))
                        },
                        onSourceFailure = { source, _ ->
                            ProviderHealth.failure(context, source, System.currentTimeMillis())
                            store.setCandidate(track.uri, ProviderCandidate(source, "error", lookupKey = key))
                        },
                        onSourceCancelled = { source ->
                            if (store.candidates(track.uri).firstOrNull { it.source == source }?.state == "fetching") {
                                ProviderHealth.failure(context, source, System.currentTimeMillis())
                                store.setCandidate(track.uri, ProviderCandidate(source, "timeout", lookupKey = key))
                            }
                        })
                }
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
                    val ready = if (current.selectedProvider == chosen.source.name && current.proposed == chosen.plain) current
                        else store.selectCandidate(track.uri, chosen.source)
                    val plain = ready.proposed
                    val enhanced = ready.enhanced
                    if (!userSelected && LibrarySettings.autoAdd(context) &&
                        LyricConfidence.canAutoWrite(ready, chosen, candidates)) {
                        try {
                            val outcome = ApprovedWriter.apply(context, ready)
                            store.recordWrite(ready, outcome)
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
        } finally {
            LibrarySettings.scanProgress.value = LibrarySettings.ScanProgress(active = false)
            store.close()
        }
    } }

    private fun lookupKey(track: LocalTrack): String = listOf(
        track.title, track.artist, track.album, track.durationMs.toString(),
    ).joinToString("") { "${it.length}:$it" }

    private fun dueSources(
        track: LocalTrack, enabled: Set<LyricsSource>, candidates: List<ProviderCandidate>, now: Long,
    ): Set<LyricsSource> {
        val bySource = candidates.associateBy { it.source }
        val key = lookupKey(track)
        return enabled.filterTo(mutableSetOf()) { source ->
            val previous = bySource[source]
            previous == null || previous.lookupKey != key || previous.fetchedAt <= 0 ||
                when (previous.state) {
                    "found" -> false
                    "missing" -> now - previous.fetchedAt >= 6L * 60 * 60 * 1000
                    "error", "timeout" -> now - previous.fetchedAt >= 15L * 60 * 1000
                    else -> true
                }
        }
    }

    private fun metadata(context: Context, uri: Uri, name: String, folderName: String): AudioTags {
        val retriever = MediaMetadataRetriever()
        val stem = name.substringBeforeLast('.')
        val split = Regex("^(.+?)\\s+-\\s+(.+)$").matchEntire(stem)
        val inferredTitle = split?.groupValues?.get(2)?.trim().orEmpty().ifBlank { stem }
        val inferredArtist = split?.groupValues?.get(1)?.trim().orEmpty().ifBlank {
            folderName.takeUnless { it.isBlank() || it.equals("Music", true) ||
                it.equals("Downloads", true) || it.equals("Unknown Artist", true) }.orEmpty()
        }
        return try {
            retriever.setDataSource(context, uri)
            AudioTags(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                    ?: inferredTitle,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                    ?: inferredArtist,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) { AudioTags(inferredTitle, inferredArtist, "", 0L) }
        finally { retriever.release() }
    }
}
