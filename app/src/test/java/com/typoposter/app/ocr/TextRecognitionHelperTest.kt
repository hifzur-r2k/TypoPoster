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
}
