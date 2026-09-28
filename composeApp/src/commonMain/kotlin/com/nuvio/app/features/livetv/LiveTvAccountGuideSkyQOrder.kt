package com.nuvio.app.features.livetv



private val skyQKeyPunctuation = Regex("""[^\p{L}\p{N}+]""")
private fun accountGuideSkyQNameKey(name: String): String = name.lowercase().replace(skyQKeyPunctuation, "")

/** England baseline, verified 27 September 2026. Ported unchanged from W TV; regional numbering is not claimed. */
private val accountGuideSkyQNumbers = buildMap<String, Int> {
    fun service(number: Int, vararg aliases: String) = aliases.forEach { put(accountGuideSkyQNameKey(it), number) }
    service(101, "BBC One", "BBC1")
    service(102, "BBC Two", "BBC2")
    service(103, "ITV1", "ITV", "STV", "UTV")
    service(104, "Channel 4", "C4")
    service(105, "5", "Channel 5", "Five")
    service(106, "Sky One", "Sky1")
    service(107, "Sky Witness")
    service(108, "Sky Atlantic")
    service(109, "U&Alibi", "Alibi")
    service(110, "U&Gold", "Gold")
    service(111, "U&Dave", "Dave")
    service(112, "Comedy Central")
    service(113, "Sky Comedy")
    service(114, "Sky Documentaries")
    service(115, "BBC Three", "BBC3")
    service(116, "BBC Four", "BBC4")
    service(118, "ITV2")
    service(119, "ITV3")
    service(120, "ITV4")
    service(121, "Sky Crime")
    service(122, "Sky Arts")
    service(123, "Sky History")
    service(124, "Sky Nature")
    service(125, "Discovery", "Discovery Channel")
    service(126, "MTV")
    service(127, "Comedy Central Extra", "Comedy Central Xtra")
    service(128, "5STAR", "Five Star")
    service(129, "National Geographic", "Nat Geo")
    service(130, "Challenge")
    service(131, "ITV Quiz")
    service(132, "U&W", "W")
    service(133, "TLC")
    service(134, "S4C")
    service(135, "E4")
    service(136, "More4")
    service(137, "4seven")
    service(138, "E4 Extra")
    service(139, "Crime+Investigation", "Crime and Investigation", "Crime & Investigation")
    service(140, "Quest")
    service(141, "5USA", "Five USA")
    service(142, "Really")
    service(143, "U&Drama", "Drama")
    service(144, "Food Network")
    service(145, "Sky Sci-Fi", "Sky Sci Fi")
    service(146, "True Crime")
    service(147, "True Crime Xtra")
    service(148, "Legend")
    service(149, "Quest Red")
    service(150, "5Action")
    service(151, "Sky Mix")
    service(153, "5Select")
    service(154, "Investigation Discovery", "ID")
    service(155, "U&Yesterday", "Yesterday")
    service(156, "Blaze")
    service(157, "Great! TV")
    service(158, "Discovery Turbo", "Disc. Turbo")
    service(159, "DMAX")
    service(161, "Discovery History", "Disc. History")
    service(162, "Animal Planet")
    service(163, "Sky History 2")
    service(165, "National Geographic Wild", "Nat Geo Wild")
    service(166, "U&Eden", "Eden")
    service(167, "Discovery Science", "Disc. Science", "Discovery Sci")
    service(169, "BBC Alba")
    service(170, "Together TV", "Together")
    service(173, "PBS America")
    service(178, "Travelxp")
    service(180, "BBC Scotland")
    service(182, "Rewind TV")
    service(203, "ITV1 +1", "ITV +1")
    service(204, "Channel 4 +1", "C4 +1")
    service(205, "5 +1", "Channel 5 +1", "Five +1")
    service(207, "Sky Witness +1")
    service(208, "Sky Atlantic +1")
    service(209, "U&Alibi +1", "Alibi +1")
    service(210, "U&Gold +1", "Gold +1")
    service(211, "U&Dave Ja Vu", "U&Dave Ja Vu +1", "Dave Ja Vu", "Dave +1")
    service(212, "Comedy Central +1")
    service(218, "ITV2 +1")
    service(219, "ITV3 +1")
    service(220, "ITV4 +1")
    service(221, "Sky Crime +1")
    service(225, "Discovery +1", "Discovery Channel +1")
    service(228, "5STAR +1")
    service(229, "Nat Geo +1", "National Geographic +1")
    service(232, "U&W +1", "W +1")
    service(233, "TLC +1")
    service(235, "E4 +1")
    service(236, "More4 +1")
    service(239, "Crime+Investigation +1", "Crime and Investigation +1", "Crime & Investigation +1")
    service(240, "Quest +1")
    service(241, "5USA +1")
    service(242, "Really +1")
    service(243, "U&Drama +1", "Drama +1")
    service(244, "Food Network +1")
    service(246, "True Crime +1")
    service(249, "Quest Red +1")
    service(254, "ID +1", "Investigation Discovery +1")
    service(255, "U&Yesterday +1", "Yesterday +1")
    service(257, "Great! TV +1")
    service(258, "Discovery Turbo +1")
    service(259, "DMAX +1")
    service(262, "Animal Planet +1")
    service(266, "U&Eden +1", "Eden +1")
    service(267, "Discovery Science +1")
    service(301, "Sky Cinema Premiere", "Sky Premiere")
    service(302, "Sky Cinema Select", "Sky Cinema Pop-Up", "Sky Cinema Five Star Movies")
    service(303, "Sky Cinema Hits", "Sky Cinema Pop-Up 2", "Sky Cinema Jurassic")
    service(304, "Sky Cinema Family")
    service(305, "Disney+ Cinema")
    service(306, "Sky Cinema Action")
    service(307, "Sky Cinema Greats")
    service(308, "Sky Cinema Comedy")
    service(309, "Sky Cinema Thriller")
    service(310, "Sky Cinema Drama")
    service(311, "Sky Cinema SciFi/Horror", "Sky Cinema Sci Fi & Horror", "Sky Cinema Sci Fi and Horror")
    service(312, "Movies 24")
    service(313, "Film4")
    service(314, "Film4 +1")
    service(315, "Movies 24+")
    service(316, "Legend Xtra")
    service(317, "Legend Xtra +1")
    service(318, "Great! Action")
    service(319, "Great! Action +1")
    service(320, "Great! Mystery")
    service(321, "Great! Mystery +1")
    service(322, "Great! Romance", "Great! Christmas")
    service(323, "Great! Romance +1", "Great! Christmas +1")
    service(324, "Talking Pictures TV", "TalkingPictures")
    service(354, "Clubland TV")
    service(355, "NOW 70s")
    service(356, "NOW 80s")
    service(357, "NOW 90s and 00s")
    service(358, "NOW Rock")
    service(401, "Sky Sports Main Event")
    service(402, "Sky Sports Premier League")
    service(403, "Sky Sports Football")
    service(404, "Sky Sports+", "Sky Sports Plus")
    service(405, "Sky Sports Cricket")
    service(406, "Sky Sports Golf")
    service(407, "Sky Sports F1")
    service(408, "Sky Sports Tennis")
    service(409, "Sky Sports News")
    service(410, "TNT Sports 1")
    service(411, "TNT Sports 2")
    service(412, "Sky Sports Action", "Sky Sports NFL")
    service(413, "TNT Sports 3")
    service(414, "TNT Sports 4")
    service(415, "Sky Sports Racing")
    service(416, "Sky Sports Mix")
    service(418, "MUTV")
    service(419, "Premier Sports 1")
    service(420, "Premier Sports 2")
    service(423, "LFCTV")
    service(424, "Racing TV")
    service(490, "TNT Sports Box Office")
    service(491, "Sky Sports Box Office")
    service(493, "TNT Sports Ultimate")
    service(494, "TNT Sports Box Office 2")
    service(501, "Sky News")
    service(502, "Bloomberg", "Bloomberg Television")
    service(503, "BBC News")
    service(504, "BBC Parliament")
    service(505, "CNBC", "CNBC Europe")
    service(506, "CNN", "CNN International", "CNN International EMEA")
    service(507, "NHK World", "NHK World Japan")
    service(508, "Euronews")
    service(509, "NDTV World")
    service(510, "France 24", "France 24 English")
    service(511, "Al Jazeera English")
    service(512, "GB News")
    service(513, "TRT World")
    service(515, "Channels 24")
    service(516, "Arise News")
    service(518, "Arirang TV")
    service(520, "TVC News")
    service(521, "NTD")
    service(522, "DM News", "DM News English")
    service(601, "Cartoon Network")
    service(602, "Cartoon Network +1", "CN +1")
    service(603, "Boomerang")
    service(604, "Nickelodeon")
    service(605, "Nicktoons")
    service(606, "Nick Jr")
    service(607, "CBBC")
    service(608, "CBeebies")
    service(609, "Sky Kids")
    service(610, "Cartoonito")
    service(611, "Boomerang +1")
    service(612, "Nick Jr Too", "Nick Jr 2")
    service(613, "Disney Jr", "Disney Junior")
    service(615, "Nickelodeon +1")
    service(619, "Nick Jr +1")
    service(623, "BabyTV")
}

