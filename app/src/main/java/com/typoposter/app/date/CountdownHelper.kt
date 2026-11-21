package com.typoposter.app.date

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * Calm urgency levels for progressive, non-aggressive deadline prominence.
 */
enum class UrgencyTier {
    CALM,        // > 1 day away, or historical multi-day overdue
    APPROACHING, // Tomorrow (1 day away), or same-day time > 2 hours away
    IMMINENT     // Today due within 2 hours, due now, or overdue today / date due today
}

/**
 * Live Countdown Badge model representing a parsed inline date or time tag.
 */
data class CountdownBadge(
    val tag: String,
    val targetDate: LocalDate,
    val daysDiff: Long,
    val label: String,
    val compactLabel: String,
    val isOverdue: Boolean,
    val isToday: Boolean,
    val targetTime: LocalTime? = null,
    val targetDateTime: LocalDateTime = if (targetTime != null) {
        LocalDateTime.of(targetDate, targetTime)
    } else {
        targetDate.atTime(23, 59, 59)
    },
    val minutesDiff: Long? = null,
    val urgencyTier: UrgencyTier = UrgencyTier.CALM
)

/**
 * 100% offline date and time parser for inline tags like:
 * - @today, @tomorrow, @yesterday
 * - @30 Nov, @Nov 30, @25 Dec 2026, @2026-12-01
 * - Same-day times: @2pm, @2:30 pm, @at 5pm, @14:00, @2 baje, @dopahar 2 baje
 *
 * Year resolution rule:
 * When no year is typed, use current year; if that date is more than 30 days
 * in the past, roll over to next year (recent overdue dates within 30 days still show "X days ago").
 */
object CountdownHelper {

    private const val MONTHS_REGEX_STR =
        "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t|tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?"

    // Boundary guards: preceded by start of string, whitespace, or opening punctuation;
    // followed by end of string, whitespace, or closing punctuation.
    private const val PREFIX_GUARD = "(?<=^|[\\s(\\[\"'])@"
    private const val SUFFIX_GUARD = "(?=$|[\\s,.;:!?\\)\\]\"'])"

