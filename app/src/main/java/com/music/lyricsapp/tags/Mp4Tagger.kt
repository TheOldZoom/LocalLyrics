package com.music.lyricsapp.tags

import com.music.lyricsapp.core.WORD_LYRICS_FIELD
import com.music.lyricsapp.StandardLyricsField
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Writes iTunes-style metadata atoms — title, artist, album, lyrics, cover —
 * into an M4A/MP4 file. [tagFile] streams the audio payload unchanged.
 *
 * There is no public Android API for this: [android.media.MediaMuxer] can
 * copy tracks into a fresh MP4 but has no way to declare a title or embed
 * artwork, and every general-purpose Java tagging library drags in either
 * native code or `javax.imageio` (absent on Android, and the exact crash
 * several other music apps hit shipping the unmodified desktop jaudiotagger).
 * So this reads and rewrites the handful of boxes involved directly.
 *
 * The whole thing is a single insertion: a fresh `udta/meta/ilst` atom is
 * appended as the last child of `moov`. Growing `moov` shifts every byte
 * after it, which is only a problem because `stco`/`co64` (the sample tables
 * under `moov/trak/mdia/minf/stbl`) record *absolute* file offsets into
 * `mdat` — so every entry at or past the insertion point is bumped by the
 * inserted length. Nothing else in the file addresses itself by absolute
 * offset, so that one adjustment is sufficient regardless of whether `mdat`
 * sits before or after `moov`.
 *
 * Unsupported layouts are rejected rather than guessed at.
 */
object Mp4Tagger {

    private data class BoxRef(
        val offset: Int,
        val headerLen: Int,
        val size: Int,
        /** The raw 32-bit size field, before size==0/1 are resolved — 0 means "to end of parent", which must be left alone rather than replaced with a real number. */
        val rawSize32: Long,
        val type: String,
    ) {
        val contentOffset get() = offset + headerLen
        val end get() = offset + size
    }

