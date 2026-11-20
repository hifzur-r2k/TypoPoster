package com.typoposter.app.voice

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 100% offline spoken deadline to countdown tag converter for English, Hindi, and Hinglish.
 *
 * Examples:
 * - "tomorrow" / "kal" / "कल" -> @tomorrow
 * - "day after tomorrow" / "parso" / "परसों" -> +2 days date tag (e.g. @21 Nov)
 * - "in 3 days" / "3 din baad" / "3 दिन बाद" -> +3 days date tag
 * - "next Friday" / "agle shukrawar" / "अगले शुक्रवार" -> calculated next Friday date tag
 * - "30 November" / "25 Dec" / "25 Dec 2026" -> @30 Nov / @25 Dec / @25 Dec 2026
 *
 * Keeps the rest of the spoken sentence clean and natural.
 */
object SpokenDateParser {

    private const val BOUNDARY_PREFIX = "(?<=^|[\\s,.;:!?।])"
    private const val BOUNDARY_SUFFIX = "(?=$|[\\s,.;:!?।])"

    private fun boundaryRegex(pattern: String) = Regex(
        "$BOUNDARY_PREFIX(?:$pattern)$BOUNDARY_SUFFIX",
        RegexOption.IGNORE_CASE
    )

    fun convert(spokenText: String, today: LocalDate = LocalDate.now()): String {
        if (spokenText.isBlank()) return spokenText

        var result = spokenText

        // 1. Day after tomorrow: "the day after tomorrow", "day after tomorrow", "parso", "parson", "परसों"
        val plus2Tag = formatDateTag(today.plusDays(2), today)
        result = result.replace(
            boundaryRegex("(?:the\\s+)?day\\s+after\\s+tomorrow"),
            plus2Tag
        )
        result = result.replace(
            boundaryRegex("(?:parso|parson)(?:\\s+tak)?"),
            plus2Tag
        )
        result = result.replace(
            boundaryRegex("परसों(?:\\s*तक)?"),
            plus2Tag
        )

        // 2. Relative weeks: "in a week", "after a week", "ek hafte baad", "1 hafte baad", "एक हफ्ते बाद"
        val plus7Tag = formatDateTag(today.plusDays(7), today)
        result = result.replace(
            boundaryRegex("(?:in|after)\\s+(?:a|1|one)\\s+weeks?"),
            plus7Tag
        )
        result = result.replace(
            boundaryRegex("(?:ek|1)\\s+hafte?\\s+(?:baad|mein|me|tak)"),
            plus7Tag
        )
        result = result.replace(
            boundaryRegex("(?:एक|1)\\s+हफ्ते?\\s+(?:बाद|में|तक)"),
            plus7Tag
        )

        // 3. "in 2 weeks", "after 2 weeks", "do hafte baad", "दो हफ्ते बाद"
        val plus14Tag = formatDateTag(today.plusDays(14), today)
        result = result.replace(
            boundaryRegex("(?:in|after)\\s+(?:2|two)\\s+weeks?"),
            plus14Tag
        )
        result = result.replace(
            boundaryRegex("(?:do|2)\\s+hafte?\\s+(?:baad|mein|me|tak)"),
            plus14Tag
        )
        result = result.replace(
            boundaryRegex("(?:दो|2)\\s+हफ्ते?\\s+(?:बाद|में|तक)"),
            plus14Tag
        )

        // 4. "in N days" / "after N days" (English)
        val regexInNDays = boundaryRegex("(?:in|after)\\s+(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\s+days?")
        result = regexInNDays.replace(result) { match ->
            val numStr = match.groupValues[1]
            val days = parseWordNumber(numStr) ?: 0
            if (days == 1) "@tomorrow"
            else if (days > 1) formatDateTag(today.plusDays(days.toLong()), today)
            else match.value
        }

        // 5. "N din baad" / "N din me" / "N din mein" (Hinglish)
        val regexNDinBaad = boundaryRegex("(\\d+|ek|do|teen|char|chaar|paanch|panch|chhe|che|saat|aath|nau|das)\\s+din(?:\\s+(?:baad|mein|me|tak))?")
        result = regexNDinBaad.replace(result) { match ->
            val numStr = match.groupValues[1]
            val days = parseWordNumber(numStr) ?: 0
            if (days == 1) "@tomorrow"
            else if (days > 1) formatDateTag(today.plusDays(days.toLong()), today)
            else match.value
        }

        // 6. "N दिन बाद" / "N दिन में" (Hindi Devanagari)
        val regexNDevDinBaad = boundaryRegex("(\\d+|एक|दो|तीन|चार|पांच|छह|सात|आठ|नौ|दस)\\s+दिन(?:\\s*(?:बाद|में|तक))?")
        result = regexNDevDinBaad.replace(result) { match ->
            val numStr = match.groupValues[1]
            val days = parseWordNumber(numStr) ?: 0
            if (days == 1) "@tomorrow"
            else if (days > 1) formatDateTag(today.plusDays(days.toLong()), today)
            else match.value
        }

        // 7. Days of week: "next Friday", "this Friday", "agle shukrawar", "अगले शुक्रवार"
        result = replaceDayOfWeekPhrases(result, today)

        // 8. Explicit dates: "30th of November", "30 November", "25th Dec", "25 Dec 2026", "November 30"
        result = replaceExplicitDates(result)

        // 9. Tomorrow: "tomorrow", "kal", "कल"
        result = result.replace(
            boundaryRegex("tomorrow"),
            "@tomorrow"
        )
        result = result.replace(
            boundaryRegex("kal(?:\\s+tak)?"),
            "@tomorrow"
        )
        result = result.replace(
            boundaryRegex("कल(?:\\s*तक)?"),
            "@tomorrow"
        )

        // 10. Yesterday: "yesterday"
        result = result.replace(
            boundaryRegex("yesterday"),
            "@yesterday"
        )

        // Normalize extra whitespace
        return result.replace(Regex("[ \\t]+"), " ")
    }

