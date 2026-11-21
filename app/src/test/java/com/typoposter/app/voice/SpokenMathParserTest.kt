package com.typoposter.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenMathParserTest {

    @Test
    fun testSpokenMathIdempotency() {
        // First run on multiplication with trailing '='
        val multExpr = "20 * 39 ="
        val firstMult = SpokenMathParser.convert(multExpr)
        assertEquals("20 * 39 = 780", firstMult)
        // Second run must be completely idempotent (never double: "20 * 39 = 780 780")
        val secondMult = SpokenMathParser.convert(firstMult)
        assertEquals("20 * 39 = 780", secondMult)

        // First run on division with trailing '='
        val divExpr = "25 / 36 ="
        val firstDiv = SpokenMathParser.convert(divExpr)
        assertEquals("25 / 36 = 0.694444", firstDiv)
        // Second run must be completely idempotent (never double: "25 / 36 = 0.694444 0.694444")
        val secondDiv = SpokenMathParser.convert(firstDiv)
        assertEquals("25 / 36 = 0.694444", secondDiv)
    }

    @Test
    fun testSpokenMathChainedMultiOperatorExpression() {
        // "100 + 100 + 100 + 100 times 4 - 36 - 54 - 100 equals"
        // PEMDAS order: 100 * 4 = 400.
        // Left to right: 100 + 100 + 100 + 400 - 36 - 54 - 100 = 510
        val input = "100 + 100 + 100 + 100 times 4 - 36 - 54 - 100 equals"
        val expected = "100 + 100 + 100 + 100 * 4 - 36 - 54 - 100 = 510"
        val actual = SpokenMathParser.convert(input)
        assertEquals(expected, actual)

        // Running convert() again on the result must be idempotent
        assertEquals(expected, SpokenMathParser.convert(actual))
    }

    @Test
    fun testSpokenMathTriggerMultiplication() {
        // "1 into 1 equals" -> "1 * 1 = 1"
        assertEquals(
            "1 * 1 = 1",
            SpokenMathParser.parse("1 into 1 equals")
        )

        // "450 multiplied by 12 equals to" -> "450 * 12 = 5,400"
        assertEquals(
            "450 * 12 = 5,400",
            SpokenMathParser.parse("450 multiplied by 12 equals to")
        )

        // Times and guna
        assertEquals(
            "12 * 12 = 144",
            SpokenMathParser.parse("12 times 12 equals")
        )
        assertEquals(
            "25 * 4 = 100",
            SpokenMathParser.parse("25 guna 4 barabar")
        )

        // "5 times 4 equals"
        assertEquals(
            "5 * 4 = 20",
            SpokenMathParser.parse("5 times 4 equals")
        )

        // Word numbers: "five times four equals"
        assertEquals(
            "5 * 4 = 20",
            SpokenMathParser.parse("five times four equals")
        )

        // Trailing '=' symbol trigger: "5 times 4 ="
        assertEquals(
            "5 * 4 = 20",
            SpokenMathParser.parse("5 times 4 =")
        )
    }

    @Test
    fun testSpokenMathTriggerPercentage() {
        // "18 percent of 4200 equals" -> "18% of 4,200 = 756"
        assertEquals(
            "18% of 4,200 = 756",
            SpokenMathParser.parse("18 percent of 4200 equals")
        )

        // "18% of 4200 equals"
        assertEquals(
            "18% of 4,200 = 756",
            SpokenMathParser.parse("18% of 4200 equals")
        )

        // "18 percent or 4200 equals" (STT mishears 'of' as 'or')
        assertEquals(
            "18% of 4,200 = 756",
            SpokenMathParser.parse("18 percent or 4200 equals")
        )

        // "10% or 500 equals to"
        assertEquals(
            "10% of 500 = 50",
            SpokenMathParser.parse("10% or 500 equals to")
        )

        // "18 percent of 4200 equals to"
        assertEquals(
            "18% of 4,200 = 756",
            SpokenMathParser.parse("18 percent of 4200 equals to")
        )

        // percentage of
        assertEquals(
            "5% of 1,000 = 50",
            SpokenMathParser.parse("5 percentage of 1000 equal to")
        )
    }

    @Test
    fun testSpokenMathTriggerDivision() {
        // "100 divided by 4 barabar" -> "100 / 4 = 25"
        assertEquals(
            "100 / 4 = 25",
            SpokenMathParser.parse("100 divided by 4 barabar")
        )

        // Hindi Devanagari "बराबर"
        assertEquals(
            "100 / 4 = 25",
            SpokenMathParser.parse("100 divided by 4 बराबर")
        )

        // "bata" (Hindi division)
        assertEquals(
            "100 / 4 = 25",
            SpokenMathParser.parse("100 bata 4 barabar")
        )

        // "by" between numbers
        assertEquals(
            "100 / 4 = 25",
            SpokenMathParser.parse("100 by 4 equals")
        )
    }

    @Test
    fun testSpokenMathTriggerAdditionAndSubtraction() {
        assertEquals(
            "200 + 50 = 250",
            SpokenMathParser.parse("200 plus 50 equals")
        )
        assertEquals(
            "200 - 50 = 150",
            SpokenMathParser.parse("200 minus 50 equals to")
        )
        assertEquals(
            "500 - 150 = 350",
            SpokenMathParser.parse("500 less 150 barabar")
        )
    }

    @Test
    fun testSpokenMathNonTriggersUntouched() {
        // "walk into the room" (no trigger, normal English sentence)
        assertEquals(
            "walk into the room",
            SpokenMathParser.parse("walk into the room")
        )

        // "450 into 12" (without equals trigger word)
        assertEquals(
            "450 into 12",
            SpokenMathParser.parse("450 into 12")
        )

        // Non-math word before equals
        assertEquals(
            "he equals his brother in skill",
            SpokenMathParser.parse("he equals his brother in skill")
        )

        // Empty / blank
        assertEquals("", SpokenMathParser.parse(""))
        assertEquals("   ", SpokenMathParser.parse("   "))
    }

    @Test
    fun testSpokenMathEmbeddedInSentence() {
        assertEquals(
            "Total bill is 450 * 12 = 5,400 please check",
            SpokenMathParser.parse("Total bill is 450 into 12 equals please check")
        )
    }

    @Test
    fun testSpokenMathDivisionByZeroUntouched() {
        // Division by zero causes evaluate() to return null -> original text untouched
        assertEquals(
            "100 divided by 0 equals",
            SpokenMathParser.parse("100 divided by 0 equals")
        )
    }
}
