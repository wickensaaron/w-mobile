package com.nuvio.app.features.livetv

import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import kotlinx.coroutines.ensureActive

/** HTTP-engine-delivered bytes and decompressed XML bytes have independent budgets. Neither is buffered. */
internal data class ImportedXmlTvLimits(
    val deliveredBytes: Long = 128L * 1024 * 1024,
    val decodedBytes: Long = 256L * 1024 * 1024,
    val retainedProgrammes: Int = MobileLiveTvProgrammeLimit,
    val perChannel: Int = 24,
    val tokenBytes: Int = 64 * 1024,
) {
    init {
        require(deliveredBytes > 0 && decodedBytes > 0 && retainedProgrammes in 0..MobileLiveTvProgrammeLimit)
        require(perChannel in 1..96 && tokenBytes in 256..65_536)
    }
}

internal data class ImportedXmlTvResult(
    val programmes: Map<String, List<LiveTvProgramme>>,
    val wasTruncated: Boolean,
)

internal class ImportedXmlTvByteBudget(private val limit: Long) {
    private var consumed = 0L
    init { require(limit > 0) }
    fun accept(length: Int) {
        require(length >= 0)
        check(length <= limit - consumed) { "Live TV guide exceeds device byte limit" }
        consumed += length
    }
}

/** Platform XML parsers own well-formedness, entity decoding and split UTF-8/tag handling. */
internal expect suspend fun parseImportedXmlTvStream(
    read: suspend (ByteArray) -> Int,
    collector: ImportedXmlTvCollector,
    limits: ImportedXmlTvLimits,
): ImportedXmlTvResult

/** Callback-only state, confined to the platform parser's worker. No fuzzy name or cross-provider mapping. */
internal class ImportedXmlTvCollector(
    sourceId: String,
    channels: List<LiveTvChannel>,
    private val now: Long,
    private val context: CoroutineContext,
    private val isCurrent: () -> Boolean,
    private val limits: ImportedXmlTvLimits,
) {
    private data class OwnedGuideChannel(val id: String, val key: String)
    private val ownedChannelsByGuideKey = channels.asSequence()
        .filter { it.accountScope != null && it.playlistId == sourceId && it.id.startsWith("$sourceId:") }
        .filter { (it.guideId ?: it.name).let { key -> key.isNotBlank() && key.length <= 2048 } }
        .distinctBy { it.id }
        .map { OwnedGuideChannel(it.id, it.guideId ?: it.name) }
        .groupBy { importedXmlTvCaseKey(it.key) }
    private val selectedXmlKeyByOwnedId = mutableMapOf<String, String>()
    private val rows = linkedMapOf<String, MutableList<LiveTvProgramme>>()
    private var retained = 0
    private val budgetDroppedOwnedIds = mutableSetOf<String>()
    private var depth = 0
    private var programmeDepth = 0
    private var fieldDepth = 0
    private var field: String? = null
    private var guideKey: String? = null
    private var start: Long? = null
    private var stop: Long? = null
    private var title: StringBuilder? = null
    private var description: StringBuilder? = null
    private var callbacks = 0

    val needsText: Boolean get() = when (field) {
        "title" -> (title?.length ?: 512) < 512
        "desc" -> (description?.length ?: 2048) < 2048
        else -> false
    }

    fun checkCurrent(force: Boolean = true) {
        if (force || callbacks++ % 256 == 0) {
            context.ensureActive()
            check(isCurrent()) { "Live TV guide source changed" }
        }
    }

    fun startElement(name: String, channel: String?, from: String?, until: String?) {
        checkCurrent(force = false)
        depth++
        check(depth <= 32) { "Live TV XML nesting exceeds device limit" }
        if (depth == 1) check(name == "tv") { "Response is not an XMLTV guide" }
        if (name == "programme") {
            check(depth == 2) { "Invalid XMLTV programme location" }
            check(programmeDepth == 0) { "Invalid nested XMLTV programme" }
            programmeDepth = depth
            // Unrelated programmes are scanned to EOF, without dates, title builders or retained rows.
            guideKey = channel?.takeIf { it.length <= 2048 && importedXmlTvCaseKey(it) in ownedChannelsByGuideKey }
            start = if (guideKey != null) importedXmlTvTimestamp(from) else null
            stop = if (guideKey != null) importedXmlTvTimestamp(until) else null
            if (start == null || stop == null || stop!! <= now || stop!! <= start!! ||
                start!! >= now + 48L * 60 * 60 * 1000) guideKey = null
            title = null
            description = null
            field = null
        } else if (guideKey != null && depth == programmeDepth + 1) {
            when {
                name == "title" && title == null -> { title = StringBuilder(); field = name; fieldDepth = depth }
                name == "desc" && description == null -> { description = StringBuilder(); field = name; fieldDepth = depth }
            }
        }
    }

    fun text(value: String) {
        checkCurrent(force = false)
        val builder = when (field) { "title" -> title; "desc" -> description; else -> null } ?: return
        val cap = if (field == "title") 512 else 2048
        val remaining = cap - builder.length
        if (remaining > 0) builder.append(value, 0, minOf(value.length, remaining))
    }

    fun endElement(name: String) {
        checkCurrent(force = false)
        if (depth == fieldDepth) { field = null; fieldDepth = 0 }
        if (name == "programme" && depth == programmeDepth) {
            val key = guideKey
            val nameText = title?.toString()?.trim().orEmpty()
            if (key != null && nameText.isNotBlank()) {
                val from = start!!
                val until = stop!!
                val detail = description?.toString()?.trim()?.takeIf { it.isNotBlank() }
                for (owned in ownedChannelsByGuideKey.getValue(importedXmlTvCaseKey(key))) {
                    checkCurrent(force = false)
                    val ownedId = owned.id
                    val selected = selectedXmlKeyByOwnedId[ownedId]
                    if (selected != null && selected != key) {
                        // Preserve the prior projection: exact spelling wins over the first valid
                        // case-insensitive XML entry, regardless of feed programme order.
                        if (key != owned.key) continue
                        val removed = rows.remove(ownedId)
                        retained -= removed?.size ?: 0
                        budgetDroppedOwnedIds.remove(ownedId)
                    }
                    selectedXmlKeyByOwnedId[ownedId] = key
                    val existing = rows[ownedId]
                    if (existing?.any { it.startEpochMs == from && it.stopEpochMs == until && it.title == nameText } == true) continue
                    // Keep the nearest 24 unexpired rows even if the feed is in reverse time order.
                    if (existing != null && existing.size >= limits.perChannel) {
                        if (from >= existing.last().startEpochMs) continue
                        existing.removeAt(existing.lastIndex)
                        retained--
                    }
                    if (retained >= limits.retainedProgrammes) { budgetDroppedOwnedIds += ownedId; continue }
                    val list = existing ?: mutableListOf<LiveTvProgramme>().also { rows[ownedId] = it }
                    val row = LiveTvProgramme(ownedId, nameText, detail, from, until)
                    var insertion = list.binarySearch { it.startEpochMs.compareTo(from) }
                    if (insertion < 0) insertion = -insertion - 1
                    list.add(insertion, row)
                    retained++
                }
            }
            guideKey = null
            programmeDepth = 0
            title = null
            description = null
            field = null
        }
        depth--
        check(depth >= 0) { "Invalid XMLTV structure" }
    }

    fun snapshot(): ImportedXmlTvResult {
        checkCurrent()
        check(depth == 0) { "Incomplete XMLTV guide" }
        return ImportedXmlTvResult(rows.mapValues { (_, value) -> value.toList() }, budgetDroppedOwnedIds.isNotEmpty())
    }
}