    // 1. Relative: @today, @tomorrow, @yesterday
    private val REGEX_RELATIVE = Regex(
        "$PREFIX_GUARD(today|tomorrow|yesterday)$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 2. ISO Date: @YYYY-MM-DD or @YYYY/MM/DD
    private val REGEX_ISO = Regex(
        "$PREFIX_GUARD(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 3. Month Day [Year]: @Nov 30, @November 30, @Nov 30, 2026, @Nov 30th 2026
    private val REGEX_MONTH_DAY = Regex(
        "$PREFIX_GUARD($MONTHS_REGEX_STR)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:[-,\\s]\\s*(\\d{4}))?$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 4. Day Month [Year]: @30 Nov, @30 November, @30th Nov, @25 Dec 2026
    private val REGEX_DAY_MONTH = Regex(
        "$PREFIX_GUARD(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTHS_REGEX_STR)(?:[-,\\s]\\s*(\\d{4}))?$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 5. Same-day 12-hour AM/PM: @2pm, @2 PM, @2:30pm, @2:30 pm, @2:00 PM, @11:30am, @by 2pm, @at 5pm, @5 p.m., @5:00 p.m., @at 9 a.m.
    private val REGEX_TIME_AMPM = Regex(
        "$PREFIX_GUARD(?:(?:by|at)\\s+)?(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?\\s*m\\.?|p\\.?\\s*m\\.?)$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 6. Same-day 24-hour: @14:00, @09:30, @23:45 (not followed by am/pm)
    private val REGEX_TIME_24H = Regex(
        "$PREFIX_GUARD(?:(?:by|at)\\s+)?([01]?\\d|2[0-3]):([0-5]\\d)(?!\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?))$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 7. Hindi/Hinglish "baje": @2 baje, @2:30 baje, @dopahar 2 baje, @shaam 5 baje, @2 बजे, @दोपहर 2 बजे
    private const val HINDI_PERIOD_STR = "dopahar|shaam|raat|subah|दोपहर|शाम|रात|सुबह"
    private const val HINDI_NUMS_STR = "ek|do|teen|char|chaar|paanch|panch|chhe|che|saat|aath|nau|das|gyarah|barah|एक|दो|तीन|चार|पांच|छह|सात|आठ|नौ|दस|ग्यारह|बारह"
    private val REGEX_TIME_BAJE = Regex(
        "$PREFIX_GUARD(?:($HINDI_PERIOD_STR)\\s+)?(\\d{1,2}|$HINDI_NUMS_STR)(?::(\\d{2}))?\\s*(?:baje|बजे)(?:\\s+(?:tak|तक))?$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    // 8. Bare 12-hour time: @by 5, @at 5, @by 5:30, @at 6:30
    private val REGEX_TIME_BARE = Regex(
        "$PREFIX_GUARD(?:by|at)\\s+(\\d{1,2})(?::(\\d{2}))?(?!\\s*(?:a\\.?\\s*m\\.?|p\\.?\\s*m\\.?|\\d))$SUFFIX_GUARD",
        RegexOption.IGNORE_CASE
    )

    private data class MatchCandidate(
        val range: IntRange,
        val tag: String,
        val targetDate: LocalDate,
        val targetTime: LocalTime? = null
    )

    fun parseAll(
        text: String,
        today: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now()
    ): List<CountdownBadge> {
        if (text.isBlank()) return emptyList()

        val candidates = mutableListOf<MatchCandidate>()

        // 1. Relative tags
        REGEX_RELATIVE.findAll(text).forEach { m ->
            val relWord = m.groupValues[1].lowercase()
            val target = when (relWord) {
                "today" -> today
                "tomorrow" -> today.plusDays(1)
                "yesterday" -> today.minusDays(1)
                else -> null
            }
            if (target != null) {
                candidates.add(MatchCandidate(m.range, m.value, target))
            }
        }

        // 2. ISO tags
        REGEX_ISO.findAll(text).forEach { m ->
            val y = m.groupValues[1].toIntOrNull()
            val month = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && month != null && d != null) {
                val target = resolveDate(y, month, d, today)
                if (target != null) {
                    candidates.add(MatchCandidate(m.range, m.value, target))
                }
            }
        }

        // 3. Month Day [Year]
        REGEX_MONTH_DAY.findAll(text).forEach { m ->
            val month = parseMonth(m.groupValues[1])
            val d = m.groupValues[2].toIntOrNull()
            val y = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (month != null && d != null) {
                val target = resolveDate(y, month, d, today)
                if (target != null) {
                    candidates.add(MatchCandidate(m.range, m.value, target))
                }
            }
        }

        // 4. Day Month [Year]
        REGEX_DAY_MONTH.findAll(text).forEach { m ->
            val d = m.groupValues[1].toIntOrNull()
            val month = parseMonth(m.groupValues[2])
            val y = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull()
            if (month != null && d != null) {
                val target = resolveDate(y, month, d, today)
                if (target != null) {
                    candidates.add(MatchCandidate(m.range, m.value, target))
                }
            }
        }

        // 5. Same-day 12-hour AM/PM
        REGEX_TIME_AMPM.findAll(text).forEach { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val rawAmPm = m.groupValues[3].lowercase()
            val isPm = rawAmPm.contains("p")
            if (h != null && h in 1..12 && min in 0..59) {
                val h24 = when {
                    isPm && h < 12 -> h + 12
                    !isPm && h == 12 -> 0
                    else -> h
                }
                candidates.add(MatchCandidate(m.range, m.value, today, LocalTime.of(h24, min)))
            }
        }

        // 6. Same-day 24-hour / colon time
        REGEX_TIME_24H.findAll(text).forEach { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues[2].toIntOrNull()
            if (h != null && min != null && h in 0..23 && min in 0..59) {
                val h24 = if (h in 1..11) {
                    val amTime = LocalTime.of(h, min)
                    val pmTime = LocalTime.of(h + 12, min)
                    if (nowTime.isAfter(amTime) && !nowTime.isAfter(pmTime)) h + 12 else h
                } else {
                    h
                }
                candidates.add(MatchCandidate(m.range, m.value, today, LocalTime.of(h24, min)))
            }
        }

        // 7. Hindi/Hinglish "baje"
        REGEX_TIME_BAJE.findAll(text).forEach { m ->
            val period = m.groupValues[1].lowercase()
            val numStr = m.groupValues[2]
            val min = m.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            val rawH = parseWordNumberOrDigit(numStr)
            if (rawH != null && rawH in 1..24 && min in 0..59) {
                val (h12, amPm) = resolveHindiPeriod(rawH, period)
                val h24 = when {
                    amPm == "PM" && h12 < 12 -> h12 + 12
                    amPm == "AM" && h12 == 12 -> 0
                    else -> h12
                }
                candidates.add(MatchCandidate(m.range, m.value, today, LocalTime.of(h24, min)))
            }
        }

        // 8. Bare 12-hour time: @by 5, @at 5, @by 5:30, @at 6:30
        REGEX_TIME_BARE.findAll(text).forEach { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
            if (h != null && h in 1..12 && min in 0..59) {
                val amPm = if (h == 12) {
                    "PM"
                } else {
                    val amTime = LocalTime.of(h, min)
                    val pmTime = LocalTime.of(h + 12, min)
                    if (nowTime.isAfter(amTime) && !nowTime.isAfter(pmTime)) "PM" else "AM"
                }
                val h24 = when {
                    amPm == "PM" && h < 12 -> h + 12
                    amPm == "AM" && h == 12 -> 0
                    else -> h
                }
                candidates.add(MatchCandidate(m.range, m.value, today, LocalTime.of(h24, min)))
            }
        }

        if (candidates.isEmpty()) return emptyList()

        // Sort by start index ascending, longer match first if same start
        candidates.sortWith(
            compareBy<MatchCandidate> { it.range.first }
                .thenByDescending { it.range.last - it.range.first }
        )

        val nonOverlapping = mutableListOf<MatchCandidate>()
        var lastEnd = -1

        for (cand in candidates) {
            if (cand.range.first > lastEnd) {
                nonOverlapping.add(cand)
                lastEnd = cand.range.last
            }
        }

        return nonOverlapping.map { createBadge(it.tag, it.targetDate, today, it.targetTime, nowTime) }
    }

    fun findNearest(
        text: String,
        today: LocalDate = LocalDate.now(),
        nowTime: LocalTime = LocalTime.now()
    ): CountdownBadge? {
        return findNearest(parseAll(text, today, nowTime))
    }

    fun findNearest(countdowns: List<CountdownBadge>): CountdownBadge? {
        if (countdowns.isEmpty()) return null
        // 1. Upcoming deadlines (daysDiff > 0, or isToday and minutesDiff >= 0, or isToday and targetTime == null)
        val upcoming = countdowns.filter { badge ->
            if (badge.daysDiff > 0) true
            else if (badge.isToday) {
                badge.minutesDiff == null || badge.minutesDiff >= 0
            } else false
        }
        if (upcoming.isNotEmpty()) {
            return upcoming.minByOrNull { it.targetDateTime }
        }
        // 2. If all past/overdue, return the most recent one (max targetDateTime)
        return countdowns.maxByOrNull { it.targetDateTime }
    }

    private fun createBadge(
        tag: String,
        targetDate: LocalDate,
        today: LocalDate,
        targetTime: LocalTime? = null,
        nowTime: LocalTime = LocalTime.now()
    ): CountdownBadge {
        val daysDiff = ChronoUnit.DAYS.between(today, targetDate)
        val isToday = daysDiff == 0L

        if (targetTime != null && isToday) {
            val minutesDiff = ChronoUnit.MINUTES.between(nowTime, targetTime)
            val isOverdue = minutesDiff < 0

            val label = when {
                minutesDiff > 0 -> {
                    val h = minutesDiff / 60
                    val m = minutesDiff % 60
                    if (h > 0) {
                        if (m > 0) "in ${h}h ${m}m" else "in ${h}h"
                    } else {
                        "in ${m}m"
                    }
                }
                minutesDiff == 0L -> "Due now"
                else -> {
                    val timeStr = formatDisplayTime(targetTime)
                    "$timeStr • past"
                }
            }

            val compactLabel = when {
                minutesDiff > 0 -> {
                    val h = minutesDiff / 60
                    val m = minutesDiff % 60
                    if (h > 0) {
                        "${h}h left"
                    } else {
                        "${m}m left"
                    }
                }
                minutesDiff == 0L -> "Now"
                else -> {
                    val timeStr = formatDisplayTime(targetTime)
                    "Due $timeStr"
                }
            }

            val urgencyTier = when {
                isOverdue -> UrgencyTier.IMMINENT
                minutesDiff <= 120 -> UrgencyTier.IMMINENT
                else -> UrgencyTier.APPROACHING
            }

            return CountdownBadge(
                tag = tag,
                targetDate = targetDate,
                daysDiff = daysDiff,
                label = label,
                compactLabel = compactLabel,
                isOverdue = isOverdue,
                isToday = isToday,
                targetTime = targetTime,
                targetDateTime = LocalDateTime.of(targetDate, targetTime),
                minutesDiff = minutesDiff,
                urgencyTier = urgencyTier
            )
        } else {
            val isOverdue = daysDiff < 0
            val label = when {
                daysDiff == 0L -> "Today"
                daysDiff == 1L -> "Tomorrow"
                daysDiff > 1L -> "$daysDiff days left"
                daysDiff == -1L -> "1 day ago"
                else -> "${-daysDiff} days ago"
            }

            val compactLabel = when {
                daysDiff == 0L -> "Today"
                daysDiff == 1L -> "Tomorrow"
                daysDiff > 1L -> "${daysDiff}d left"
                daysDiff == -1L -> "1d ago"
                else -> "${-daysDiff}d ago"
            }

            val urgencyTier = when {
                daysDiff == 0L -> UrgencyTier.IMMINENT
                daysDiff == 1L -> UrgencyTier.APPROACHING
                else -> UrgencyTier.CALM
            }

            return CountdownBadge(
                tag = tag,
                targetDate = targetDate,
                daysDiff = daysDiff,
                label = label,
                compactLabel = compactLabel,
                isOverdue = isOverdue,
                isToday = isToday,
                targetTime = null,
                targetDateTime = targetDate.atTime(23, 59, 59),
                minutesDiff = null,
                urgencyTier = urgencyTier
            )
        }
    }

    fun formatDisplayTime(time: LocalTime): String {
        val h = time.hour
        val m = time.minute
        val amPm = if (h >= 12) "PM" else "AM"
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val minStr = if (m < 10) "0$m" else "$m"
        return "$h12:$minStr $amPm"
    }

    fun parseWordNumberOrDigit(word: String): Int? {
        val clean = word.trim().lowercase()
        clean.toIntOrNull()?.let { return it }
        return when (clean) {
            "one", "ek", "एक" -> 1
            "two", "do", "दो" -> 2
            "three", "teen", "तीन" -> 3
            "four", "char", "chaar", "चार" -> 4
            "five", "paanch", "panch", "पांच" -> 5
            "six", "chhe", "che", "छह" -> 6
            "seven", "saat", "सात" -> 7
            "eight", "aath", "आठ" -> 8
            "nine", "nau", "नौ" -> 9
            "ten", "das", "दस" -> 10
            "eleven", "gyarah", "ग्यारह" -> 11
            "twelve", "barah", "बारह" -> 12
            else -> null
        }
    }

    fun resolveHindiPeriod(rawH: Int, period: String): Pair<Int, String> {
        val p = period.trim().lowercase()
        return when {
            p in listOf("dopahar", "दोपहर") -> {
                Pair(rawH, "PM")
            }
            p in listOf("shaam", "शाम") -> {
                Pair(rawH, "PM")
            }
            p in listOf("raat", "रात") -> {
                if (rawH == 12 || rawH in 1..4) Pair(rawH, "AM") else Pair(rawH, "PM")
            }
            p in listOf("subah", "सुबह") -> {
                Pair(rawH, "AM")
            }
            else -> {
                if (rawH in 1..6 || rawH == 12) Pair(rawH, "PM") else Pair(rawH, "AM")
            }
        }
    }

    private fun parseMonth(name: String): Int? {
        val clean = name.trim().lowercase()
        return when {
            clean.startsWith("jan") -> 1
            clean.startsWith("feb") -> 2
            clean.startsWith("mar") -> 3
            clean.startsWith("apr") -> 4
            clean == "may" -> 5
            clean.startsWith("jun") -> 6
            clean.startsWith("jul") -> 7
            clean.startsWith("aug") -> 8
            clean.startsWith("sep") -> 9
            clean.startsWith("oct") -> 10
            clean.startsWith("nov") -> 11
            clean.startsWith("dec") -> 12
            else -> null
        }
    }

    private fun resolveDate(
        year: Int?,
        month: Int,
        day: Int,
        today: LocalDate
    ): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null

        if (year != null) {
            return try {
                LocalDate.of(year, month, day)
            } catch (e: Exception) {
                null
            }
        }

        if (month == 2 && day == 29) {
            val candYear = if (Year.isLeap(today.year.toLong())) today.year else findNextLeapYear(today.year)
            val candDate = LocalDate.of(candYear, 2, 29)
            val diff = ChronoUnit.DAYS.between(today, candDate)
            return if (diff < -30) {
                val nextLeap = findNextLeapYear(candYear + 1)
                LocalDate.of(nextLeap, 2, 29)
            } else {
                candDate
            }
        }

        val candDate = try {
            LocalDate.of(today.year, month, day)
        } catch (e: Exception) {
            return null
        }

        val diff = ChronoUnit.DAYS.between(today, candDate)
        return if (diff < -30) {
            try {
                LocalDate.of(today.year + 1, month, day)
            } catch (e: Exception) {
                null
            }
        } else {
            candDate
        }
    }

    private fun findNextLeapYear(startYear: Int): Int {
        var y = startYear
        while (!Year.isLeap(y.toLong())) {
            y++
        }
        return y
    }
}
