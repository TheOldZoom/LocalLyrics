package com.music.lyricsapp.tags

import com.music.lyricsapp.StandardLyricsField
import com.music.lyricsapp.core.WORD_LYRICS_FIELD
import java.io.RandomAccessFile

/** Structural EBML walk used before changing any Matroska tag name or segment size. */
internal object WebmLayout {
    data class Layout(
        val sizeAt: Long, val sizeWidth: Int, val segmentSize: Long,
        val unknownSize: Boolean, val oldLyricNames: List<Long>,
        val tagRanges: List<Pair<Long, Long>>,
    ) {
        fun sizeAfter(extra: Long): Long {
            if (unknownSize) return segmentSize
            val max = (1L shl (7 * sizeWidth)) - 2
            require(extra >= 0 && segmentSize <= max - extra) { "WebM segment size cannot grow safely" }
            return segmentSize + extra
        }
    }

    private interface Source {
        val length: Long
        fun byte(at: Long): Int
        fun bytes(at: Long, count: Int): ByteArray
    }

    fun inspect(bytes: ByteArray): Layout = inspect(object : Source {
        override val length = bytes.size.toLong()
        override fun byte(at: Long): Int {
            require(at in 0 until length) { "Truncated WebM" }
            return bytes[at.toInt()].toInt() and 255
        }
        override fun bytes(at: Long, count: Int): ByteArray {
            require(count >= 0 && at >= 0 && count <= length - at) { "Truncated WebM" }
            return bytes.copyOfRange(at.toInt(), at.toInt() + count)
        }
    })

    fun inspect(file: RandomAccessFile): Layout = inspect(object : Source {
        override val length = file.length()
        override fun byte(at: Long): Int {
            require(at in 0 until length) { "Truncated WebM" }
            file.seek(at)
            return file.readUnsignedByte()
        }
        override fun bytes(at: Long, count: Int): ByteArray {
            require(count >= 0 && at >= 0 && count <= length - at) { "Truncated WebM" }
            file.seek(at)
            return ByteArray(count).also { file.readFully(it) }
        }
    })

    private data class Element(
        val id: Long, val sizeAt: Long, val sizeWidth: Int, val sizeValue: Long,
        val unknown: Boolean, val contentAt: Long, val end: Long,
    )

    private fun inspect(source: Source): Layout {
        val header = element(source, 0, source.length)
        require(header.id == 0x1A45DFA3L && !header.unknown) { "WebM EBML header is missing" }
        val segment = element(source, header.end, source.length, allowUnknown = true)
        require(segment.id == 0x18538067L && segment.end == source.length) {
            "WebM must contain one complete segment"
        }
        val names = mutableListOf<Long>()
        val tagRanges = mutableListOf<Pair<Long, Long>>()
        forEachChild(source, segment.contentAt, segment.end) { child ->
            if (child.id == 0x1254C367L) {
                tagRanges += child.contentAt to child.end
                tags(source, child, names)
            }
        }
        return Layout(segment.sizeAt, segment.sizeWidth, segment.sizeValue,
            segment.unknown, names, tagRanges)
    }

    private fun tags(source: Source, tags: Element, names: MutableList<Long>) {
        forEachChild(source, tags.contentAt, tags.end) { tag ->
            if (tag.id == 0x7373L) forEachChild(source, tag.contentAt, tag.end) { child ->
                if (child.id == 0x67C8L) simpleTag(source, child, names)
            }
        }
    }

    private fun simpleTag(source: Source, simple: Element, names: MutableList<Long>) {
        var name: Element? = null
        var hasValue = false
        forEachChild(source, simple.contentAt, simple.end) { child ->
            when (child.id) {
                0x45A3L -> name = child
                0x4487L -> hasValue = true
                0x67C8L -> simpleTag(source, child, names)
            }
        }
        val field = name ?: return
        if (!hasValue || field.sizeValue !in 1..64) return
        val value = String(source.bytes(field.contentAt, field.sizeValue.toInt()), Charsets.US_ASCII)
        if (value == StandardLyricsField.MATROSKA.fieldName || value == WORD_LYRICS_FIELD) {
            names += field.contentAt
        }
    }

    private fun forEachChild(source: Source, start: Long, end: Long, action: (Element) -> Unit) {
        var at = start
        while (at < end) {
            val child = element(source, at, end)
            action(child)
            at = child.end
        }
        require(at == end) { "Invalid WebM element boundary" }
    }

    private fun element(source: Source, at: Long, limit: Long, allowUnknown: Boolean = false): Element {
        require(at >= 0 && at < limit) { "Truncated WebM element" }
        val first = source.byte(at)
        val idWidth = width(first, 4)
        require(idWidth <= limit - at) { "Truncated WebM element ID" }
        var id = 0L
        repeat(idWidth) { i -> id = (id shl 8) or source.byte(at + i).toLong() }
        val sizeAt = at + idWidth
        require(sizeAt < limit) { "Truncated WebM element size" }
        val sizeFirst = source.byte(sizeAt)
        val sizeWidth = width(sizeFirst, 8)
        require(sizeWidth <= limit - sizeAt) { "Truncated WebM element size" }
        val mask = 0x80 ushr (sizeWidth - 1)
        var size = (sizeFirst and (mask - 1)).toLong()
        for (i in 1 until sizeWidth) size = (size shl 8) or source.byte(sizeAt + i).toLong()
        val unknown = size == (1L shl (7 * sizeWidth)) - 1
        require(!unknown || allowUnknown) { "Unsupported unknown-length WebM child" }
        val contentAt = sizeAt + sizeWidth
        require(contentAt <= limit && (unknown || size <= limit - contentAt)) {
            "WebM element exceeds its parent"
        }
        return Element(id, sizeAt, sizeWidth, size, unknown,
            contentAt, if (unknown) limit else contentAt + size)
    }

    private fun width(first: Int, max: Int): Int {
        var mask = 0x80
        var width = 1
        while (mask != 0 && (first and mask) == 0) { mask = mask ushr 1; width++ }
        require(mask != 0 && width <= max) { "Invalid EBML variable-length integer" }
        return width
    }
}
