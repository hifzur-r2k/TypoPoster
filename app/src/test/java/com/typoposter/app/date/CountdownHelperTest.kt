package com.typoposter.app.date

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

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

    @Test
    fun testSameDayTimeParsing12H() {
        val text = "Submit report @by 2pm and call client @at 11:30am, review @2:00 PM and sync @2:30 pm"
        val fixedTime = LocalTime.of(12, 6) // 12:06 PM

        val badges = CountdownHelper.parseAll(text, fixedToday, fixedTime)
        assertEquals(4, badges.size)

        // 1. @by 2pm (target 14:00, 114 mins away)
        val b1 = badges[0]
        assertEquals(fixedToday, b1.targetDate)
        assertEquals(LocalTime.of(14, 0), b1.targetTime)
        assertEquals(114L, b1.minutesDiff)
        assertEquals("in 1h 54m", b1.label)
        assertEquals("1h left", b1.compactLabel)
        assertFalse(b1.isOverdue)
        assertTrue(b1.isToday)
        assertEquals(UrgencyTier.IMMINENT, b1.urgencyTier)

        // 2. @at 11:30am (target 11:30, 36 mins in past)
        val b2 = badges[1]
        assertEquals(fixedToday, b2.targetDate)
        assertEquals(LocalTime.of(11, 30), b2.targetTime)
        assertEquals(-36L, b2.minutesDiff)
        assertEquals("11:30 AM • past", b2.label)
        assertEquals("Due 11:30 AM", b2.compactLabel)
        assertTrue(b2.isOverdue)
        assertTrue(b2.isToday)
        assertEquals(UrgencyTier.IMMINENT, b2.urgencyTier)

        // 3. @2:00 PM (target 14:00, 114 mins away)
        val b3 = badges[2]
        assertEquals(LocalTime.of(14, 0), b3.targetTime)
        assertEquals("in 1h 54m", b3.label)
        assertEquals("1h left", b3.compactLabel)
        assertEquals(UrgencyTier.IMMINENT, b3.urgencyTier)

        // 4. @2:30 pm (target 14:30, 144 mins away -> > 120 mins)
        val b4 = badges[3]
        assertEquals(LocalTime.of(14, 30), b4.targetTime)
        assertEquals(144L, b4.minutesDiff)
        assertEquals("in 2h 24m", b4.label)
        assertEquals("2h left", b4.compactLabel)
        assertEquals(UrgencyTier.APPROACHING, b4.urgencyTier)
    }

    @Test
    fun testSameDayTimeParsing24HAndBaje() {
        val text = "Lunch @14:00 and meeting @2 baje and dinner @shaam 5 baje and review @dopahar 2 baje"
        val fixedTime = LocalTime.of(12, 6) // 12:06 PM

        val badges = CountdownHelper.parseAll(text, fixedToday, fixedTime)
        assertEquals(4, badges.size)

        // @14:00
        assertEquals(LocalTime.of(14, 0), badges[0].targetTime)
        assertEquals("in 1h 54m", badges[0].label)
        assertEquals("1h left", badges[0].compactLabel)
        assertEquals(UrgencyTier.IMMINENT, badges[0].urgencyTier)

        // @2 baje -> 14:00
        assertEquals(LocalTime.of(14, 0), badges[1].targetTime)
        assertEquals("in 1h 54m", badges[1].label)
        assertEquals(UrgencyTier.IMMINENT, badges[1].urgencyTier)

        // @shaam 5 baje -> 17:00 (294 mins away -> > 120 mins)
        assertEquals(LocalTime.of(17, 0), badges[2].targetTime)
        assertEquals(294L, badges[2].minutesDiff)
        assertEquals("in 4h 54m", badges[2].label)
        assertEquals("4h left", badges[2].compactLabel)
        assertEquals(UrgencyTier.APPROACHING, badges[2].urgencyTier)

        // @dopahar 2 baje -> 14:00
        assertEquals(LocalTime.of(14, 0), badges[3].targetTime)
        assertEquals("in 1h 54m", badges[3].label)
        assertEquals(UrgencyTier.IMMINENT, badges[3].urgencyTier)
    }

    @Test
    fun testUrgencyTierProgression() {
        val fixedTime = LocalTime.of(12, 0) // 12:00 PM

        // 1. IMMINENT:
        // - Same-day time within 2 hours (@1:30 PM = 90 mins away)
        val imminentBadge = CountdownHelper.parseAll("@1:30 PM", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.IMMINENT, imminentBadge.urgencyTier)

        // - Same-day time overdue (@11:00 AM = -60 mins)
        val overdueBadge = CountdownHelper.parseAll("@11:00 AM", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.IMMINENT, overdueBadge.urgencyTier)

        // - Date due today (@today)
        val todayBadge = CountdownHelper.parseAll("@today", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.IMMINENT, todayBadge.urgencyTier)

        // 2. APPROACHING:
        // - Tomorrow (@tomorrow)
        val tomorrowBadge = CountdownHelper.parseAll("@tomorrow", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.APPROACHING, tomorrowBadge.urgencyTier)

        // - Same-day time > 2 hours away (@5:00 PM = 5 hours away)
        val approachingTimeBadge = CountdownHelper.parseAll("@5:00 PM", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.APPROACHING, approachingTimeBadge.urgencyTier)

        // 3. CALM:
        // - Future date > 1 day away (@30 Nov)
        val calmBadge = CountdownHelper.parseAll("@30 Nov", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.CALM, calmBadge.urgencyTier)

        // - Historical multi-day overdue (@yesterday)
        val yesterdayBadge = CountdownHelper.parseAll("@yesterday", fixedToday, fixedTime).first()
        assertEquals(UrgencyTier.CALM, yesterdayBadge.urgencyTier)
    }

    @Test
    fun testNearestCountdownWithSameDayTimes() {
        val text = "Sprint demo @tomorrow and sync @2:00 PM and team check @5:00 PM"
        val fixedTime = LocalTime.of(12, 6) // 12:06 PM

        val nearest = CountdownHelper.findNearest(text, fixedToday, fixedTime)
        assertNotNull(nearest)
        // 2:00 PM is today in 1h 54m, which is nearer than 5:00 PM or tomorrow!
        assertEquals("@2:00 PM", nearest?.tag)
        assertEquals("in 1h 54m", nearest?.label)
        assertEquals(UrgencyTier.IMMINENT, nearest?.urgencyTier)
    }

    @Test
    fun testDottedMeridiemAndBareTimeBadges() {
        val fixedTimeAt239Pm = LocalTime.of(14, 39) // 2:39 PM

        // 1. Dotted meridiem @5 p.m.
        val badges1 = CountdownHelper.parseAll("Meeting @5 p.m.", fixedToday, fixedTimeAt239Pm)
        assertEquals(1, badges1.size)
        assertEquals(LocalTime.of(17, 0), badges1[0].targetTime)
        assertEquals(141L, badges1[0].minutesDiff)
        assertEquals("in 2h 21m", badges1[0].label)

        // 2. Dotted meridiem @5:30 p.m.
        val badges2 = CountdownHelper.parseAll("Call @5:30 p.m.", fixedToday, fixedTimeAt239Pm)
        assertEquals(1, badges2.size)
        assertEquals(LocalTime.of(17, 30), badges2[0].targetTime)
        assertEquals(171L, badges2[0].minutesDiff)
        assertEquals("in 2h 51m", badges2[0].label)

        // 3. Dotted meridiem @at 9 a.m. (past at 2:39 PM)
        val badges3 = CountdownHelper.parseAll("Sync @at 9 a.m.", fixedToday, fixedTimeAt239Pm)
        assertEquals(1, badges3.size)
        assertEquals(LocalTime.of(9, 0), badges3[0].targetTime)
        assertTrue(badges3[0].isOverdue)
        assertEquals("9:00 AM • past", badges3[0].label)

        // 4. Bare @by 5 at 2:39 PM -> Smart inference picks 17:00 (5:00 PM)
        val badges4 = CountdownHelper.parseAll("Finish @by 5", fixedToday, fixedTimeAt239Pm)
        assertEquals(1, badges4.size)
        assertEquals(LocalTime.of(17, 0), badges4[0].targetTime)
        assertEquals(141L, badges4[0].minutesDiff)
        assertEquals("in 2h 21m", badges4[0].label)
    }
}
