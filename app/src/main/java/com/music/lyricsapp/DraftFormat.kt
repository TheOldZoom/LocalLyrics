package com.music.lyricsapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class DraftLine(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timeMs: Long? = null,
    val wordTimes: List<Long?> = emptyList(),
)

internal object DraftFormat {
    private val lineStamp = Regex("""^\[(\d{1,3}):(\d{2})[.:](\d{2,3})](.*)$""")
    private val wordStamp = Regex("""<\d{1,3}:\d{2}[.:]\d{2,3}>""")
    private val wordToken = Regex("""\S+""")

    fun decode(json: String): List<DraftLine> = runCatching {
        val rows = JSONArray(json)
        List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            val times = row.optJSONArray("words") ?: JSONArray()
            DraftLine(row.getString("id"), row.optString("text"),
                if (row.isNull("time")) null else row.getLong("time"),
                List(times.length()) { i -> if (times.isNull(i)) null else times.getLong(i) })
        }
    }.getOrDefault(emptyList())

    fun encode(lines: List<DraftLine>): String = JSONArray().apply {
        lines.forEach { line ->
            put(JSONObject().apply {
                put("id", line.id); put("text", line.text)
                put("time", line.timeMs ?: JSONObject.NULL)
                put("words", JSONArray().apply {
                    line.wordTimes.forEach { put(it ?: JSONObject.NULL) }
                })
            })
        }
    }.toString()

    fun import(text: String): List<DraftLine> = text.lineSequence().mapNotNull { original ->
        val row = original.trim()
        if (row.isEmpty() || row.matches(Regex("""\[(ti|ar|al|by|offset):.*]""", RegexOption.IGNORE_CASE)))
            return@mapNotNull null
        val matched = lineStamp.matchEntire(row)
        val body = matched?.groupValues?.get(4) ?: row
        val time = matched?.let { stampMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        val tokens = wordToken.findAll(body).toList()
        val times = tokens.map { token ->
            val mark = Regex("""<(?<m>\d{1,3}):(?<s>\d{2})[.:](?<f>\d{2,3})>""")
                .find(token.value)
            mark?.let { stampMs(it.groups["m"]!!.value, it.groups["s"]!!.value, it.groups["f"]!!.value) }
        }
        val clean = body.replace(wordStamp, "").trim()
        clean.takeIf { it.isNotEmpty() }?.let { DraftLine(text = it, timeMs = time,
            wordTimes = times.takeIf { list -> list.any { it != null } }.orEmpty()) }
    }.toList()

    fun text(lines: List<DraftLine>): String = lines.joinToString("\n") { it.text }

    fun portable(title: String, artist: String, lines: List<DraftLine>): String = buildString {
        if (title.isNotBlank()) append("[ti:").append(title.replace(']', ' ')).append("]\n")
        if (artist.isNotBlank()) append("[ar:").append(artist.replace(']', ' ')).append("]\n")
        lines.forEach { line ->
            line.timeMs?.let { append(stamp(it, false)) }
            append(line.text).append('\n')
        }
    }.trimEnd()

    fun enhanced(title: String, artist: String, lines: List<DraftLine>): String = buildString {
        if (title.isNotBlank()) append("[ti:").append(title.replace(']', ' ')).append("]\n")
        if (artist.isNotBlank()) append("[ar:").append(artist.replace(']', ' ')).append("]\n")
        lines.forEach { line ->
            line.timeMs?.let { append(stamp(it, false)) }
            val words = wordToken.findAll(line.text).map { it.value }.toList()
            words.forEachIndexed { index, word ->
                val time = line.wordTimes.getOrNull(index)
                if (time != null) append(stamp(time, true))
                append(word)
                if (index < words.lastIndex) append(' ')
            }
            append('\n')
        }
    }.trimEnd()

    fun words(line: DraftLine): List<String> = wordToken.findAll(line.text).map { it.value }.toList()

    private fun stampMs(minutes: String, seconds: String, fraction: String): Long =
        minutes.toLong() * 60_000 + seconds.toLong() * 1_000 +
            fraction.toLong() * if (fraction.length == 2) 10 else 1

    fun stamp(ms: Long, word: Boolean): String {
        val value = ms.coerceAtLeast(0)
        val clock = "${(value / 60_000).toString().padStart(2, '0')}:" +
            "${(value % 60_000 / 1_000).toString().padStart(2, '0')}." +
            (value % 1_000 / 10).toString().padStart(2, '0')
        return if (word) "<$clock>" else "[$clock]"
    }
}
