package com.typoposter.app.date

import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * Live Countdown Badge model representing a parsed inline date tag.
 */
data class CountdownBadge(
    val tag: String,
    val targetDate: LocalDate,
    val daysDiff: Long,
    val label: String,
    val compactLabel: String,
    val isOverdue: Boolean,
    val isToday: Boolean,
)

/**
 * 100% offline date parser for inline date tags like:
 * - @today, @tomorrow, @yesterday
 * - @30 Nov, @Nov 30, @25 Dec 2026, @2026-12-01
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

    private data class MatchCandidate(
        val range: IntRange,
        val tag: String,
        val targetDate: LocalDate,
    )

    fun parseAll(text: String, today: LocalDate = LocalDate.now()): List<CountdownBadge> {
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

        return nonOverlapping.map { createBadge(it.tag, it.targetDate, today) }
    }

    fun findNearest(text: String, today: LocalDate = LocalDate.now()): CountdownBadge? {
        return findNearest(parseAll(text, today))
    }

    fun findNearest(countdowns: List<CountdownBadge>): CountdownBadge? {
        if (countdowns.isEmpty()) return null
        val upcoming = countdowns.filter { it.daysDiff >= 0 }.minByOrNull { it.daysDiff }
        if (upcoming != null) return upcoming
        return countdowns.maxByOrNull { it.daysDiff }
    }

    private fun createBadge(tag: String, targetDate: LocalDate, today: LocalDate): CountdownBadge {
        val daysDiff = ChronoUnit.DAYS.between(today, targetDate)
        val isOverdue = daysDiff < 0
        val isToday = daysDiff == 0L

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

        return CountdownBadge(
            tag = tag,
            targetDate = targetDate,
            daysDiff = daysDiff,
            label = label,
            compactLabel = compactLabel,
            isOverdue = isOverdue,
            isToday = isToday
        )
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

        // No year specified: resolve relative to today
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