    private fun replaceDayOfWeekPhrases(text: String, today: LocalDate): String {
        // Matches phrases like:
        // "next Friday", "this Friday", "on Friday"
        // "agle shukrawar", "agle shukravaar", "agle friday"
        // "अगले शुक्रवार", "अगले फ्राइडे"
        val dowPrefix = "(?:next|this|coming|on|agle|aane\\s+wale|अगले|आने\\s+वाले)\\s+"
        val dowNames = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|" +
                "mon|tue|wed|thu|fri|sat|sun|" +
                "somwar|somvaar|mangalwar|mangalvaar|budhwar|budhvaar|guruwar|guruvaar|veervar|shukrawar|shukravaar|shaniwar|shanivaar|raviwar|ravivaar|itwar|itvaar|" +
                "सोमवार|मंगलवार|बुधवार|गुरुवार|वीरवार|शुक्रवार|शनिवार|रविवार|इतवार|" +
                "मंडे|ट्यूसडे|वेडनसडे|थर्सडे|फ्राइडे|सैटरडे|संडे"

        val regexWithPrefix = boundaryRegex("$dowPrefix($dowNames)")
        var res = regexWithPrefix.replace(text) { match ->
            val dayName = match.groupValues[1]
            val dow = parseDayOfWeek(dayName)
            if (dow != null) {
                val nextDate = nextDayOfWeek(today, dow)
                formatDateTag(nextDate, today)
            } else {
                match.value
            }
        }

        // Also check standalone "DAY ko" / "DAY को" (e.g. "shukrawar ko submit karna")
        val regexWithKo = boundaryRegex("($dowNames)(?=\\s+(?:ko|को))")
        res = regexWithKo.replace(res) { match ->
            val dayName = match.groupValues[1]
            val dow = parseDayOfWeek(dayName)
            if (dow != null) {
                val nextDate = nextDayOfWeek(today, dow)
                formatDateTag(nextDate, today)
            } else {
                match.value
            }
        }

        return res
    }

    private fun replaceExplicitDates(text: String): String {
        val monthNames = "January|February|March|April|May|June|July|August|September|October|November|December|" +
                "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec|" +
                "जनवरी|फरवरी|मार्च|अप्रैल|मई|जून|जुलाई|अगस्त|सितंबर|अक्टूबर|नवंबर|दिसंबर"

        // Pattern A: Day Month [Year] e.g. "30th of November", "30 November", "25 Dec 2026", "30 nov ko"
        val regexDayMonth = boundaryRegex(
            "(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+of)?\\s+($monthNames)(?:,?\\s+(\\d{4}))?"
        )
        var res = regexDayMonth.replace(text) { match ->
            val d = match.groupValues[1].toIntOrNull()
            val m = parseMonth(match.groupValues[2])
            val y = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (d != null && m != null) {
                val monthShort = getMonthShortName(m)
                if (y != null) "@$d $monthShort $y"
                else "@$d $monthShort"
            } else {
                match.value
            }
        }

        // Pattern B: Month Day [Year] e.g. "November 30", "November 30th", "Dec 25 2026"
        val regexMonthDay = boundaryRegex(
            "($monthNames)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?"
        )
        res = regexMonthDay.replace(res) { match ->
            val m = parseMonth(match.groupValues[1])
            val d = match.groupValues[2].toIntOrNull()
            val y = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (d != null && m != null) {
                val monthShort = getMonthShortName(m)
                if (y != null) "@$d $monthShort $y"
                else "@$d $monthShort"
            } else {
                match.value
            }
        }

        return res
    }

