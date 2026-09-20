package com.rawsmusic.lyrico

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricRubySyllable
import com.rawsmusic.core.common.model.LyricTtmlAgent
import com.rawsmusic.core.common.model.LyricTtmlMetadataElement
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.RawSLyricsParser

internal object LyricoLyricsCodec {
    private const val NS_TTML = "http://www.w3.org/ns/ttml"
    private const val NS_TTM = "http://www.w3.org/ns/ttml#metadata"
    private const val NS_TTS = "http://www.w3.org/ns/ttml#styling"
    private const val NS_ITUNES = "http://music.apple.com/lyric-ttml-internal"
    private val SAFE_XML_NAME = Regex("[A-Za-z_][A-Za-z0-9_.:-]*")
    private val BODY_MS = Regex("^(\\d+(?:\\.\\d+)?)ms$")
    private val BODY_SECONDS = Regex("^(\\d+(?:\\.\\d+)?)s$")
    private val BODY_PLAIN_SECONDS = Regex("^(\\d+(?:\\.\\d+)?)$")
    private val BODY_CLOCK_HOURS = Regex("^(\\d+):(\\d{2}):(\\d{2})(?:\\.(\\d+))?$")
    private val BODY_CLOCK_MINUTES = Regex("^(\\d+):(\\d{2})(?:\\.(\\d+))?$")

    fun parsePayload(raw: String): LyricData {
        if (raw.isBlank() || raw == "null") return LyricData()
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return RawSLyricsParser.parse(raw)
        if (root.isJsonNull) return LyricData()
        if (root.isJsonPrimitive && root.asJsonPrimitive.isString) {
            return RawSLyricsParser.parse(root.asString)
        }
        val obj = root.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return LyricData()
        if (obj.get("notFound")?.let { runCatching { it.asBoolean }.getOrNull() } == true) {
            return LyricData()
        }

        val type = obj.firstString("type")
        val structured = type.equals("structured", ignoreCase = true) ||
            (type.isNullOrBlank() && obj.firstArray("original", "lines") != null)
        if (!structured) {
            val payload = when (type.orEmpty()) {
                "rawTtml", "raw_ttml", "RAW_TTML", "ttml" -> obj.firstString("rawTtml", "raw_ttml")
                "rawEnhancedLrc", "raw_enhanced_lrc", "RAW_ENHANCED_LRC" ->
                    obj.firstString("rawEnhancedLrc", "raw_enhanced_lrc")
                "rawVerbatimLrc", "raw_verbatim_lrc", "RAW_VERBATIM_LRC" ->
                    obj.firstString("rawVerbatimLrc", "raw_verbatim_lrc")
                "rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc", "RAW_MULTI_PERSON_ENHANCED_LRC" ->
                    obj.firstString("rawMultiPersonEnhancedLrc", "raw_multi_person_enhanced_lrc")
                else -> obj.firstString(
                    "rawPlainLrc",
                    "raw_plain_lrc",
                    "plainLrc",
                    "plain_lrc",
                    "lrc",
                    "originalLrc",
                    "original_lrc",
                    "original",
                )
            }
            return payload?.let(RawSLyricsParser::parse) ?: LyricData()
        }

        val translated = obj.firstArray("translated", "translation", "translations")
            .parseCompactTextLines()
            .associateBy { it.start }
        val romanization = obj.firstArray("romanization", "romanized", "roma")
            .parseCompactWordLines()
            .associateBy { it.start }
        val agents = obj.firstArray("agents").parseAgents()
        val agentsById = agents.associateBy { it.id }
        val metadata = obj.firstArray("metadata").parseMetadataElements()

        val lines = obj.firstArray("original", "lines")
            .parseCompactWordLines()
            .map { original ->
                val translatedLine = translated[original.start]
                val romanLine = romanization[original.start]
                val lineAgent = original.extensions["ttm:agent"]
                LyricLine(
                    timeStamp = original.start,
                    endTime = original.end,
                    text = original.words.joinToString("") { it.text },
                    translation = translatedLine?.words?.joinToString("") { it.text }.orEmpty(),
                    romanization = romanLine?.words?.joinToString("") { it.text }.orEmpty(),
                    words = original.words,
                    pronunciationWords = romanLine?.takeIf { it.wordArray }?.words.orEmpty(),
                    agent = lineAgent,
                    agentName = lineAgent?.let(agentsById::get)?.name,
                    isTtml = true,
                    extensions = original.extensions,
                )
            }
            .sortedBy { it.timeStamp }

        if (lines.isEmpty()) return LyricData()

        val rawBodyDur = obj.firstString("bodyDur", "body_dur")
        return LyricData(
            lines = lines,
            ttmlAgents = agents,
            ttmlMetadata = metadata,
            ttmlTiming = obj.firstString("timing")?.takeIf(String::isNotBlank),
            language = obj.firstString("language")?.takeIf(String::isNotBlank),
            translatedLanguage = obj.firstString("translatedLang", "translated_lang")?.takeIf(String::isNotBlank),
            romanizationLanguage = obj.firstString("romanizationLang", "romanization_lang")?.takeIf(String::isNotBlank),
            bodyDur = rawBodyDur?.takeIf(::isValidTtmlTime),
        )
    }