private fun importedXmlTvCaseKey(key: String): String = buildString(key.length) {
    // Match String.equals(ignoreCase = true), including one-character Unicode case variants.
    key.forEach { append(it.uppercaseChar().lowercaseChar()) }
}

private fun importedXmlTvTimestamp(value: String?): Long? {
    val raw = value?.trim() ?: return null
    if (raw.length !in 14..32 || raw.take(14).any { it !in '0'..'9' }) return null
    val zone = raw.drop(14).trim()
    if (zone.isNotEmpty() && !zone.matches(Regex("[+-][0-9]{4}"))) return null
    val offset = if (zone.isEmpty()) "Z" else "${zone.take(3)}:${zone.drop(3)}"
    return runCatching { Instant.parse("${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}T" +
        "${raw.substring(8, 10)}:${raw.substring(10, 12)}:${raw.substring(12, 14)}$offset").toEpochMilliseconds() }.getOrNull()
}

/**
 * Security/resource preflight, NOT an XML parser. Native SAX validates XML syntax afterwards.
 * UTF-8 only; at most three carried continuation bytes. No DTD subset or declared entities.
 * Comment/CDATA contents are discarded incrementally; tags/text/PI/DOCTYPE cannot grow unbounded
 * inside the native parser before its next callback. External DOCTYPE declarations are allowed,
 * but both native adapters disable resolution and fetching independently.
 */
internal class ImportedXmlTvByteGuard(private val limits: ImportedXmlTvLimits) {
    private enum class Mode { Text, Prefix, Tag, Comment, Cdata, Doctype, Pi }
    private var mode = Mode.Text
    private val prefix = StringBuilder(10)
    private var tokenBytes = 0
    private val decodedBudget = ImportedXmlTvByteBudget(limits.decodedBytes)
    private var quote = 0
    private var tail = 0
    private var entityBytes = 0
    private var continuation = 0
    private var codePoint = 0
    private var minimum = 0
    private var declaration: StringBuilder? = null