    private fun nextDayOfWeek(today: LocalDate, targetDayOfWeek: DayOfWeek): LocalDate {
        var d = today.plusDays(1)
        while (d.dayOfWeek != targetDayOfWeek) {
            d = d.plusDays(1)
        }
        return d
    }

    private fun formatDateTag(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val monthShort = getMonthShortName(date.monthValue)
        return if (date.year == today.year) {
            "@${date.dayOfMonth} $monthShort"
        } else {
            "@${date.dayOfMonth} $monthShort ${date.year}"
        }
    }

    private fun getMonthShortName(month: Int): String = when (month) {
        1 -> "Jan"
        2 -> "Feb"
        3 -> "Mar"
        4 -> "Apr"
        5 -> "May"
        6 -> "Jun"
        7 -> "Jul"
        8 -> "Aug"
        9 -> "Sep"
        10 -> "Oct"
        11 -> "Nov"
        12 -> "Dec"
        else -> "Jan"
    }

    private fun parseWordNumber(word: String): Int? {
        val clean = word.trim().lowercase()
        clean.toIntOrNull()?.let { return it }
        return when (clean) {
            "one", "a", "an", "ek", "एक" -> 1
            "two", "do", "दो" -> 2
            "three", "teen", "तीन" -> 3
            "four", "char", "chaar", "चार" -> 4
            "five", "paanch", "panch", "पांच" -> 5
            "six", "chhe", "che", "छह" -> 6
            "seven", "saat", "सात" -> 7
            "eight", "aath", "आठ" -> 8
            "nine", "nau", "नौ" -> 9
            "ten", "das", "दस" -> 10
            else -> null
        }
    }

    private fun parseDayOfWeek(word: String): DayOfWeek? {
        val clean = word.trim().lowercase()
        return when {
            clean in listOf("monday", "mon", "somwar", "somvaar", "सोमवार", "मंडे") -> DayOfWeek.MONDAY
            clean in listOf("tuesday", "tue", "mangalwar", "mangalvaar", "मंगलवार", "ट्यूसडे") -> DayOfWeek.TUESDAY
            clean in listOf("wednesday", "wed", "budhwar", "budhvaar", "बुधवार", "वेडनसडे") -> DayOfWeek.WEDNESDAY
            clean in listOf("thursday", "thu", "guruwar", "guruvaar", "veervar", "गुरुवार", "वीरवार", "थर्सडे") -> DayOfWeek.THURSDAY
            clean in listOf("friday", "fri", "shukrawar", "shukravaar", "शुक्रवार", "जुमा", "फ्राइडे") -> DayOfWeek.FRIDAY
            clean in listOf("saturday", "sat", "shaniwar", "shanivaar", "शनिवार", "सैटरडे") -> DayOfWeek.SATURDAY
            clean in listOf("sunday", "sun", "raviwar", "ravivaar", "itwar", "itvaar", "रविवार", "इतवार", "संडे") -> DayOfWeek.SUNDAY
            else -> null
        }
    }

    private fun parseMonth(word: String): Int? {
        val clean = word.trim().lowercase()
        return when {
            clean.startsWith("jan") || clean == "जनवरी" -> 1
            clean.startsWith("feb") || clean == "फरवरी" -> 2
            clean.startsWith("mar") || clean == "मार्च" -> 3
            clean.startsWith("apr") || clean == "अप्रैल" -> 4
            clean == "may" || clean == "मई" -> 5
            clean.startsWith("jun") || clean == "जून" -> 6
            clean.startsWith("jul") || clean == "जुलाई" -> 7
            clean.startsWith("aug") || clean == "अगस्त" -> 8
            clean.startsWith("sep") || clean == "सितंबर" -> 9
            clean.startsWith("oct") || clean == "अक्टूबर" -> 10
            clean.startsWith("nov") || clean == "नवंबर" -> 11
            clean.startsWith("dec") || clean == "दिसंबर" -> 12
            else -> null
        }
    }
}