    fun parsePayloadCandidates(raw: String): List<LyricData> {
        val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
            ?: return listOf(parsePayload(raw)).filterNot(LyricData::isEmpty)
        if (root.isJsonNull) return emptyList()
        val elements = when {
            root.isJsonArray -> root.asJsonArray.toList()
            root.isJsonObject -> {
                val obj = root.asJsonObject
                obj.firstArray("items", "results", "candidates", "lyrics", "data")?.toList()
                    ?: listOf(root)
            }
            else -> listOf(root)
        }
        return elements.mapNotNull { element ->
            val candidate = if (element.isJsonObject) {
                element.asJsonObject.firstObject("lyrics", "result") ?: element
            } else {
                element
            }
            runCatching { parsePayload(candidate.toString()) }
                .getOrNull()
                ?.takeUnless(LyricData::isEmpty)
        }
    }

    fun toTtml(lyrics: LyricData): String {
        val hasRuby = lyrics.lines.any { line -> line.words.any { it.ruby.isNotEmpty() } }
        val customNamespaces = linkedMapOf<String, String>()
        fun collectNamespace(element: LyricTtmlMetadataElement) {
            val prefix = element.name.substringBefore(':', "")
            if (prefix.isNotEmpty() && prefix !in setOf("ttm", "itunes", "xml", "tts")) {
                element.namespace?.takeIf(String::isNotBlank)?.let { customNamespaces.putIfAbsent(prefix, it) }
            }
            element.children.forEach(::collectNamespace)
        }
        lyrics.ttmlMetadata.forEach(::collectNamespace)

        val keys = lyrics.lines.mapIndexed { index, line ->
            line.extensions["itunes:key"]?.takeIf(String::isNotBlank) ?: "L${index + 1}"
        }

        return buildString {
            appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<tt xmlns=\"").append(NS_TTML).append("\"")
            append(" xmlns:ttm=\"").append(NS_TTM).append("\"")
            append(" xmlns:itunes=\"").append(NS_ITUNES).append("\"")
            if (hasRuby) append(" xmlns:tts=\"").append(NS_TTS).append("\"")
            customNamespaces.forEach { (prefix, uri) ->
                append(" xmlns:").append(prefix).append("=\"").append(xmlEscape(uri)).append("\"")
            }
            lyrics.language?.takeIf(String::isNotBlank)?.let {
                append(" xml:lang=\"").append(xmlEscape(it)).append("\"")
            }
            lyrics.ttmlTiming?.takeIf(String::isNotBlank)?.let {
                append(" itunes:timing=\"").append(xmlEscape(it)).append("\"")
            }
            appendLine(">")

            appendHead(this, lyrics, keys)

            append("  <body")
            lyrics.bodyDur?.takeIf(::isValidTtmlTime)?.let {
                append(" dur=\"").append(xmlEscape(it)).append("\"")
            }
            appendLine(">")
            appendLine("    <div>")
            lyrics.lines.forEachIndexed { index, line ->
                appendOriginalLine(this, line, keys[index])
            }
            appendLine("    </div>")
            appendLine("  </body>")
            appendLine("</tt>")
        }
    }

    private fun appendHead(builder: StringBuilder, lyrics: LyricData, keys: List<String>) {
        val hasTranslations = lyrics.lines.any { it.translation.isNotBlank() }
        val hasRomanization = lyrics.lines.any { it.romanization.isNotBlank() || it.pronunciationWords.isNotEmpty() }
        if (lyrics.ttmlAgents.isEmpty() && lyrics.ttmlMetadata.isEmpty() && !hasTranslations && !hasRomanization) {
            return
        }
        builder.appendLine("  <head>")
        if (lyrics.ttmlAgents.isNotEmpty()) {
            builder.appendLine("    <metadata>")
            lyrics.ttmlAgents.forEach { agent ->
                builder.append("      <ttm:agent xml:id=\"").append(xmlEscape(agent.id)).append("\"")
                agent.type?.takeIf(String::isNotBlank)?.let {
                    builder.append(" type=\"").append(xmlEscape(it)).append("\"")
                }
                appendAttributes(builder, agent.attributes, setOf("xml:id", "id", "type"))
                if (agent.name.isNullOrBlank()) {
                    builder.appendLine("/>")
                } else {
                    builder.appendLine(">")
                    builder.append("        <ttm:name type=\"full\">")
                        .append(xmlEscape(agent.name.orEmpty()))
                        .appendLine("</ttm:name>")
                    builder.appendLine("      </ttm:agent>")
                }
            }
            builder.appendLine("    </metadata>")
        }
        if (lyrics.ttmlMetadata.isNotEmpty()) {
            builder.appendLine("    <metadata>")
            lyrics.ttmlMetadata.forEach { appendMetadataElement(builder, it, "      ") }
            builder.appendLine("    </metadata>")
        }
        if (hasTranslations || hasRomanization) {
            builder.appendLine("    <metadata>")
            builder.append("      <iTunesMetadata xmlns=\"").append(NS_ITUNES).appendLine("\">")
            if (hasTranslations) {
                builder.appendLine("        <translations>")
                builder.append("          <translation")
                lyrics.translatedLanguage?.takeIf(String::isNotBlank)?.let {
                    builder.append(" xml:lang=\"").append(xmlEscape(it)).append("\"")
                }
                builder.appendLine(">")
                lyrics.lines.forEachIndexed { index, line ->
                    if (line.translation.isBlank()) return@forEachIndexed
                    builder.append("            <text for=\"").append(xmlEscape(keys[index])).append("\">")
                        .append(xmlEscape(line.translation)).appendLine("</text>")
                }
                builder.appendLine("          </translation>")
                builder.appendLine("        </translations>")
            }
            if (hasRomanization) {
                builder.appendLine("        <transliterations>")
                builder.append("          <transliteration")
                lyrics.romanizationLanguage?.takeIf(String::isNotBlank)?.let {
                    builder.append(" xml:lang=\"").append(xmlEscape(it)).append("\"")
                }
                builder.appendLine(">")
                lyrics.lines.forEachIndexed { index, line ->
                    if (line.romanization.isBlank() && line.pronunciationWords.isEmpty()) return@forEachIndexed
                    builder.append("            <text for=\"").append(xmlEscape(keys[index])).append("\">")
                    if (line.pronunciationWords.isNotEmpty()) {
                        line.pronunciationWords.forEach { word ->
                            builder.append("<span begin=\"").append(formatTtmlTime(word.begin)).append("\" end=\"")
                                .append(formatTtmlTime(word.end.coerceAtLeast(word.begin))).append("\">")
                                .append(xmlEscape(word.text)).append("</span>")
                        }
                    } else {
                        builder.append(xmlEscape(line.romanization))
                    }
                    builder.appendLine("</text>")
                }
                builder.appendLine("          </transliteration>")
                builder.appendLine("        </transliterations>")
            }
            builder.appendLine("      </iTunesMetadata>")
            builder.appendLine("    </metadata>")
        }
        builder.appendLine("  </head>")
    }

    private fun appendOriginalLine(builder: StringBuilder, line: LyricLine, key: String) {
        val end = line.endTime.takeIf { it > line.timeStamp }
            ?: line.words.lastOrNull()?.end?.takeIf { it > line.timeStamp }
            ?: (line.timeStamp + 3_000L)
        builder.append("      <p begin=\"").append(formatTtmlTime(line.timeStamp))
            .append("\" end=\"").append(formatTtmlTime(end)).append("\"")
            .append(" itunes:key=\"").append(xmlEscape(key)).append("\"")
        appendAttributes(
            builder = builder,
            attributes = line.extensions,
            excluded = setOf("begin", "end", "itunes:key", "key"),
        )
        builder.append(">")
        if (line.words.isNotEmpty()) {
            line.words.forEach { appendWord(builder, it) }
        } else {
            builder.append(xmlEscape(line.text))
        }
        builder.appendLine("</p>")
    }

    private fun appendWord(builder: StringBuilder, word: LyricWord) {
        if (word.ruby.isNotEmpty()) {
            builder.append("<span tts:ruby=\"container\">")
            builder.append("<span tts:ruby=\"base\">").append(xmlEscape(word.text)).append("</span>")
            builder.append("<span tts:ruby=\"textContainer\">")
            resolveRubyTimings(word).forEach { ruby ->
                builder.append("<span tts:ruby=\"text\" begin=\"")
                    .append(formatTtmlTime(ruby.begin))
                    .append("\" end=\"").append(formatTtmlTime(ruby.end)).append("\">")
                    .append(xmlEscape(ruby.text)).append("</span>")
            }
            builder.append("</span></span>")
            return
        }
        builder.append("<span begin=\"").append(formatTtmlTime(word.begin))
            .append("\" end=\"").append(formatTtmlTime(word.end.coerceAtLeast(word.begin))).append("\">")
            .append(xmlEscape(word.text)).append("</span>")
    }

    private data class ResolvedRuby(val begin: Long, val end: Long, val text: String)

    private fun resolveRubyTimings(word: LyricWord): List<ResolvedRuby> {
        if (word.ruby.isEmpty()) return emptyList()
        val starts = word.ruby.map { it.begin }.toMutableList()
        val ends = word.ruby.map { it.end }.toMutableList()
        val fallbackStart = word.begin
        val fallbackEnd = word.end.coerceAtLeast(fallbackStart)
        if (starts.first() == null) starts[0] = fallbackStart
        if (ends.last() == null) ends[ends.lastIndex] = fallbackEnd
        repeat(word.ruby.size) {
            for (index in 0 until word.ruby.lastIndex) {
                if (ends[index] != null && starts[index + 1] == null) starts[index + 1] = ends[index]
                if (ends[index] == null && starts[index + 1] != null) ends[index] = starts[index + 1]
            }
        }

        var index = 0
        while (index < word.ruby.size) {
            val rangeStart = starts[index]
            if (rangeStart == null || ends[index] != null) {
                index++
                continue
            }

            var rangeEndIndex = index
            while (rangeEndIndex < word.ruby.lastIndex && ends[rangeEndIndex] == null) {
                rangeEndIndex++
            }
            val rangeEnd = (ends[rangeEndIndex] ?: fallbackEnd).coerceAtLeast(rangeStart)
            val syllableCount = rangeEndIndex - index + 1
            for (syllableIndex in index..rangeEndIndex) {
                val position = syllableIndex - index
                if (starts[syllableIndex] == null) {
                    starts[syllableIndex] = interpolateRubyTime(
                        rangeStart,
                        rangeEnd,
                        position,
                        syllableCount,
                    )
                }
                if (ends[syllableIndex] == null) {
                    ends[syllableIndex] = interpolateRubyTime(
                        rangeStart,
                        rangeEnd,
                        position + 1,
                        syllableCount,
                    )
                }
            }
            index = rangeEndIndex + 1
        }

        var previousEnd = fallbackStart
        return word.ruby.mapIndexed { index, ruby ->
            val begin = starts[index] ?: previousEnd
            val end = ends[index] ?: fallbackEnd.coerceAtLeast(begin)
            previousEnd = end
            ResolvedRuby(begin = begin, end = end, text = ruby.text)
        }
    }

    private fun interpolateRubyTime(start: Long, end: Long, position: Int, segmentCount: Int): Long {
        if (start == end) return start
        return start + ((end - start).toDouble() * position / segmentCount).toLong()
    }

    private fun appendMetadataElement(
        builder: StringBuilder,
        element: LyricTtmlMetadataElement,
        indent: String,
    ) {
        if (!SAFE_XML_NAME.matches(element.name)) return
        builder.append(indent).append('<').append(element.name)
        appendAttributes(builder, element.attributes, emptySet())
        if (element.text.isEmpty() && element.children.isEmpty()) {
            builder.appendLine("/>")
            return
        }
        builder.append('>')
        if (element.text.isNotEmpty()) builder.append(xmlEscape(element.text))
        if (element.children.isNotEmpty()) {
            builder.appendLine()
            element.children.forEach { appendMetadataElement(builder, it, "$indent  ") }
            builder.append(indent)
        }
        builder.append("</").append(element.name).appendLine(">")
    }

    private fun appendAttributes(
        builder: StringBuilder,
        attributes: Map<String, String>,
        excluded: Set<String>,
    ) {
        attributes.forEach { (key, value) ->
            if (key in excluded || !SAFE_XML_NAME.matches(key)) return@forEach
            val prefix = key.substringBefore(':', "")
            if (prefix.isNotEmpty() && prefix !in setOf("ttm", "itunes", "xml", "tts")) return@forEach
            builder.append(' ').append(key).append("=\"").append(xmlEscape(value)).append('"')
        }
    }

    private data class CompactLine(
        val start: Long,
        val end: Long,
        val words: List<LyricWord>,
        val extensions: Map<String, String>,
        val wordArray: Boolean,
    )

    private fun JsonArray?.parseCompactWordLines(): List<CompactLine> = this?.mapNotNull { element ->
        val row = element.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
        val start = row.longAt(0) ?: return@mapNotNull null
        val end = row.longAt(1) ?: start
        val wordsArray = row.arrayAt(2)
        val lineText = row.stringAt(2)
        val extensions = row.objectAt(3)
            ?.stringMap()
            ?.filterKeys { key ->
                val prefix = key.substringBefore(':', "")
                prefix.isEmpty() || prefix == "ttm" || prefix == "itunes"
            }
            .orEmpty()
        val words = when {
            wordsArray != null -> wordsArray.mapNotNull { wordElement ->
                val word = wordElement.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
                val text = word.stringAt(2)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                LyricWord(
                    text = text,
                    begin = word.longAt(0) ?: start,
                    end = word.longAt(1) ?: end,
                    ruby = word.arrayAt(3).parseRuby(),
                )
            }
            !lineText.isNullOrEmpty() -> listOf(LyricWord(text = lineText, begin = start, end = end))
            else -> emptyList()
        }
        if (words.isEmpty()) return@mapNotNull null
        CompactLine(start, end, words, extensions, wordsArray != null)
    }.orEmpty()

    private fun JsonArray?.parseCompactTextLines(): List<CompactLine> = this?.mapNotNull { element ->
        val row = element.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
        val start = row.longAt(0) ?: return@mapNotNull null
        val end = row.longAt(1) ?: start
        val text = row.stringAt(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        CompactLine(start, end, listOf(LyricWord(text, start, end)), emptyMap(), false)
    }.orEmpty()

    private fun JsonArray?.parseRuby(): List<LyricRubySyllable> = this?.mapNotNull { element ->
        val row = element.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return@mapNotNull null
        val text = row.stringAt(2)?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        LyricRubySyllable(begin = row.longAt(0), end = row.longAt(1), text = text)
    }.orEmpty()

    private fun JsonArray?.parseAgents(): List<LyricTtmlAgent> = this?.mapNotNull { element ->
        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val id = obj.firstString("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        LyricTtmlAgent(
            id = id,
            type = obj.firstString("type")?.takeIf(String::isNotBlank),
            name = obj.firstString("name")?.takeIf(String::isNotBlank),
        )
    }.orEmpty()

    private fun JsonArray?.parseMetadataElements(): List<LyricTtmlMetadataElement> = this?.mapNotNull { element ->
        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val node = obj.parseMetadataElement() ?: return@mapNotNull null
        when (node.name) {
            "songwriters" -> node.takeIf {
                it.children.isNotEmpty() && it.children.all { child ->
                    child.name == "songwriter" && child.text.isNotBlank()
                }
            }
            "translations", "transliterations", "ttm:agent" -> null
            else -> node
        }
    }.orEmpty()

    private fun JsonObject.parseMetadataElement(): LyricTtmlMetadataElement? {
        val name = firstString("name")?.takeIf(String::isNotBlank) ?: return null
        val namespace = firstString("namespace")?.takeIf(String::isNotBlank)
        val prefix = name.substringBefore(':', "")
        if (prefix.isNotEmpty() && prefix !in setOf("ttm", "itunes", "xml") && namespace == null) return null
        val attributes = get("attributes")
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?.stringMap()
            .orEmpty()
        val children = get("children")
            ?.takeIf(JsonElement::isJsonArray)
            ?.asJsonArray
            ?.mapNotNull { child ->
                child.takeIf(JsonElement::isJsonObject)?.asJsonObject?.parseMetadataElement()
            }
            .orEmpty()
        return LyricTtmlMetadataElement(
            name = name,
            namespace = namespace,
            attributes = attributes,
            text = firstString("text").orEmpty(),
            children = children,
        )
    }

    private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        val value = get(key) ?: return@firstNotNullOfOrNull null
        when {
            value.isJsonPrimitive -> runCatching { value.asString }.getOrNull()
            value.isJsonArray -> value.asJsonArray.joinToString("/") { item ->
                when {
                    item.isJsonPrimitive -> runCatching { item.asString }.getOrDefault("")
                    item.isJsonObject -> item.asJsonObject.firstString("name", "title", "value").orEmpty()
                    else -> ""
                }
            }.takeIf(String::isNotBlank)
            else -> null
        }
    }

    private fun JsonObject.firstArray(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { key -> get(key)?.takeIf(JsonElement::isJsonArray)?.asJsonArray }

    private fun JsonObject.firstObject(vararg keys: String): JsonObject? =
        keys.firstNotNullOfOrNull { key -> get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject }

    private fun JsonObject.stringMap(): Map<String, String> = entrySet().mapNotNull { (key, value) ->
        value.takeIf(JsonElement::isJsonPrimitive)?.let {
            runCatching { it.asString }.getOrNull()?.let { stringValue -> key to stringValue }
        }
    }.toMap()

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null
    private fun JsonArray.longAt(index: Int): Long? = getOrNull(index)?.let { element ->
        if (element.isJsonNull) null else runCatching { element.asLong }.getOrNull()
    }
    private fun JsonArray.stringAt(index: Int): String? = getOrNull(index)?.let { element ->
        if (element.isJsonNull || !element.isJsonPrimitive) null else runCatching { element.asString }.getOrNull()
    }
    private fun JsonArray.arrayAt(index: Int): JsonArray? = getOrNull(index)?.takeIf(JsonElement::isJsonArray)?.asJsonArray
    private fun JsonArray.objectAt(index: Int): JsonObject? = getOrNull(index)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

    private fun isValidTtmlTime(value: String): Boolean {
        val text = value.trim().takeIf(String::isNotEmpty) ?: return false
        if (BODY_MS.matches(text) || BODY_SECONDS.matches(text) || BODY_PLAIN_SECONDS.matches(text)) {
            return text.removeSuffix("ms").removeSuffix("s").toDoubleOrNull()?.isFinite() == true
        }
        return BODY_CLOCK_HOURS.matches(text) || BODY_CLOCK_MINUTES.matches(text)
    }

    private fun formatTtmlTime(value: Long): String {
        val safe = value.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