    fun accept(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size)
        decodedBudget.accept(length)
        for (index in 0 until length) {
            val byte = bytes[index].toInt() and 255
            validateUtf8(byte)
            val char = byte.toChar()
            tokenBytes++
            check(tokenBytes <= limits.tokenBytes) { "Live TV XML token exceeds device limit" }
            when (mode) {
                Mode.Text -> {
                    if (char == '<') { mode = Mode.Prefix; prefix.clear(); prefix.append(char); tokenBytes = 1; entityBytes = 0 }
                    else checkEntity(char)
                }
                Mode.Prefix -> {
                    prefix.append(char)
                    val value = prefix.toString()
                    when {
                        value == "<!--" -> { mode = Mode.Comment; tail = 0 }
                        value == "<![CDATA[" -> { mode = Mode.Cdata; tail = 0 }
                        value == "<!DOCTYPE" -> { mode = Mode.Doctype; quote = 0 }
                        value == "<?" -> { mode = Mode.Pi; tail = 0; declaration = StringBuilder("<?") }
                        !value.startsWith("<!") && value.length >= 2 -> {
                            mode = Mode.Tag; quote = 0; tagByte(char)
                        }
                        listOf("<!--", "<![CDATA[", "<!DOCTYPE").none { it.startsWith(value) } ->
                            error("Unsupported XML declaration")
                    }
                }
                Mode.Tag -> tagByte(char)
                Mode.Comment -> { tail = ((tail shl 8) or byte) and 0xffffff; if (tail == 0x2d2d3e) reset() }
                Mode.Cdata -> { tail = ((tail shl 8) or byte) and 0xffffff; if (tail == 0x5d5d3e) reset() }
                Mode.Doctype -> {
                    if (quote != 0) { if (byte == quote) quote = 0 }
                    else when (char) { '\'', '"' -> quote = byte; '[' -> error("XML DTD subsets are not supported"); '>' -> reset() }
                }
                Mode.Pi -> {
                    // Only the short initial prefix is stored for the XML declaration's encoding check.
                    declaration?.let { if (it.length < 1024) it.append(char) }
                    tail = ((tail shl 8) or byte) and 0xffff
                    if (tail == 0x3f3e) {
                        val pi = declaration?.toString().orEmpty()
                        if (pi.startsWith("<?xml") && pi.getOrNull(5)?.isWhitespace() == true) {
                            check(tokenBytes <= 1024) { "XML declaration exceeds device limit" }
                            val encoding = Regex("encoding\\s*=\\s*(['\"])(.*?)\\1").find(pi)?.groupValues?.get(2)
                            check(encoding == null || encoding.equals("UTF-8", true) || encoding.equals("US-ASCII", true)) {
                                "Only UTF-8 XMLTV is supported"
                            }
                        }
                        declaration = null
                        reset()
                    }
                }
            }
        }
    }

    fun finish() {
        check(continuation == 0 && mode == Mode.Text) { "Incomplete XMLTV input" }
    }

    private fun reset() { mode = Mode.Text; tokenBytes = 0; quote = 0; entityBytes = 0; tail = 0 }
    private fun tagByte(char: Char) {
        if (quote != 0) { checkEntity(char); if (char.code == quote) { quote = 0; entityBytes = 0 } }
        else when (char) { '\'', '"' -> { quote = char.code; entityBytes = 0 }; '>' -> reset() }
    }
    private fun checkEntity(char: Char) {
        if (char == '&') entityBytes = 1
        else if (entityBytes != 0) {
            entityBytes++
            check(entityBytes <= 256) { "XML entity exceeds device limit" }
            if (char == ';') entityBytes = 0
        }
    }
    private fun validateUtf8(byte: Int) {
        if (continuation > 0) {
            check(byte in 0x80..0xbf) { "Invalid XMLTV UTF-8" }
            codePoint = (codePoint shl 6) or (byte and 63)
            if (--continuation == 0) check(codePoint >= minimum && codePoint <= 0x10ffff &&
                codePoint !in 0xd800..0xdfff && codePoint !in 0xfffe..0xffff) { "Invalid XMLTV UTF-8" }
        } else when (byte) {
            in 0..127 -> check(byte >= 32 || byte == 9 || byte == 10 || byte == 13) { "Invalid XMLTV character" }
            in 0xc2..0xdf -> { continuation = 1; codePoint = byte and 31; minimum = 0x80 }
            in 0xe0..0xef -> { continuation = 2; codePoint = byte and 15; minimum = 0x800 }
            in 0xf0..0xf4 -> { continuation = 3; codePoint = byte and 7; minimum = 0x10000 }
            else -> error("Invalid XMLTV UTF-8")
        }
    }
}
