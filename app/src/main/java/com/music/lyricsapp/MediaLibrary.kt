package com.music.lyricsapp

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow

/** Indexes the platform's audio collection. A row URI, including its volume, is its identity. */
internal object MediaLibrary {
    private val indexedPaths = ConcurrentHashMap<String, String>()
    val pathsRevision = MutableStateFlow(0)
    fun permission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun permitted(context: Context): Boolean =
        context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED

    fun isIndexed(uri: String): Boolean = uri.startsWith("content://media/")

    fun indexedCopyOf(documentUri: String): Boolean = indexedUriForDocument(documentUri) != null

    fun indexedUriForDocument(documentUri: String): String? {
        val id = runCatching { DocumentsContract.getDocumentId(Uri.parse(documentUri)) }.getOrNull()
            ?: return null
        val volume = id.substringBefore(':', "")
        val relative = id.substringAfter(':', "")
        if (volume.isBlank() || relative.isBlank()) return null
        val path = if (volume.equals("primary", true)) "/storage/emulated/0/$relative"
            else "/storage/$volume/$relative"
        return indexedPaths[path]
    }

    suspend fun scan(context: Context, store: LyricStore, stopped: () -> Boolean = { false }) {
        if (!permitted(context)) {
            indexedPaths.clear()
            pathsRevision.value += 1
            return
        }
        val columns = mutableListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= 29) columns += MediaStore.Audio.Media.VOLUME_NAME
        columns += MediaStore.Audio.Media.DATA // duplicate detection only; never used for writes
        val seen = HashSet<String>()
        val paths = HashMap<String, String>()
        // The external collection includes every mounted shared-storage volume. Never infer a
        // file path for writes; the path is only a best-effort duplicate hint for SAF rows.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val cursor = try {
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns.toTypedArray(), selection, null, null)
        } catch (_: IllegalArgumentException) {
            columns.remove(MediaStore.Audio.Media.DATA)
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns.toTypedArray(), selection, null, null)
        }
        cursor?.use { cursor ->
            fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column)).orEmpty()
            fun Cursor.number(column: String) = getLong(getColumnIndexOrThrow(column))
            while (cursor.moveToNext()) {
                if (stopped()) return
                val id = cursor.number(MediaStore.Audio.Media._ID)
                val volume = if (Build.VERSION.SDK_INT >= 29) cursor.string(MediaStore.Audio.Media.VOLUME_NAME) else ""
                val uri = if (Build.VERSION.SDK_INT >= 30 && volume.isNotBlank())
                    MediaStore.Audio.Media.getContentUri(volume, id)
                else ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val name = cursor.string(MediaStore.Audio.Media.DISPLAY_NAME)
                if (name.isBlank() || ".lyrics-backup-" in name || ".tagged-" in name) continue
                val identity = uri.toString()
                seen += identity
                val pathColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (pathColumn >= 0) cursor.getString(pathColumn)?.takeIf { it.isNotBlank() }
                    ?.let { paths[it] = identity }
                val signature = "${cursor.number(MediaStore.Audio.Media.SIZE)}:${cursor.number(MediaStore.Audio.Media.DATE_MODIFIED)}:$name"
                val previous = store.get(identity)
                if (previous?.signature == signature && previous.status != "discovered") continue
                val title = cursor.string(MediaStore.Audio.Media.TITLE).takeUnless { it.isBlank() || it == "<unknown>" }
                    ?: name.substringBeforeLast('.')
                val artist = cursor.string(MediaStore.Audio.Media.ARTIST).takeUnless { it == "<unknown>" }.orEmpty()
                val album = cursor.string(MediaStore.Audio.Media.ALBUM).takeUnless { it == "<unknown>" }.orEmpty()
                val existing = if (previous?.existing?.isNotBlank() == true &&
                    name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "m4a", "mp4", "webm"))
                    runCatching {
                        RawEmbeddedLyrics.read(context, uri, name.substringAfterLast('.').lowercase())
                    }.getOrNull().orEmpty() else ""
                store.scanned(LocalTrack(identity, name, title, artist, album,
                    cursor.number(MediaStore.Audio.Media.DURATION), signature, "indexed", "", "", "",
                    existing, ""))
            }
        } ?: return
        if (!stopped()) {
            indexedPaths.clear(); indexedPaths.putAll(paths)
            pathsRevision.value += 1
            store.removeMissingIndexed(seen)
        }
    }
}
