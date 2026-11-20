package com.typoposter.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SpokenDateParserTest {

    // Fixed Thursday, 19 Nov 2026
    private val fixedToday = LocalDate.of(2026, 11, 19)

    @Test
    fun testTomorrowVariations() {
        // English
        assertEquals(
            "finish report @tomorrow morning",
            SpokenDateParser.convert("finish report tomorrow morning", fixedToday)
        )

        // Hinglish
        assertEquals(
            "ye kaam @tomorrow submit karna hai",
            SpokenDateParser.convert("ye kaam kal submit karna hai", fixedToday)
        )
        assertEquals(
            "report @tomorrow bhejo",
            SpokenDateParser.convert("report kal tak bhejo", fixedToday)
        )

        // Hindi Devanagari
        assertEquals(
            "@tomorrow सुबह मीटिंग है",
            SpokenDateParser.convert("कल सुबह मीटिंग है", fixedToday)
        )
        assertEquals(
            "@tomorrow काम खत्म करना है",
            SpokenDateParser.convert("कल तक काम खत्म करना है", fixedToday)
        )
    }

    @Test
    fun testDayAfterTomorrowVariations() {
        // fixedToday is 19 Nov 2026 -> +2 days is 21 Nov 2026
        assertEquals(
            "client review @21 Nov",
            SpokenDateParser.convert("client review day after tomorrow", fixedToday)
        )
        assertEquals(
            "deliver package @21 Nov",
            SpokenDateParser.convert("deliver package the day after tomorrow", fixedToday)
        )
        assertEquals(
            "@21 Nov meeting rakhte hain",
            SpokenDateParser.convert("parso meeting rakhte hain", fixedToday)
        )
        assertEquals(
            "@21 Nov प्रोजेक्ट फाइनल होगा",
            SpokenDateParser.convert("परसों प्रोजेक्ट फाइनल होगा", fixedToday)
        )
    }

    @Test
    fun testInNDaysVariations() {
        // in 3 days -> 19 + 3 = 22 Nov
        assertEquals(
            "finish audit @22 Nov please",
            SpokenDateParser.convert("finish audit in 3 days please", fixedToday)
        )
        assertEquals(
            "audit @22 Nov",
            SpokenDateParser.convert("audit in three days", fixedToday)
        )
        // in 1 day -> @tomorrow
        assertEquals(
            "launch @tomorrow",
            SpokenDateParser.convert("launch in 1 day", fixedToday)
        )

        // Hinglish "3 din baad"
        assertEquals(
            "ye kaam @22 Nov check karna",
            SpokenDateParser.convert("ye kaam 3 din baad check karna", fixedToday)
        )
        assertEquals(
            "update @22 Nov aayega",
            SpokenDateParser.convert("update teen din baad aayega", fixedToday)
        )

        // Hindi Devanagari
        assertEquals(
            "@22 Nov सबमिट करो",
            SpokenDateParser.convert("3 दिन बाद सबमिट करो", fixedToday)
        )
    }

    @Test
    fun testWeekVariations() {
        // in a week -> 19 + 7 = 26 Nov
        assertEquals(
            "release version @26 Nov",
            SpokenDateParser.convert("release version in a week", fixedToday)
        )
        assertEquals(
            "launch @26 Nov hoga",
            SpokenDateParser.convert("launch ek hafte baad hoga", fixedToday)
        )
        assertEquals(
            "अपडेट @26 Nov मिलेगा",
            SpokenDateParser.convert("अपडेट 1 हफ्ते बाद मिलेगा", fixedToday)
        )
    }

    @Test
    fun testDayOfWeekVariations() {
        // fixedToday is Thursday 19 Nov 2026.
        // next Friday is 20 Nov 2026
        assertEquals(
            "meet the team @20 Nov",
            SpokenDateParser.convert("meet the team next Friday", fixedToday)
        )
        // Hinglish "agle shukrawar" -> 20 Nov
        assertEquals(
            "review @20 Nov ko hoga",
            SpokenDateParser.convert("review agle shukrawar ko hoga", fixedToday)
        )
        // Devanagari "अगले शुक्रवार" -> 20 Nov
        assertEquals(
            "@20 Nov को मिलेंगे",
            SpokenDateParser.convert("अगले शुक्रवार को मिलेंगे", fixedToday)
        )

        // next Monday -> 23 Nov 2026
        assertEquals(
            "start sprint @23 Nov",
            SpokenDateParser.convert("start sprint next Monday", fixedToday)
        )
    }

    @Test
    fun testExplicitDates() {
        assertEquals(
            "deadline is @30 Nov",
            SpokenDateParser.convert("deadline is 30 November", fixedToday)
        )
        assertEquals(
            "event on @25 Dec",
            SpokenDateParser.convert("event on 25 Dec", fixedToday)
        )
        assertEquals(
            "celebration on @25 Dec 2026",
            SpokenDateParser.convert("celebration on 25 Dec 2026", fixedToday)
        )
        assertEquals(
            "meet on @30 Nov",
            SpokenDateParser.convert("meet on November 30", fixedToday)
        )
        assertEquals(
            "meet on @30 Nov",
            SpokenDateParser.convert("meet on 30th of November", fixedToday)
        )
    }

    @Test
    fun testNoCorruptingNormalWords() {
        // Words containing "kal" like "Kolkata" or "kalakar" must NOT be converted
        val text = "Trip to Kolkata with kalakar friends"
        assertEquals(text, SpokenDateParser.convert(text, fixedToday))
    }
}
