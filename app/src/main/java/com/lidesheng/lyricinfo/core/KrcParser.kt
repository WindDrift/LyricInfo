package com.lidesheng.lyricinfo.core

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Parses plain KRC and LRC lyric lanes.
 *
 * Standard KRC word tags precede their text and contain offsets relative to
 * the line start. A postfix variant is also accepted for cached payloads that
 * place a tag after the word it describes.
 */
object KrcParser {

    private val LINE_HEADER = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val WORD_TAG = Regex("""<(\d+),(\d+),\d+>""")
    private val LRC_TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val LANGUAGE_HEADER = Regex("""^\[language:([^]]*)]$""", RegexOption.IGNORE_CASE)

    private data class ParsedLine(
        val startMs: Long,
        val text: String,
        val words: List<Word>
    )

    private data class Word(
        val beginMs: Long,
        val text: String,
        val timed: Boolean
    )

    private data class EmbeddedLanes(
        val translation: String? = null,
        val roma: String? = null
    )

    /** Parse one lyric lane without merging it with another lane. */
    fun parse(type: String?, content: String?): LyricResult? {
        if (content.isNullOrBlank()) return null

        return when (type?.lowercase(Locale.ROOT)) {
            "krc" -> parseKrcResult(content)
            "lrc" -> parseLrcResult(content)
            "elrc" -> LyricNormalizer.normalize(content)
            else -> null
        }
    }

    private fun parseKrcResult(content: String): LyricResult? {
        val lines = parseKrcToLines(content)
        if (lines.isEmpty()) return null

        val metadata = LyricNormalizer.prefixMetadataLines(content)
            .filterNot { LANGUAGE_HEADER.matches(it) }
        val lyric = withMetadata(
            metadata,
            lines.joinToString("\n") { line ->
                "${formatLrcTime(line.startMs)}${line.text}"
            }.trim()
        )
        if (lyric.isBlank()) return null

        val hasTimedWords = lines.any { line ->
            line.words.any { it.timed && it.text.isNotEmpty() }
        }
        val rawLyric = if (hasTimedWords) {
            withMetadata(metadata, renderKrc(lines))
        } else {
            null
        }
        val embedded = parseEmbeddedLanes(content, lines)
        return LyricResult(
            lyric = lyric,
            rawLyric = rawLyric,
            translation = embedded.translation?.let { withMetadata(metadata, it) },
            roma = embedded.roma?.let { withMetadata(metadata, it) }
        )
    }

    private fun parseLrcResult(content: String): LyricResult? {
        LyricNormalizer.normalize(content)
            ?.takeIf { it.rawLyric != null }
            ?.let { return it }

        val lines = parseLrcToLines(content)
        if (lines.isEmpty()) return null
        val metadata = LyricNormalizer.prefixMetadataLines(content)
        val lyric = withMetadata(
            metadata,
            lines.joinToString("\n") { line ->
                "${formatLrcTime(line.startMs)}${line.text}"
            }.trim()
        )
        return lyric.takeIf { it.isNotBlank() }?.let { LyricResult(lyric = it) }
    }

    private fun renderKrc(lines: List<ParsedLine>): String =
        lines.joinToString("\n") { line ->
            buildString {
                append(formatLrcTime(line.startMs))
                for (word in line.words) {
                    if (word.text.isEmpty()) continue
                    if (word.timed) append(formatElrcTime(word.beginMs))
                    append(word.text)
                }
            }
        }.trim()

    private fun parseKrcToLines(content: String): List<ParsedLine> {
        val result = mutableListOf<ParsedLine>()
        for (rawLine in content.lineSequence()) {
            val lineMatch = LINE_HEADER.matchEntire(rawLine.trim().removePrefix("\uFEFF")) ?: continue
            val startMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            lineMatch.groupValues[2].toLongOrNull() ?: continue
            val body = lineMatch.groupValues[3]
            val words = parseKrcWords(startMs, body)
            val text = words.joinToString("") { it.text }
            result += ParsedLine(startMs, text, words)
        }
        return result
    }

    private fun parseKrcWords(lineStartMs: Long, body: String): List<Word> {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) {
            return listOf(Word(lineStartMs, body, timed = false))
        }

        val result = mutableListOf<Word>()
        val firstTextIndex = body.indexOfFirst { !it.isWhitespace() }
        val tagsPrecedeText = firstTextIndex < 0 || tags.first().range.first <= firstTextIndex

        if (tagsPrecedeText) {
            // Standard KRC: <offset,duration,flag>word...
            for (index in tags.indices) {
                val tag = tags[index]
                val textStart = tag.range.last + 1
                val textEnd = tags.getOrNull(index + 1)?.range?.first ?: body.length
                val text = body.substring(textStart, textEnd)
                val offset = tag.groupValues[1].toLongOrNull() ?: continue
                result += Word(lineStartMs + offset, text, timed = true)
            }
            return result
        }

