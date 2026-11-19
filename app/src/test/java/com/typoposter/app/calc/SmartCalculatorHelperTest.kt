package com.typoposter.app.calc

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SmartCalculatorHelperTest {

    // ─────────────────────────────────────────────────────────────
    //  Basic Arithmetic & Precedence Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testBasicArithmetic() {
        assertEquals("300", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("(120 + 30) * 4 / 2")!!))
        assertEquals("5,250", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("1500 * 3.5")!!))
        assertEquals("7,500", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("10000 - 2500")!!))
        assertEquals("20", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("10 + 2.5 * 4")!!))
        assertEquals("50", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("(10 + 2.5) * 4")!!))
        assertEquals("256", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("2 ^ 8")!!))
        assertEquals("5", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("-5 + 10")!!))
    }

    @Test
    fun testCommasAndUnicodeSymbols() {
        // Formatted thousands commas in inputs
        assertEquals("4,000", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("1,500 + 2,500")!!))
        assertEquals("17,500", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("10,000 + 7,500")!!))

        // Unicode math symbols (×, ÷, −)
        assertEquals("50", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("10 × 5")!!))
        assertEquals("20", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("100 ÷ 5")!!))
        assertEquals("80", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("100 − 20")!!))

        // 'x' as multiplication between numbers
        assertEquals("42", SmartCalculatorHelper.formatResult(SmartCalculatorHelper.evaluate("6 x 7")!!))
    }

    // ─────────────────────────────────────────────────────────────
    //  Percentage Calculations
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testPercentageCalculations() {
        // "18% of 4200" -> 756
        val pctOf = SmartCalculatorHelper.evaluate("18% of 4200")
        assertNotNull(pctOf)
        assertEquals("756", SmartCalculatorHelper.formatResult(pctOf!!))

        // "4200 + 18%" -> 4956
        val addPct = SmartCalculatorHelper.evaluate("4200 + 18%")
        assertNotNull(addPct)
        assertEquals("4,956", SmartCalculatorHelper.formatResult(addPct!!))

        // "4200 - 10%" -> 3780
        val subPct = SmartCalculatorHelper.evaluate("4200 - 10%")
        assertNotNull(subPct)
        assertEquals("3,780", SmartCalculatorHelper.formatResult(subPct!!))

        // "50 * 20%" -> 10
        val mulPct = SmartCalculatorHelper.evaluate("50 * 20%")
        assertNotNull(mulPct)
        assertEquals("10", SmartCalculatorHelper.formatResult(mulPct!!))

        // "100 / 50%" -> 200
        val divPct = SmartCalculatorHelper.evaluate("100 / 50%")
        assertNotNull(divPct)
        assertEquals("200", SmartCalculatorHelper.formatResult(divPct!!))
    }

    // ─────────────────────────────────────────────────────────────
    //  Safe Error Handling (Division by Zero, Incomplete Syntax)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testSafeErrorHandling() {
        // Division by zero
        assertNull(SmartCalculatorHelper.evaluate("10 / 0"))
        assertNull(SmartCalculatorHelper.evaluate("5 / (2 - 2)"))

        // Incomplete or invalid syntax
        assertNull(SmartCalculatorHelper.evaluate("10 +"))
        assertNull(SmartCalculatorHelper.evaluate("(10 + 2"))
        assertNull(SmartCalculatorHelper.evaluate("++"))
        assertNull(SmartCalculatorHelper.evaluate("hello world"))
        assertNull(SmartCalculatorHelper.evaluate(""))
    }

    // ─────────────────────────────────────────────────────────────
    //  Inline Math Detection (=)
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testInlineMathDetectionAndInsertion() {
        // Cursor right after '='
        val text1 = "Total cost: 4200 + 18% ="
        val val1 = TextFieldValue(text1, TextRange(text1.length))
        val sug1 = SmartCalculatorHelper.detectCalcSuggestion(val1)
        assertNotNull(sug1)
        assertTrue(sug1 is CalcSuggestion.InlineMath)
        val math1 = sug1 as CalcSuggestion.InlineMath
        assertEquals("4,956", math1.formattedResult)
        assertEquals(" 4,956", math1.insertText)

        // Apply suggestion
        val applied1 = SmartCalculatorHelper.applySuggestion(val1, math1)
        assertEquals("Total cost: 4200 + 18% = 4,956", applied1.text)
        assertEquals(applied1.text.length, applied1.selection.end)

        // Cursor after space: "18% of 4200 = "
        val text2 = "18% of 4200 = "
        val val2 = TextFieldValue(text2, TextRange(text2.length))
        val sug2 = SmartCalculatorHelper.detectCalcSuggestion(val2)
        assertNotNull(sug2)
        val math2 = sug2 as CalcSuggestion.InlineMath
        assertEquals("756", math2.formattedResult)
        assertEquals("756", math2.insertText)
        val applied2 = SmartCalculatorHelper.applySuggestion(val2, math2)
        assertEquals("18% of 4200 = 756", applied2.text)

        // Non-math text ending with '=' should NOT trigger
        val textNoMath = "My variable name ="
        val valNoMath = TextFieldValue(textNoMath, TextRange(textNoMath.length))
        assertNull(SmartCalculatorHelper.detectCalcSuggestion(valNoMath))

        // Multiplication with '*'
        val textMul = "1500 * 3.5 ="
        val valMul = TextFieldValue(textMul, TextRange(textMul.length))
        val sugMul = SmartCalculatorHelper.detectCalcSuggestion(valMul)
        assertNotNull(sugMul)
        assertEquals("5,250", (sugMul as CalcSuggestion.InlineMath).formattedResult)

        // User typed '=' and pressed Enter (cursor on the next line)
        val textEnter = "10 + 20 =\n"
        val valEnter = TextFieldValue(textEnter, TextRange(textEnter.length))
        val sugEnter = SmartCalculatorHelper.detectCalcSuggestion(valEnter)
        assertNotNull(sugEnter)
        assertEquals("30", (sugEnter as CalcSuggestion.InlineMath).formattedResult)
    }

    // ─────────────────────────────────────────────────────────────
    //  Auto-Sum (/sum) Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testAutoSumNumberExtraction() {
        assertEquals(BigDecimal("1200"), SmartCalculatorHelper.extractNumberFromLine("1200"))
        assertEquals(BigDecimal("14250"), SmartCalculatorHelper.extractNumberFromLine("14,250"))
        assertEquals(BigDecimal("450"), SmartCalculatorHelper.extractNumberFromLine("• Flight: $450"))
        assertEquals(BigDecimal("680.50"), SmartCalculatorHelper.extractNumberFromLine("• Hotel: €680.50"))
        assertEquals(BigDecimal("4956"), SmartCalculatorHelper.extractNumberFromLine("4200 + 18% = 4,956"))
        assertEquals(BigDecimal("150"), SmartCalculatorHelper.extractNumberFromLine("1. Dinner: 150"))
    }

    @Test
    fun testAutoSumBlockScanning() {
        val doc = """
            # Expenses
            Flight: $450
            Hotel: $680.50
            Meals: $120
            /sum
        """.trimIndent()

        val cursor = doc.indexOf("/sum") + 4
        val value = TextFieldValue(doc, TextRange(cursor))
        val sug = SmartCalculatorHelper.detectCalcSuggestion(value)
        assertNotNull(sug)
        assertTrue(sug is CalcSuggestion.AutoSum)
        val sumSug = sug as CalcSuggestion.AutoSum
        assertEquals(3, sumSug.count)
        assertEquals("1,250.5", sumSug.formattedTotal)

        val applied = SmartCalculatorHelper.applySuggestion(value, sumSug)
        val expected = """
            # Expenses
            Flight: $450
            Hotel: $680.50
            Meals: $120
            1,250.5
        """.trimIndent()
        assertEquals(expected, applied.text)
    }

    @Test
    fun testAutoSumBlankLineBoundary() {
        val doc = """
            Previous Section
            100
            200
            
            Current Section
            • 50
            • 60
            /sum
        """.trimIndent()

        val cursor = doc.indexOf("/sum") + 4
        val value = TextFieldValue(doc, TextRange(cursor))
        val sug = SmartCalculatorHelper.detectCalcSuggestion(value)
        assertNotNull(sug)
        val sumSug = sug as CalcSuggestion.AutoSum
        // Only 50 and 60 should be summed because of the blank line
        assertEquals(2, sumSug.count)
        assertEquals("110", sumSug.formattedTotal)
    }

    @Test
    fun testAutoSumHeadingBoundary() {
        val doc = """
            # Older Group
            1000
            2000
            ## Active Group
            300
            400
            /sum
        """.trimIndent()

        val cursor = doc.indexOf("/sum") + 4
        val value = TextFieldValue(doc, TextRange(cursor))
        val sug = SmartCalculatorHelper.detectCalcSuggestion(value)
        assertNotNull(sug)
        val sumSug = sug as CalcSuggestion.AutoSum
        // Heading boundary stops at "## Active Group", so only 300 and 400 are summed
        assertEquals(2, sumSug.count)
        assertEquals("700", sumSug.formattedTotal)
    }
}
