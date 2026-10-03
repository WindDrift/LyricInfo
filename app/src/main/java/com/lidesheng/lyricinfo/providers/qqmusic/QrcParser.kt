package com.lidesheng.lyricinfo.providers.qqmusic

/** Unwraps QQ's XML envelope and returns the original QRC/LRC lyric text. */
internal object QrcParser {

    private val LYRIC_CONTENT_ATTRIBUTE = Regex(
        """LyricContent\s*=\s*(["'])([\s\S]*?)\1""",
        RegexOption.IGNORE_CASE
    )
    private val XML_ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|quot|amp|lt|gt|apos);""")

    /** Extract plain lyric text from XML QRC payloads; pass through plain text unchanged. */
    fun toRawQrc(qrcContent: String?): String? {
        if (qrcContent.isNullOrBlank()) return null
        return extractFromXml(qrcContent) ?: qrcContent.trim()
    }

    private fun extractFromXml(input: String): String? {
        val encoded = LYRIC_CONTENT_ATTRIBUTE.find(input)?.groupValues?.getOrNull(2)
            ?: return null
        return decodeXmlEntities(encoded).takeIf { it.isNotBlank() }
    }

    private fun decodeXmlEntities(input: String): String = XML_ENTITY.replace(input) { match ->
        when (val entity = match.groupValues[1]) {
            "quot" -> "\""
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "apos" -> "'"
            else -> {
                val codePoint = runCatching {
                    if (entity.startsWith("#x", ignoreCase = true)) {
                        entity.substring(2).toInt(16)
                    } else {
                        entity.substring(1).toInt()
                    }
                }.getOrNull()
                if (codePoint == null || !Character.isValidCodePoint(codePoint)) {
                    match.value
                } else {
                    String(Character.toChars(codePoint))
                }
            }
        }
    }
}
