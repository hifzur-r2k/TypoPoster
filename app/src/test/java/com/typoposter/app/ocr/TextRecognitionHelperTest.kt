package com.typoposter.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRecognitionHelperTest {

    @Test
    fun testCheckboxNormalization() {
        // Glued D / O before TitleCase word
        assertEquals("☐ Me", TextRecognitionHelper.normalizeCheckboxOption("DMe"))
        assertEquals("☐ Yes", TextRecognitionHelper.normalizeCheckboxOption("OYes"))
        assertEquals("☐ The agency", TextRecognitionHelper.normalizeCheckboxOption("OThe agency"))
        assertEquals("☐ Evasive", TextRecognitionHelper.normalizeCheckboxOption("DEvasive"))
        assertEquals("☐ Shared", TextRecognitionHelper.normalizeCheckboxOption("DShared"))
        assertEquals("☐ Partial", TextRecognitionHelper.normalizeCheckboxOption("DPartial"))
        assertEquals("☐ Unclear", TextRecognitionHelper.normalizeCheckboxOption("OUnclear"))

        // Standalone D / O / 0 / [] before space
        assertEquals("☐ Shared", TextRecognitionHelper.normalizeCheckboxOption("D Shared"))
        assertEquals("☐ The agency [RED FLAG]", TextRecognitionHelper.normalizeCheckboxOption("O The agency [RED FLAG]"))
        assertEquals("☐ Option 1", TextRecognitionHelper.normalizeCheckboxOption("0 Option 1"))
        assertEquals("☐ Option 2", TextRecognitionHelper.normalizeCheckboxOption("[] Option 2"))
        assertEquals("☐ Option 3", TextRecognitionHelper.normalizeCheckboxOption("[ ] Option 3"))

        // Conservative guardrails: Ensure standard English words are NEVER corrupted
        assertEquals("Do you agree?", TextRecognitionHelper.normalizeCheckboxOption("Do you agree?"))
        assertEquals("On time", TextRecognitionHelper.normalizeCheckboxOption("On time"))
        assertEquals("Or else", TextRecognitionHelper.normalizeCheckboxOption("Or else"))
        assertEquals("Done with task", TextRecognitionHelper.normalizeCheckboxOption("Done with task"))
        assertEquals("Order number", TextRecognitionHelper.normalizeCheckboxOption("Order number"))
        assertEquals("DESIGN SYSTEM", TextRecognitionHelper.normalizeCheckboxOption("DESIGN SYSTEM"))
    }

    @Test
    fun testSafeMarkdownGuardrails() {
        // Prevent double colons
        assertEquals("**Step score (1 to 5):**", TextRecognitionHelper.formatLabel("Step score (1 to 5):"))
        assertEquals("**Status:**", TextRecognitionHelper.formatLabel("Status"))
        assertEquals("**Review:**", TextRecognitionHelper.formatLabel("Review:"))
        assertEquals("**Questions?**", TextRecognitionHelper.formatLabel("Questions?"))
        assertEquals("**Questions?**", TextRecognitionHelper.formatLabel("Questions?:"))

        // Clean questions
        assertEquals("Who owns the creative files?", TextRecognitionHelper.cleanQuestion("Who owns the creative files?:"))
        assertEquals("Are ad accounts shared?", TextRecognitionHelper.cleanQuestion("Are ad accounts shared?"))

        // Ensure bold spans are balanced
        val unbalanced = "Step 8:** Details here"
        val sanitized = TextRecognitionHelper.sanitizeMarkdown(unbalanced)
        assertFalse(sanitized.count { it == '*' } % 2 != 0)
    }

    @Test
    fun testMultiLineChecklistTableSimulation() {
        // Simulate scanning a 4-column checklist table:
        // Col 0: "#" (x: 20..60)
        // Col 1: "Ask" (x: 80..360)
        // Col 2: "Tick what they said" (x: 390..590)
        // Col 3: "Remarks" (x: 620..820)
        val syntheticLines = listOf(
            // Header Row
            OcrLine("#", left = 20, top = 50, right = 40, bottom = 70),
            OcrLine("Ask", left = 80, top = 50, right = 140, bottom = 70),
            OcrLine("Tick what they said", left = 390, top = 50, right = 550, bottom = 70),
            OcrLine("Remarks", left = 620, top = 50, right = 720, bottom = 70),

            // Row 8.1
            // Col 0 (Row Anchor)
            OcrLine("8.1", left = 20, top = 100, right = 50, bottom = 120),
            // Col 1 (Wrapped Question across 3 lines)
            OcrLine("Who owns the ad accounts,", left = 80, top = 100, right = 350, bottom = 120),
            OcrLine("analytics, website, and creative", left = 80, top = 125, right = 360, bottom = 145),
            OcrLine("files?:", left = 80, top = 150, right = 150, bottom = 170),
            // Col 2 (Stacked Checkboxes misread as DMe, D Shared, OThe agency)
            OcrLine("DMe", left = 390, top = 100, right = 450, bottom = 120),
            OcrLine("D Shared", left = 390, top = 125, right = 490, bottom = 145),
            OcrLine("OThe agency [RED FLAG]", left = 390, top = 150, right = 590, bottom = 170),
            // Col 3 (Remarks)
            OcrLine("Must be client-owned", left = 620, top = 100, right = 800, bottom = 120),

            // Row 8.2
            // Col 0 (Row Anchor)
            OcrLine("8.2", left = 20, top = 220, right = 50, bottom = 240),
            // Col 1 (Wrapped Question across 2 lines)
            OcrLine("What attribution window do", left = 80, top = 220, right = 340, bottom = 240),
            OcrLine("you use for reporting?:", left = 80, top = 245, right = 310, bottom = 265),
            // Col 2 (Stacked Options)
            OcrLine("7-day click / 1-day view", left = 390, top = 220, right = 560, bottom = 240),
            OcrLine("DEvasive / unclear", left = 390, top = 245, right = 530, bottom = 265),
            // Col 3 (Remarks)
            OcrLine("Verify in Meta Events Manager", left = 620, top = 220, right = 820, bottom = 240),

            // Post-table section (Outside table)
            OcrLine("Step score (1 to 5):", left = 20, top = 340, right = 250, bottom = 360)
        )

        val output = TextRecognitionHelper.processOcrLines(syntheticLines, usePointwiseTable = true)

        // 1. Verify row 8.1 was NOT spliced into fake bullets
        assertFalse("Should not have fake bullet from wrapped line", output.contains("• **analytics, website, and creative:**"))
        assertFalse("Should not have fake bullet from files line", output.contains("• **files?:**"))

        // 2. Verify questions are properly unwrapped
        assertTrue(
            "Question in 8.1 should be unwrapped cleanly",
            output.contains("**8.1** Who owns the ad accounts, analytics, website, and creative files?")
        )
        assertTrue(
            "Question in 8.2 should be unwrapped cleanly",
            output.contains("**8.2** What attribution window do you use for reporting?")
        )

        // 3. Verify checkboxes are normalized and intact
        assertTrue(
            "Checkboxes in 8.1 should be normalized and paired with row 8.1",
            output.contains("☐ Me  •  ☐ Shared  •  ☐ The agency [RED FLAG]")
        )
        assertTrue(
            "Checkboxes in 8.2 should be normalized",
            output.contains("☐ Evasive / unclear")
        )

        // 4. Verify no orphaned cell lines became fake headings (## Partial, ## Unclear, etc.)
        assertFalse("Should not contain fake heading for options", output.contains("## Me"))
        assertFalse("Should not contain fake heading for options", output.contains("## Shared"))
        assertFalse("Should not contain fake heading for options", output.contains("## The agency"))

        // 5. Verify remarks are properly retained under each row
        assertTrue("Remarks for 8.1 retained", output.contains("Must be client-owned"))
        assertTrue("Remarks for 8.2 retained", output.contains("Verify in Meta Events Manager"))

        // 6. Verify post-table key-value label does NOT have double colons
        assertFalse("No double colons in Step score", output.contains("::"))
        assertTrue("Step score formatted cleanly", output.contains("**Step score (1 to 5):**"))
    }

    @Test
    fun testNotebookRulingLineAndPenSpeckSuppression() {
        // Ruling line artifacts from lined notebook paper
        assertTrue(TextRecognitionHelper.isRulingLineArtifact("----------"))
        assertTrue(TextRecognitionHelper.isRulingLineArtifact("__________"))
        assertTrue(TextRecognitionHelper.isRulingLineArtifact("— — — — —"))
        assertTrue(TextRecognitionHelper.isRulingLineArtifact("~~~~~~"))
        assertFalse(TextRecognitionHelper.isRulingLineArtifact("Normal sentence"))
        assertFalse(TextRecognitionHelper.isRulingLineArtifact("1. First item"))

        // Pen specks / ink resting dots
        assertTrue(TextRecognitionHelper.isPenSpeck("."))
        assertTrue(TextRecognitionHelper.isPenSpeck("'"))
        assertTrue(TextRecognitionHelper.isPenSpeck(","))
        assertTrue(TextRecognitionHelper.isPenSpeck("~"))
        assertFalse(TextRecognitionHelper.isPenSpeck("A"))
        assertFalse(TextRecognitionHelper.isPenSpeck("No"))

        // Test in layout processor
        val handwrittenNoteWithRulingLines = listOf(
            OcrLine("Meeting Notes", left = 20, top = 20, right = 200, bottom = 45),
            OcrLine("----------", left = 20, top = 50, right = 500, bottom = 55),
            OcrLine("Discussed project roadmap and delivery schedule", left = 20, top = 70, right = 450, bottom = 95),
            OcrLine(".", left = 20, top = 100, right = 25, bottom = 105), // stray pen speck
            OcrLine("____________________", left = 20, top = 110, right = 500, bottom = 115),
            OcrLine("Next steps:", left = 20, top = 130, right = 150, bottom = 150),
            OcrLine("• Ship v1.2 release", left = 20, top = 160, right = 220, bottom = 180)
        )

        val output = TextRecognitionHelper.processOcrLines(handwrittenNoteWithRulingLines)
        assertFalse("Ruling lines should be suppressed", output.contains("----------"))
        assertFalse("Underscore ruling lines should be suppressed", output.contains("_____"))
        assertFalse("Isolated pen specks should not appear alone", output.lines().any { it.trim() == "." })
        assertTrue("Content preserved", output.contains("Discussed project roadmap and delivery schedule"))
        assertTrue("Next steps preserved", output.contains("Next steps:"))
        assertTrue("Bullet item preserved", output.contains("• Ship v1.2 release"))
    }

    @Test
    fun testFiveColumnTablePreservation() {
        val lines = listOf(
            // Header Row (columns 0..4)
            OcrLine("Item", left = 20, top = 40, right = 60, bottom = 60),
            OcrLine("Description", left = 100, top = 40, right = 220, bottom = 60),
            OcrLine("Qty", left = 280, top = 40, right = 320, bottom = 60),
            OcrLine("Rate", left = 380, top = 40, right = 420, bottom = 60),
            OcrLine("Amount", left = 480, top = 40, right = 550, bottom = 60),

            // Row 1
            OcrLine("1", left = 20, top = 80, right = 40, bottom = 100),
            OcrLine("Organic Apples", left = 100, top = 80, right = 240, bottom = 100),
            OcrLine("2 kg", left = 280, top = 80, right = 330, bottom = 100),
            OcrLine("Rs 120", left = 380, top = 80, right = 440, bottom = 100),
            OcrLine("Rs 240", left = 480, top = 80, right = 550, bottom = 100),

            // Row 2
            OcrLine("2", left = 20, top = 120, right = 40, bottom = 140),
            OcrLine("Almond Milk", left = 100, top = 120, right = 220, bottom = 140),
            OcrLine("1 L", left = 280, top = 120, right = 320, bottom = 140),
            OcrLine("Rs 180", left = 380, top = 120, right = 440, bottom = 140),
            OcrLine("Rs 180", left = 480, top = 120, right = 550, bottom = 140)
        )

        val output = TextRecognitionHelper.processOcrLines(lines, usePointwiseTable = true)

        // Verify all 5 columns are preserved in output and not truncated
        assertTrue("Row 1 index preserved", output.contains("**1:** Organic Apples"))
        assertTrue("Row 1 quantity preserved", output.contains("2 kg"))
        assertTrue("Row 1 rate preserved", output.contains("Rs 120"))
        assertTrue("Row 1 amount preserved (col 4)", output.contains("Rs 240"))

        assertTrue("Row 2 index preserved", output.contains("**2:** Almond Milk"))
        assertTrue("Row 2 quantity preserved", output.contains("1 L"))
        assertTrue("Row 2 rate preserved", output.contains("Rs 180"))
        assertTrue("Row 2 amount preserved (col 4)", output.contains("Rs 180"))
    }

    @Test
    fun testProportionalColumnClustering() {
        // Low-res: 720px wide image with 3 columns around x = 50, 300, 550
        val lowResLines = listOf(
            OcrLine("A1", left = 50, top = 20, right = 150, bottom = 40),
            OcrLine("B1", left = 300, top = 20, right = 420, bottom = 40),
            OcrLine("C1", left = 550, top = 20, right = 680, bottom = 40),
            OcrLine("A2", left = 58, top = 60, right = 160, bottom = 80),
            OcrLine("B2", left = 312, top = 60, right = 430, bottom = 80),
            OcrLine("C2", left = 562, top = 60, right = 690, bottom = 80)
        )
        val lowResBounds = TextRecognitionHelper.computeColumnBounds(lowResLines)
        assertEquals(3, lowResBounds.size)

        // High-res: 3600px wide image with 3 columns around x = 200, 1600, 2900 with 60px variations
        // Proportional tolerance ensures 60px-70px variations cluster properly
        val highResLines = listOf(
            OcrLine("A1", left = 200, top = 100, right = 800, bottom = 200),
            OcrLine("B1", left = 1600, top = 100, right = 2300, bottom = 200),
            OcrLine("C1", left = 2900, top = 100, right = 3500, bottom = 200),
            OcrLine("A2", left = 265, top = 280, right = 850, bottom = 380),
            OcrLine("B2", left = 1670, top = 280, right = 2360, bottom = 380),
            OcrLine("C2", left = 2960, top = 280, right = 3550, bottom = 380)
        )
        val highResBounds = TextRecognitionHelper.computeColumnBounds(highResLines)
        assertEquals(3, highResBounds.size)
    }

    @Test
    fun testReceiptKeyValueFormatting() {
        val lines = listOf(
            OcrLine("STATE BANK OF INDIA", left = 20, top = 20, right = 300, bottom = 45),
            OcrLine("Payee Name: Deepak Kumar Padit", left = 20, top = 60, right = 400, bottom = 85),
            OcrLine("Transfer Mode: NEFT", left = 20, top = 95, right = 300, bottom = 120),
            OcrLine("Transfer Date: 21 Nov 2026", left = 20, top = 130, right = 350, bottom = 155),
            OcrLine("Amount: ₹4,500.00", left = 20, top = 165, right = 280, bottom = 190)
        )

        val output = TextRecognitionHelper.processOcrLines(lines)

        // Heading verified
        assertTrue(output.contains("## STATE BANK OF INDIA"))

        // Key-Values formatted with bold labels and NOT ## headings
        assertFalse(output.contains("## Payee Name"))
        assertFalse(output.contains("## Transfer Mode"))
        assertTrue(output.contains("**Payee Name:** Deepak Kumar Padit"))
        assertTrue(output.contains("**Transfer Mode:** NEFT"))
        assertTrue(output.contains("**Transfer Date:** 21 Nov 2026"))
        assertTrue(output.contains("**Amount:** ₹4,500.00"))

        // Verify compact single newline between consecutive Key-Value lines
        assertTrue(
            "Consecutive key-value lines should be separated by single newline",
            output.contains("**Payee Name:** Deepak Kumar Padit\n**Transfer Mode:** NEFT\n**Transfer Date:** 21 Nov 2026\n**Amount:** ₹4,500.00")
        )
    }

    @Test
    fun testTimeColonNotSplit() {
        val lines = listOf(
            OcrLine("WhatsApp • 8:51 pm", left = 20, top = 20, right = 250, bottom = 45),
            OcrLine("Call at 10:30 am with client", left = 20, top = 60, right = 350, bottom = 85),
            OcrLine("Transfer Time: 8:51 pm", left = 20, top = 100, right = 300, bottom = 125)
        )

        val output = TextRecognitionHelper.processOcrLines(lines)

        // Verify time colon in WhatsApp • 8:51 pm is not split into fake key-value
        assertFalse("Should not split on time colon", output.contains("**WhatsApp 8:**"))
        assertFalse("Should not split on time colon", output.contains("**WhatsApp • 8:**"))
        assertTrue("Original time line preserved", output.contains("WhatsApp • 8:51 pm"))

        // Regular sentence with time is preserved
        assertFalse(output.contains("**Call at 10:**"))
        assertTrue(output.contains("Call at 10:30 am with client"))

        // Legitimate key-value with time value is preserved as key-value
        assertTrue(output.contains("**Transfer Time:** 8:51 pm"))
    }

    @Test
    fun testMaskedAccountNumbersNormalized() {
        val lines = listOf(
            OcrLine("Account Number: **** ***7 572", left = 20, top = 20, right = 400, bottom = 45),
            OcrLine("Debit Card: 4111 **** **** 1111", left = 20, top = 60, right = 420, bottom = 85)
        )

        val output = TextRecognitionHelper.processOcrLines(lines)

        // Masked asterisks converted to dots
        assertFalse("Should not contain raw runs of asterisks", output.contains("****"))
        assertTrue("Should convert masked account number to dots", output.contains("**Account Number:** •••• •••7 572"))
        assertTrue("Should convert masked card number to dots", output.contains("**Debit Card:** 4111 •••• •••• 1111"))

        // Ensure markdown bold spans are balanced
        val linesInOutput = output.lines()
        for (line in linesInOutput) {
            val asteriskCount = line.windowed(2).count { it == "**" } * 2
            val totalAsterisks = line.count { it == '*' }
            assertEquals("All asterisks in line must be part of paired bold markers: $line", asteriskCount, totalAsterisks)
        }
    }

    @Test
    fun testCleanScannedTextPaymentSlipWithNoise() {
        val rawPointwise = """
            STATE BANK OF INDIA
            Payment Receipt
            Payee Name: Deepak Kumar Padit
            Amount: ₹4,500.00
            Transfer Mode: NEFT
            Transfer Date: 21 Nov 2026
            Reference ID: UTR9876543210
            Status: Successful
            Reply  Mark as read  Mute
        """.trimIndent()

        val cleaned = TextRecognitionHelper.cleanScannedText(rawPointwise)

        // Notification buttons must be stripped
        assertFalse("Notification bar buttons should be stripped", cleaned.contains("Reply"))
        assertFalse("Notification bar buttons should be stripped", cleaned.contains("Mark as read"))
        assertFalse("Notification bar buttons should be stripped", cleaned.contains("Mute"))

        // Headings & payment details preserved and formatted as clean key-values
        assertTrue(cleaned.contains("STATE BANK OF INDIA"))
        assertTrue(cleaned.contains("**Payee Name:** Deepak Kumar Padit"))
        assertTrue(cleaned.contains("**Amount:** ₹4,500.00"))
        assertTrue(cleaned.contains("**Transfer Mode:** NEFT"))
        assertTrue(cleaned.contains("**Transfer Date:** 21 Nov 2026"))
        assertTrue(cleaned.contains("**Reference ID:** UTR9876543210"))
        assertTrue(cleaned.contains("**Status:** Successful"))

        // Consecutive key-values separated by single newline
        assertTrue(
            "Consecutive key-value lines should be compact",
            cleaned.contains("**Payee Name:** Deepak Kumar Padit\n**Amount:** ₹4,500.00\n**Transfer Mode:** NEFT\n**Transfer Date:** 21 Nov 2026\n**Reference ID:** UTR9876543210\n**Status:** Successful")
        )
    }

    @Test
    fun testCleanScannedTextMixedItemRateCountList() {
        val rawList = """
            Site Material & Labor
            • Cement — 50 bags — Rs 19000
            • Sand — 2 trolleys — Rs 9000
            • Mason (2) — ₹1600
            • Helper (4) — ₹2400
            1. Bricks — 5000 pcs — Rs 45000
            2. Steel — 500 kg — Rs 35000
        """.trimIndent()

        val cleaned = TextRecognitionHelper.cleanScannedText(rawList)

        // List items must NOT become ## headings
        assertFalse(cleaned.contains("## Cement"))
        assertFalse(cleaned.contains("## Mason"))
        assertFalse(cleaned.contains("## 1."))

        // Item — Value pairs must remain on single lines
        assertTrue(cleaned.contains("• Cement — 50 bags — Rs 19000"))
        assertTrue(cleaned.contains("• Sand — 2 trolleys — Rs 9000"))
        assertTrue(cleaned.contains("• Mason (2) — ₹1600"))
        assertTrue(cleaned.contains("• Helper (4) — ₹2400"))
        assertTrue(cleaned.contains("1. Bricks — 5000 pcs — Rs 45000"))
        assertTrue(cleaned.contains("2. Steel — 500 kg — Rs 35000"))
    }

    @Test
    fun testCleanScannedTextHandwrittenNoteWithWrapsAndPunctuation() {
        val rawNote = """
            Kal site pe cement aur sand delivery
            aayegi , subah 9 baje tak .
            Please verify payment with vendor :
            advance amount paid ₹ 5000 .
        """.trimIndent()

        val cleaned = TextRecognitionHelper.cleanScannedText(rawNote)

        // Mid-sentence line wrap collapsed
        assertTrue(
            "Accidental mid-sentence wrap should be collapsed",
            cleaned.contains("Kal site pe cement aur sand delivery aayegi, subah 9 baje tak.")
        )

        // Spacing before punctuation cleaned
        assertFalse("No space before comma", cleaned.contains(" ,"))
        assertFalse("No space before period", cleaned.contains(" ."))
        assertFalse("No space before colon", cleaned.contains(" :"))
        assertTrue(cleaned.contains("vendor:"))

        // Currency spacing cleaned
        assertTrue("Currency symbol formatted cleanly", cleaned.contains("₹5000."))
        assertFalse(cleaned.contains("₹ 5000"))
    }
}