private val accountGuideSkyQBbcRegions = setOf("england", "scotland", "wales", "northernireland", "ni", "london", "east",
    "eastmidlands", "westmidlands", "northwest", "northeast", "northeastcumbria", "northeastandcumbria", "south",
    "southeast", "southwest", "west", "yorkshire", "yorkshirelincolnshire", "yorkshireandlincolnshire",
    "eastyorkshirelincolnshire", "eastyorkshireandlincolnshire", "yorksandlincs", "yorkslincs", "eastyorkslincs",
    "eyorkslincs", "eyorksandlincs", "eastyorksandlincs", "westmids", "eastmids", "necumbria",
    "yorkshirenorthmidlands", "yorkshireandnorthmidlands",
    "channelislands", "cambridge", "cambridgeshire", "oxford", "oxfordshire")
private val accountGuideSkyQItvRegions = setOf("anglia", "angliaeast", "angliawest",
    "border", "borderengland", "borderscotland", "central", "centraleast", "centralwest", "granada", "meridian",
    "meridianeast", "meridianwest", "meridiansouth", "meridiansoutheast", "meridianthamesvalley", "tynetees",
    "westcountry", "westcountryeast", "westcountrywest", "yorkshireeast", "yorkshirewest", "yorkshirenorth",
    "yorkshiresouth", "channel", "channeltv", "channelislands", "north", "midlands", "thamesvalley",
    "london", "england", "scotland", "wales", "northernireland", "ni", "east", "west", "south", "southeast", "southwest")
