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
        WorkManager.getInstance(context).enqueueUniqueWork("lyrics_scan_now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LibraryWorker>().build())
    }
}

class LibraryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private data class AudioTags(val title: String, val artist: String, val album: String, val durationMs: Long)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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
            val pending = store.all().filter { it.status == "new" }
            for (track in pending.take(15)) {
                if (isStopped) break
                if (track.artist.isBlank()) {
                    store.update(track.uri, "error", error = "Artist missing from file tags; edit the file tags and rescan.")
                    continue
                }
                val sources = LibrarySettings.sources(context)
                val videoId = if (LyricsSource.YOUTUBE_MUSIC in sources ||
                    LyricsSource.YOUTUBE_TRANSCRIPT in sources) {
                    withTimeoutOrNull(5_000L) {
                        YouTubeVideoLookup.find(track.title, track.artist, track.durationMs)
                    }
                } else null
                val found = withTimeoutOrNull(30_000L) {
                    LyricsRepository.lyrics(videoId ?: track.uri, track.title, track.artist, track.durationMs,
                        album = track.album.ifBlank { null }, sources = sources,
                        order = LyricsSource.offered, prioritizeSyllableSync = true)
                }
                val plain = found?.lines?.toLrc().orEmpty()
                if (found != null && plain.isNotBlank() && plain.length <= 64_000) {
                    val enhanced = found.lines.toEnhancedLrc()
                    store.update(track.uri, "ready", found.source.label, plain, enhanced)
                    if (LibrarySettings.autoAdd(context) && track.existing.isBlank()) {
                        try {
                            val ready = track.copy(status = "ready", source = found.source.label,
                                proposed = plain, enhanced = enhanced)
                            val outcome = ApprovedWriter.apply(context, ready)
                            if (outcome.replaced) store.delete(track.uri)
                            else store.update(track.uri, "ready", found.source.label, plain, enhanced,
                                error = outcome.message)
                            store.scanned(ready.copy(uri = outcome.uri, signature = outcome.signature,
                                status = "applied", existing = plain))
                            store.update(outcome.uri, "applied", found.source.label, plain, enhanced)
                        } catch (e: Exception) {
                            store.update(track.uri, "ready", found.source.label, plain, enhanced,
                                error = "Automatic write failed: ${e.message.orEmpty()}")
                        }
                    }
                } else {
                    store.update(track.uri, "miss", error = "No matching lyrics were found.")
                }
            }
            if (pending.size > 15 || isStopped) Result.retry() else Result.success()
        } catch (e: SecurityException) {
            Result.failure()
        } catch (e: Exception) {
            Result.retry()
        } finally { store.close() }
    }

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
