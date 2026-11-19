package com.typoposter.app.date

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CountdownHelperTest {

    private val fixedToday = LocalDate.of(2026, 11, 19)

    @Test
    fun testRelativeDates() {
        val text = "Ship update @today, review @tomorrow, and retrospected @yesterday."
        val badges = CountdownHelper.parseAll(text, fixedToday)

        assertEquals(3, badges.size)

        // @today
        assertEquals("@today", badges[0].tag)
        assertEquals(fixedToday, badges[0].targetDate)
        assertEquals(0L, badges[0].daysDiff)
        assertEquals("Today", badges[0].label)
        assertEquals("Today", badges[0].compactLabel)
        assertTrue(badges[0].isToday)
        assertFalse(badges[0].isOverdue)

        // @tomorrow
        assertEquals("@tomorrow", badges[1].tag)
        assertEquals(fixedToday.plusDays(1), badges[1].targetDate)
        assertEquals(1L, badges[1].daysDiff)
        assertEquals("Tomorrow", badges[1].label)
        assertEquals("Tomorrow", badges[1].compactLabel)
        assertFalse(badges[1].isToday)
        assertFalse(badges[1].isOverdue)

        // @yesterday
        assertEquals("@yesterday", badges[2].tag)
        assertEquals(fixedToday.minusDays(1), badges[2].targetDate)
        assertEquals(-1L, badges[2].daysDiff)
        assertEquals("1 day ago", badges[2].label)
        assertEquals("1d ago", badges[2].compactLabel)
        assertFalse(badges[2].isToday)
        assertTrue(badges[2].isOverdue)
    }

    @Test
    fun testDayMonthAndMonthDay() {
        val text = "Event A on @30 Nov, Event B on @Nov 30, and Party on @25 Dec 2026."
        val badges = CountdownHelper.parseAll(text, fixedToday)

        assertEquals(3, badges.size)

        // @30 Nov
        assertEquals(LocalDate.of(2026, 11, 30), badges[0].targetDate)
        assertEquals(11L, badges[0].daysDiff)
        assertEquals("11 days left", badges[0].label)
        assertEquals("11d left", badges[0].compactLabel)

        // @Nov 30
        assertEquals(LocalDate.of(2026, 11, 30), badges[1].targetDate)
        assertEquals(11L, badges[1].daysDiff)

        // @25 Dec 2026
        assertEquals(LocalDate.of(2026, 12, 25), badges[2].targetDate)
        assertEquals(36L, badges[2].daysDiff)
        assertEquals("36 days left", badges[2].label)
        assertEquals("36d left", badges[2].compactLabel)
    }

    @Test
    fun testIsoDates() {
        val text = "Release on @2026-12-01 and audit on @2026/12/15."
        val badges = CountdownHelper.parseAll(text, fixedToday)

        assertEquals(2, badges.size)
        assertEquals(LocalDate.of(2026, 12, 1), badges[0].targetDate)
        assertEquals(12L, badges[0].daysDiff)
        assertEquals("12 days left", badges[0].label)

        assertEquals(LocalDate.of(2026, 12, 15), badges[1].targetDate)
        assertEquals(26L, badges[1].daysDiff)
        assertEquals("26 days left", badges[1].label)
    }

    @Test
    fun testRecentOverdueWithin30Days() {
        // fixedToday is 2026-11-19
        // @15 Nov is 4 days ago (<= 30 days in past), should stay in 2026
        val text = "Bug reported @15 Nov and patch shipped @17 Nov."
        val badges = CountdownHelper.parseAll(text, fixedToday)

        assertEquals(2, badges.size)

        assertEquals(LocalDate.of(2026, 11, 15), badges[0].targetDate)
        assertEquals(-4L, badges[0].daysDiff)
        assertEquals("4 days ago", badges[0].label)
        assertEquals("4d ago", badges[0].compactLabel)
        assertTrue(badges[0].isOverdue)

        assertEquals(LocalDate.of(2026, 11, 17), badges[1].targetDate)
        assertEquals(-2L, badges[1].daysDiff)
        assertEquals("2 days ago", badges[1].label)
        assertEquals("2d ago", badges[1].compactLabel)
        assertTrue(badges[1].isOverdue)
    }

    @Test
    fun testYearRolloverWhenOver30DaysInPast() {
        // Today is mid-December 2026
        val decemberToday = LocalDate.of(2026, 12, 15)
        // @15 Jan in 2026 is 334 days in the past (> 30 days).
        // It must roll over to 2027-01-15 (31 days left).
        val text = "Renewal @15 Jan."
        val badges = CountdownHelper.parseAll(text, decemberToday)

        assertEquals(1, badges.size)
        assertEquals(LocalDate.of(2027, 1, 15), badges[0].targetDate)
        assertEquals(31L, badges[0].daysDiff)
        assertEquals("31 days left", badges[0].label)
        assertEquals("31d left", badges[0].compactLabel)
        assertFalse(badges[0].isOverdue)
    }

    @Test
    fun testLeapYears() {
        // Explicit leap year date
        val textExplicit = "Check leap day @29 Feb 2028."
        val badges1 = CountdownHelper.parseAll(textExplicit, fixedToday)
        assertEquals(1, badges1.size)
        assertEquals(LocalDate.of(2028, 2, 29), badges1[0].targetDate)

        // Inferred leap year date from 2026 (non-leap)
        val textImplicit = "Check leap day @29 Feb."
        val badges2 = CountdownHelper.parseAll(textImplicit, fixedToday)
        assertEquals(1, badges2.size)
        // Next leap year is 2028
        assertEquals(LocalDate.of(2028, 2, 29), badges2[0].targetDate)
    }

    @Test
    fun testNearestCountdownSelection() {
        // Multiple tags with upcoming and past
        val text = "Old task @15 Nov, big sprint @25 Dec 2026, demo @tomorrow, sync @today."
        val nearest = CountdownHelper.findNearest(text, fixedToday)

        assertNotNull(nearest)
        // Nearest upcoming should be @today (daysDiff == 0)
        assertEquals("Today", nearest?.label)

        // If today is removed, nearest upcoming is @tomorrow
        val textWithoutToday = "Old task @15 Nov, big sprint @25 Dec 2026, demo @tomorrow."
        val nearestTomorrow = CountdownHelper.findNearest(textWithoutToday, fixedToday)
        assertEquals("Tomorrow", nearestTomorrow?.label)

        // If all are overdue, returns the most recent overdue (closest to today)
        val overdueText = "Missed @10 Nov and missed @17 Nov."
        val nearestOverdue = CountdownHelper.findNearest(overdueText, fixedToday)
        assertEquals("2 days ago", nearestOverdue?.label)
    }

    @Test
    fun testEmailAndFalsePositiveIgnored() {
        val text = "Contact support@example.com or john@company.org regarding project."
        val badges = CountdownHelper.parseAll(text, fixedToday)
        assertTrue(badges.isEmpty())

        val textWithUser = "Hey @john, can you review this?"
        val badgesUser = CountdownHelper.parseAll(textWithUser, fixedToday)
        assertTrue(badgesUser.isEmpty())
    }
}