private val accountGuideSkyQFourRegions = setOf("london", "midlands", "north", "south", "east", "west", "england", "scotland",
    "wales", "northernireland", "nireland", "ni", "ireland", "ulster")

/** Input is a tidy display name. Unknown services/suffixes get no invented number. */
internal fun accountGuideSkyQNumber(name: String): Int? {
    if (accountGuideIsRadioName(name)) return null
    val key = accountGuideSkyQNameKey(name)
    accountGuideSkyQNumbers[key]?.let { return it }
    val shifted = key.endsWith("+1")
    val withoutShift = if (shifted) key.removeSuffix("+1") else key
    listOf("bbcone" to 101, "bbc1" to 101, "bbctwo" to 102, "bbc2" to 102,
        "itv1" to 103, "channel4" to 104, "c4" to 104).forEach { (base, number) ->
        val region = when {
            withoutShift.startsWith(base) -> withoutShift.removePrefix(base)
            else -> return@forEach
        }
        val regionWithoutShift = region.removePrefix("+1")
        val knownRegions = when (number) { 101, 102 -> accountGuideSkyQBbcRegions; 103 -> accountGuideSkyQItvRegions; else -> accountGuideSkyQFourRegions }
        if (regionWithoutShift in knownRegions) {
            val timeshift = shifted || region.startsWith("+1")
            return if (timeshift) if (number == 103 || number == 104) number + 100 else null else number
        }
    }
    return null
}


/** Exact catalogue aliases only; regional families remain distinct until explicitly collapsed. */
internal fun accountGuideExactSkyQNumber(name: String): Int? = accountGuideSkyQNumbers[accountGuideSkyQNameKey(name)]

/** Only the explicit regional families used by W TV qualify; unfamiliar suffixes remain distinct. */
internal fun accountGuideRegionalFamily(name: String): Pair<String, Boolean>? {
    val key = accountGuideSkyQNameKey(name)
    val shiftedAtEnd = key.endsWith("+1")
    val withoutShift = if (shiftedAtEnd) key.removeSuffix("+1") else key
    for ((base, number) in listOf("bbcone" to 101, "bbc1" to 101, "bbctwo" to 102, "bbc2" to 102,
        "itv1" to 103, "itv" to 103, "channel4" to 104, "c4" to 104)) {
        if (!withoutShift.startsWith(base)) continue
        val suffix = withoutShift.removePrefix(base)
        val region = suffix.removePrefix("+1")
        val shifted = shiftedAtEnd || suffix.startsWith("+1")
        if (shifted && number != 103 && number != 104) return null
        val known = when (number) {
            101, 102 -> accountGuideSkyQBbcRegions
            103 -> accountGuideSkyQItvRegions
            else -> accountGuideSkyQFourRegions
        }
        if (region.isEmpty() || region in known) {
            return "region:${if (shifted) number + 100 else number}" to region.isEmpty()
        }
    }
    return null
}