    /** Box types whose payload is itself a run of child boxes, on the path down to `stco`/`co64`. */
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String,
        cover: ByteArray?,
        coverIsPng: Boolean,
        /** The A2 form, kept beside [lyrics] rather than instead of it — see [freeformItem]. */
        wordLyrics: String? = null,
    ): ByteArray {
        StandardLyricsField.requireLyrics(lyrics)
        return tagAtBase(bytes, 0L, title, artist, album, lyrics, cover, coverIsPng, wordLyrics)
    }

    /** Reads only moov into memory. mdat and all other top-level boxes are streamed unchanged. */
    fun tagFile(
        source: File, target: File, title: String, artist: String, album: String?,
        lyrics: String, cover: ByteArray?, coverIsPng: Boolean, wordLyrics: String? = null,
    ) {
        StandardLyricsField.requireLyrics(lyrics)
        require(source.canonicalFile != target.canonicalFile) { "Source and target must differ" }
        RandomAccessFile(source, "r").use { input ->
            val (moovOffset, moovSize) = findMoov(input)
            require(moovSize <= 32L * 1024 * 1024) { "MP4 metadata is too large to handle safely" }
            val oldMoov = ByteArray(moovSize.toInt())
            input.seek(moovOffset)
            input.readFully(oldMoov)
            val updatedMoov = tagAtBase(oldMoov, moovOffset, title, artist, album,
                lyrics, cover, coverIsPng, wordLyrics)
            require(!updatedMoov.contentEquals(oldMoov)) { "Unsupported MP4 metadata layout" }
            FileOutputStream(target).use { output ->
                copyRange(input, output, 0, moovOffset)
                output.write(updatedMoov)
                copyRange(input, output, moovOffset + moovSize,
                    input.length() - moovOffset - moovSize)
                output.fd.sync()
            }
        }
    }

    private fun tagAtBase(
        bytes: ByteArray, baseOffset: Long, title: String, artist: String, album: String?,
        lyrics: String, cover: ByteArray?, coverIsPng: Boolean, wordLyrics: String?,
    ): ByteArray {
        val items = mutableListOf<ByteArray>()
        // © is iTunes's own "copyright" prefix for the four text atoms
        // below — not a copyright mark here, just the byte their readers key on.
        if (title.isNotBlank()) items += textItem("©nam", title)
        if (artist.isNotBlank()) items += textItem("©ART", artist)
        if (!album.isNullOrBlank()) items += textItem("©alb", album)
        // `©lyr` is a UTF-8 text atom like the three above, with no length limit
        // and no objection to newlines, so LRC goes in as-is. There is a
        // separate `Sync Lyrics`/`sylt`-style representation in some tools;
        // nothing writes it, because `©lyr` holding LRC is what the players
        // that show synced lyrics for an M4A actually read.
        items += textItem(StandardLyricsField.ITUNES.fieldName, lyrics)
        if (!wordLyrics.isNullOrBlank()) items += freeformItem(WORD_LYRICS_FIELD, wordLyrics)
        if (cover != null && cover.isNotEmpty()) items += coverItem(cover, coverIsPng)
        if (items.isEmpty()) return bytes

        return runCatching {
            val working = bytes.copyOf()
            val moov = parseBoxes(working, 0, working.size).firstOrNull { it.type == "moov" }
                ?: return@runCatching bytes
            neutralizeOldLyrics(working)
            insert(working, moov, udtaAtom(metaAtom(ilstAtom(items))), baseOffset)
        }.getOrDefault(bytes)
    }

    private fun findMoov(input: RandomAccessFile): Pair<Long, Long> {
        var at = 0L
        var hasFtyp = false
        while (at + 8 <= input.length()) {
            input.seek(at)
            val size32 = input.readInt().toLong() and 0xffffffffL
            val typeBytes = ByteArray(4).also(input::readFully)
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val header = if (size32 == 1L) 16L else 8L
            val size = when (size32) {
                0L -> input.length() - at
                1L -> input.readLong().also { require(it >= 16) { "Invalid MP4 extended box size" } }
                else -> size32
            }
            require(size >= header && size <= input.length() - at) { "Invalid MP4 box size" }
            if (at == 0L) require(type == "ftyp") { "MP4 ftyp is missing" }
            if (type == "ftyp") hasFtyp = true
            if (type == "moov") {
                require(hasFtyp) { "MP4 ftyp is missing" }
                return at to size
            }
            at += size
        }
        error("MP4 moov is missing")
    }

    private fun copyRange(input: RandomAccessFile, output: FileOutputStream, offset: Long, size: Long) {
        input.seek(offset)
        var left = size
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            require(n > 0) { "Truncated MP4 while copying" }
            output.write(buffer, 0, n)
            left -= n
        }
    }

    /** Leave old atoms in place at the same byte length, but stop readers seeing them. */
    private fun neutralizeOldLyrics(bytes: ByteArray) {
        val moov = parseBoxes(bytes, 0, bytes.size).firstOrNull { it.type == "moov" } ?: return
        for (udta in parseBoxes(bytes, moov.contentOffset, moov.end).filter { it.type == "udta" }) {
            for (meta in parseBoxes(bytes, udta.contentOffset, udta.end).filter { it.type == "meta" }) {
                for (ilst in parseBoxes(bytes, meta.contentOffset + 4, meta.end).filter { it.type == "ilst" }) {
                    for (item in parseBoxes(bytes, ilst.contentOffset, ilst.end)) {
                        val isWord = item.type == "----" &&
                            String(bytes, item.contentOffset, item.size - item.headerLen, Charsets.ISO_8859_1)
                                .contains(WORD_LYRICS_FIELD)
                        if (item.type == StandardLyricsField.ITUNES.fieldName || isWord) {
                            "free".toByteArray(Charsets.US_ASCII).copyInto(bytes, item.offset + 4)
                            if (isWord) {
                                val needle = WORD_LYRICS_FIELD.toByteArray(Charsets.US_ASCII)
                                for (at in item.contentOffset..(item.end - needle.size)) {
                                    if (bytes.copyOfRange(at, at + needle.size).contentEquals(needle)) {
                                        bytes[at] = 'X'.code.toByte()
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun insert(bytes: ByteArray, moov: BoxRef, udta: ByteArray, baseOffset: Long): ByteArray {
        val insertAt = moov.end
        val delta = udta.size
        val absoluteInsertAt = baseOffset + insertAt

        val prefix = bytes.copyOf(insertAt)
        // rawSize32 == 0 means "this box runs to the end of its parent" — still
        // true after the insertion, since nothing follows moov but this new
        // atom, so the field is left as-is rather than given a concrete value.
        if (moov.rawSize32 != 0L) {
            if (moov.headerLen == 16) {
                writeU64(prefix, moov.offset + 8, moov.size.toLong() + delta)
            } else {
                require(moov.size.toLong() + delta <= 0xffffffffL) { "MP4 moov size overflow" }
                writeU32(prefix, moov.offset, moov.size.toLong() + delta)
            }
        }

        val offsetBoxes = mutableListOf<BoxRef>()
        collectOffsetBoxes(bytes, moov, offsetBoxes)
        offsetBoxes.forEach { box ->
            when (box.type) {
                "stco" -> patchStco(prefix, box, absoluteInsertAt, delta)
                "co64" -> patchCo64(prefix, box, absoluteInsertAt, delta)
            }
        }

        val suffix = bytes.copyOfRange(insertAt, bytes.size)
        return prefix + udta + suffix
    }

    private fun collectOffsetBoxes(bytes: ByteArray, box: BoxRef, out: MutableList<BoxRef>) {
        if (box.type == "stco" || box.type == "co64") {
            out += box
            return
        }
        if (box.type in CONTAINERS) {
            parseBoxes(bytes, box.contentOffset, box.end).forEach { collectOffsetBoxes(bytes, it, out) }
        }
    }

    /** `stco`: FullBox header, an entry count, then that many 32-bit offsets. */
    private fun patchStco(bytes: ByteArray, box: BoxRef, insertAt: Long, delta: Int) {
        val base = box.contentOffset + 4
        val count = readU32(bytes, base).toInt()
        require(count >= 0 && base.toLong() + 4L + count.toLong() * 4 <= box.end) { "Invalid stco count" }
        var p = base + 4
        repeat(count) {
            val off = readU32(bytes, p)
            if (off >= insertAt) {
                require(off + delta <= 0xffffffffL) { "stco offset overflow" }
                writeU32(bytes, p, off + delta)
            }
            p += 4
        }
    }

    /** `co64`: the same shape as [patchStco], with 64-bit offsets. */
    private fun patchCo64(bytes: ByteArray, box: BoxRef, insertAt: Long, delta: Int) {
        val base = box.contentOffset + 4
        val count = readU32(bytes, base).toInt()
        require(count >= 0 && base.toLong() + 4L + count.toLong() * 8 <= box.end) { "Invalid co64 count" }
        var p = base + 4
        repeat(count) {
            val off = readU64(bytes, p)
            if (off >= insertAt) {
                require(off <= Long.MAX_VALUE - delta) { "co64 offset overflow" }
                writeU64(bytes, p, off + delta)
            }
            p += 8
        }
    }

    private fun parseBoxes(bytes: ByteArray, start: Int, end: Int): List<BoxRef> {
        val out = mutableListOf<BoxRef>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readU32(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8
            var size = size32
            if (size32 == 1L) {
                require(pos + 16 <= end) { "Truncated MP4 extended box" }
                size = readU64(bytes, pos + 8)
                headerLen = 16
            } else if (size32 == 0L) {
                size = (end - pos).toLong()
            }
            require(size >= headerLen && size <= Int.MAX_VALUE && size <= end.toLong() - pos) {
                "Invalid MP4 box"
            }
            out += BoxRef(pos, headerLen, size.toInt(), size32, type)
            pos += size.toInt()
        }
        require(pos == end) { "Truncated MP4 box list" }
        return out
    }

    private fun readU32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun readU64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun writeU32(b: ByteArray, off: Int, value: Long) {
        b[off] = ((value shr 24) and 0xFF).toByte()
        b[off + 1] = ((value shr 16) and 0xFF).toByte()
        b[off + 2] = ((value shr 8) and 0xFF).toByte()
        b[off + 3] = (value and 0xFF).toByte()
    }

    private fun writeU64(b: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) b[off + i] = ((value shr (8 * (7 - i))) and 0xFF).toByte()
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeU32(out, 0, (8 + payload.size).toLong())
        // ISO-8859-1, not ASCII: the iTunes item names below carry the 0xA9
        // "copyright" byte, which plain ASCII can't encode and would replace
        // with '?' — corrupting the very atom type a player looks up by.
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    /** iTunes's `data` atom: version(0) + a type indicator packed into 3 flag bytes, then the value. */
    private fun dataAtom(typeIndicator: Int, payload: ByteArray): ByteArray {
        val body = ByteArray(8 + payload.size)
        writeU32(body, 0, typeIndicator.toLong())
        payload.copyInto(body, 8)
        return box("data", body)
    }

    /** Type indicator 1 = UTF-8 text. */
    private fun textItem(fourCc: String, text: String): ByteArray =
        box(fourCc, dataAtom(1, text.toByteArray(Charsets.UTF_8)))

    /** Type indicator 13 = JPEG, 14 = PNG. */
    private fun coverItem(image: ByteArray, isPng: Boolean): ByteArray =
        box("covr", dataAtom(if (isPng) 14 else 13, image))

    /**
     * A `----` item: iTunes' escape hatch for a field it has no four-byte code
     * for, named by a `mean`/`name` pair in front of the value.
     *
     * This is where the word timings go, and the reason they go somewhere of
     * their own is the one [LrcWriter] documents: `©lyr` is read by every other
     * player, and a reader without the A2 extension shows `<00:01.00>` rather
     * than skipping it. A player that doesn't know this item ignores it whole,
     * so the standard field stays clean and the timings still survive in the
     * file — see [EmbeddedLyrics][com.music.lyricsapp.EmbeddedLyrics],
     * which reads them back.
     */
    private fun freeformItem(name: String, text: String): ByteArray {
        // Both are FullBoxes: four bytes of version/flags, then the string.
        val mean = box("mean", ByteArray(4) + MEAN.toByteArray(Charsets.UTF_8))
        val label = box("name", ByteArray(4) + name.toByteArray(Charsets.UTF_8))
        return box("----", mean + label + dataAtom(1, text.toByteArray(Charsets.UTF_8)))
    }

    /** The reverse-DNS owner of [freeformItem], which is what keeps the name ours. */
    private const val MEAN = "com.music.lyricsapp"


    /** A minimal handler box declaring this `meta` as iTunes-style metadata. */
    private fun hdlrAtom(): ByteArray {
        // version/flags(4) + pre_defined(4) + handler_type(4) + reserved(12) + name(1, empty cstring)
        val body = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(body, 8)
        return box("hdlr", body)
    }

    private fun ilstAtom(items: List<ByteArray>): ByteArray {
        val payload = ByteArray(items.sumOf { it.size })
        var p = 0
        items.forEach { it.copyInto(payload, p); p += it.size }
        return box("ilst", payload)
    }

    private fun metaAtom(ilst: ByteArray): ByteArray {
        val hdlr = hdlrAtom()
        val payload = ByteArray(4 + hdlr.size + ilst.size)
        hdlr.copyInto(payload, 4)
        ilst.copyInto(payload, 4 + hdlr.size)
        return box("meta", payload)
    }

    private fun udtaAtom(meta: ByteArray): ByteArray = box("udta", meta)
}