        // Postfix KRC variant: word<offset,duration,flag>word...
        var textStart = 0
        var previousEndMs = lineStartMs
        for (tag in tags) {
            val text = body.substring(textStart, tag.range.first)
            val offset = tag.groupValues[1].toLongOrNull()
            val duration = tag.groupValues[2].toLongOrNull()
            if (text.isNotEmpty() && offset != null) {
                val wordStartMs = lineStartMs + offset
                result += Word(wordStartMs, text, timed = true)
                previousEndMs = wordStartMs + (duration ?: 0L)
            }
            textStart = tag.range.last + 1
        }
        val trailingText = body.substring(textStart)
        if (trailingText.isNotEmpty()) {
            result += Word(previousEndMs, trailingText, timed = false)
        }
        return result
    }

    private fun parseEmbeddedLanes(content: String, lines: List<ParsedLine>): EmbeddedLanes {
        val encoded = content.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .firstNotNullOfOrNull { line -> LANGUAGE_HEADER.matchEntire(line)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }
            ?: return EmbeddedLanes()

        val bytes = sequenceOf(
            Base64.DEFAULT,
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ).mapNotNull { flags ->
            runCatching { Base64.decode(encoded, flags) }.getOrNull()
        }.firstOrNull() ?: return EmbeddedLanes()

        val entries = runCatching {
            JSONObject(String(bytes, StandardCharsets.UTF_8)).optJSONArray("content")
        }.getOrNull() ?: return EmbeddedLanes()

        var translation: String? = null
        var roma: String? = null
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val rows = entry.optJSONArray("lyricContent") ?: continue
            when (entry.optInt("type", -1)) {
                0 -> roma = parseRomanization(rows, lines) ?: roma
                1 -> translation = parseTranslation(rows, lines) ?: translation
            }
        }
        return EmbeddedLanes(translation = translation, roma = roma)
    }

    private fun parseTranslation(rows: JSONArray, lines: List<ParsedLine>): String? {
        val output = mutableListOf<String>()
        for (index in 0 until minOf(rows.length(), lines.size)) {
            val text = flattenText(rows.opt(index)).trim()
            if (text.isBlank()) continue
            output += "${formatLrcTime(lines[index].startMs)}$text"
        }
        if (output.isEmpty()) return null
        return output.joinToString("\n")
    }

    private fun parseRomanization(rows: JSONArray, lines: List<ParsedLine>): String? {
        val output = mutableListOf<String>()
        var omittedEmptyLines = 0
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            if (line.text.isBlank()) {
                omittedEmptyLines++
                continue
            }
            val rowIndex = lineIndex - omittedEmptyLines
            val row = rows.opt(rowIndex)
            val romanWords = row as? JSONArray
            if (romanWords == null) {
                val text = flattenText(row).trim()
                if (text.isNotBlank()) output += "${formatLrcTime(line.startMs)}$text"
                continue
            }

            val rendered = buildString {
                append(formatLrcTime(line.startMs))
                for (wordIndex in 0 until romanWords.length()) {
                    val text = flattenText(romanWords.opt(wordIndex))
                    if (text.isEmpty()) continue
                    val word = line.words.getOrNull(wordIndex)
                    if (word?.timed == true) append(formatElrcTime(word.beginMs))
                    append(text)
                }
            }
            if (rendered.substringAfter(']').isNotBlank()) output += rendered
        }
        if (output.isEmpty()) return null
        return output.joinToString("\n")
    }

    private fun flattenText(value: Any?): String = when (value) {
        is JSONArray -> buildString {
            for (index in 0 until value.length()) append(flattenText(value.opt(index)))
        }
        is JSONObject -> value.optString("text", value.optString("tx", ""))
        null -> ""
        JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun parseLrcToLines(content: String): List<ParsedLine> {
        val result = mutableListOf<ParsedLine>()
        for (rawLine in content.lineSequence()) {
            if (rawLine.isBlank()) continue
            val timeMatches = LRC_TIME_TAG.findAll(rawLine).toList()
            if (timeMatches.isEmpty()) continue
            val text = rawLine.substring(timeMatches.last().range.last + 1)
            for (timeMatch in timeMatches) {
                val startMs = parseTimestampMs(timeMatch) ?: continue
                result += ParsedLine(startMs, text, emptyList())
            }
        }
        return result
    }

    private fun parseTimestampMs(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        val fraction = match.groupValues.getOrNull(3).orEmpty()
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> (fraction.toLongOrNull() ?: return null) * 100L
            2 -> (fraction.toLongOrNull() ?: return null) * 10L
            3 -> fraction.toLongOrNull() ?: return null
            else -> return null
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun withMetadata(metadata: List<String>, lyric: String): String =
        (metadata + lyric).filter { it.isNotBlank() }.joinToString("\n")

    private fun formatLrcTime(ms: Long): String {
        val totalSeconds = ms / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = ms % 1_000L
        return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }

    private fun formatElrcTime(ms: Long): String {
        val totalSeconds = ms / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val millis = ms % 1_000L
        return String.format(Locale.ROOT, "<%02d:%02d.%03d>", minutes, seconds, millis)
    }
}
