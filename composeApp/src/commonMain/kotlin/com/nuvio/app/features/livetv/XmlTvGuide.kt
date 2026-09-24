package com.nuvio.app.features.livetv

import kotlin.time.Clock
import kotlin.time.Instant

/** A bounded XMLTV reader for the common programme/title/desc subset. */
internal fun parseXmlTvGuide(
    xml: String,
    now: Long = Clock.System.now().toEpochMilliseconds(),
): Map<String, List<LiveTvProgramme>> {
    val byChannel = mutableMapOf<String, MutableList<LiveTvProgramme>>()
    val programmePattern = Regex("<programme\\b([^>]*)>(.*?)</programme>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    for (match in programmePattern.findAll(xml).take(100_000)) {
        val attributes = match.groupValues[1]
        val channel = xmlAttribute(attributes, "channel")?.let(::xmlText)?.trim().orEmpty()
        val start = xmlTvTimestamp(xmlAttribute(attributes, "start")) ?: continue
        val stop = xmlTvTimestamp(xmlAttribute(attributes, "stop")) ?: continue
        if (channel.isBlank() || stop <= now || start > now + 48L * 60 * 60 * 1000 || stop <= start) continue
        val body = match.groupValues[2]
        val title = xmlTag(body, "title")?.takeIf(String::isNotBlank) ?: continue
        val description = xmlTag(body, "desc")?.takeIf(String::isNotBlank)
        byChannel.getOrPut(channel) { mutableListOf() } += LiveTvProgramme(channel, title, description, start, stop)
    }
    return byChannel.mapValues { (_, programmes) -> programmes.sortedBy { it.startEpochMs }.take(24) }
}

private fun xmlAttribute(attributes: String, name: String): String? =
    Regex("\\b${Regex.escape(name)}\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
        .find(attributes)?.groupValues?.get(2)

private fun xmlTag(body: String, name: String): String? =
    Regex("<${Regex.escape(name)}(?:\\s[^>]*)?>(.*?)</${Regex.escape(name)}>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(body)?.groupValues?.get(1)?.let(::xmlText)?.trim()

private fun xmlText(value: String): String = value
    .removePrefix("<![CDATA[").removeSuffix("]]>")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&apos;", "'")

private fun xmlTvTimestamp(value: String?): Long? {
    val raw = value?.trim() ?: return null
    if (raw.length < 14 || raw.take(14).any { !it.isDigit() }) return null
    val dateTime = "${raw.substring(0, 4)}-${raw.substring(4, 6)}-${raw.substring(6, 8)}T" +
        "${raw.substring(8, 10)}:${raw.substring(10, 12)}:${raw.substring(12, 14)}"
    val offset = raw.drop(14).trim().takeIf { it.matches(Regex("[+-][0-9]{4}")) }
    val zone = if (offset == null) "Z" else "${offset.take(3)}:${offset.drop(3)}"
    return runCatching { Instant.parse(dateTime + zone).toEpochMilliseconds() }.getOrNull()
}
