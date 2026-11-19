package com.typoposter.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val pointwiseText: String, // Point-wise table and structured document (Default)
    val gridTableText: String, // Markdown grid table and structured document
    val smartFlowText: String, // Clean paragraph flow with list points
    val exactText: String,     // Raw physical lines
    val bitmap: Bitmap?,
    val rotation: Int
)

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val blockIndex: Int = 0
) {
    val width: Int get() = maxOf(1, right - left)
    val height: Int get() = maxOf(1, bottom - top)
    val centerY: Int get() = (top + bottom) / 2
    val centerX: Int get() = (left + right) / 2
}

object TextRecognitionHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extractText(
        context: Context,
        uri: Uri,
        additionalRotationDegrees: Int = 0
    ): Result<OcrResult> = withContext(Dispatchers.IO) {
        try {
            // 1. Determine natural EXIF orientation
            val exifRotation = getExifOrientation(context, uri)
            val totalRotation = (exifRotation + additionalRotationDegrees) % 360

            // 2. Load sampled bitmap with high resolution (3840px / 4K UHD) to prevent
            // downsampling thin font strokes, light font weights, or fine serifs
            val baseBitmap = decodeSampledBitmap(context, uri, maxDimension = 3840)
                ?: return@withContext Result.failure(Exception("Could not decode image from camera"))

            // 3. Apply orientation rotation so image is right-side up
            val orientedBitmap = if (totalRotation != 0) {
                rotateBitmap(baseBitmap, totalRotation)
            } else {
                baseBitmap
            }

            // 4. Dual-Pass OCR for Maximum Accuracy on Print, Handwriting & Colored Ink:
            // Pass 1: High-resolution RGB (optimal for human handwriting, blue/black ballpoint ink,
            // pencil shading, and natural stroke continuity).
            val primaryImage = InputImage.fromBitmap(orientedBitmap, 0)
            val primaryVisionText = processImageAsync(recognizer, primaryImage)
            val primaryWordCount = countRecognizedWords(primaryVisionText)

            // Pass 2: If primary pass extracted sparse text (e.g. low-contrast faint print or faint pencil),
            // evaluate a contrast-enhanced pass and select whichever produced higher yield
            val finalVisionText = if (primaryWordCount < 10) {
                val enhancedBitmap = enhanceBitmapForOcr(orientedBitmap)
                val secondaryImage = InputImage.fromBitmap(enhancedBitmap, 0)
                val secondaryVisionText = processImageAsync(recognizer, secondaryImage)
                val secondaryWordCount = countRecognizedWords(secondaryVisionText)
                if (secondaryWordCount > primaryWordCount) secondaryVisionText else primaryVisionText
            } else {
                primaryVisionText
            }

            val pointwise = buildDocument(finalVisionText, usePointwiseTable = true)
            val gridTable = buildDocument(finalVisionText, usePointwiseTable = false)
            val smart = buildSmartFlow(finalVisionText)
            val exact = buildExactLines(finalVisionText)

            Result.success(
                OcrResult(
                    pointwiseText = pointwise,
                    gridTableText = gridTable,
                    smartFlowText = smart,
                    exactText = exact,
                    bitmap = orientedBitmap,
                    rotation = totalRotation
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun processImageAsync(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        inputImage: InputImage
    ): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                continuation.resume(visionText)
            }
            .addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }
    }

    private fun countRecognizedWords(visionText: Text): Int {
        return visionText.textBlocks.sumOf { b -> b.lines.sumOf { l -> l.elements.size } }
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        var inputStream: InputStream? = null
        return try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                val exif = ExifInterface(inputStream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } else {
                0
            }
        } catch (e: Exception) {
            0
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
        }
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight
        if (origWidth <= 0 || origHeight <= 0) return null

        var sampleSize = 1
        val maxSide = maxOf(origWidth, origHeight)
        // Keep high resolution (up to 3840px / 4K) so small & thin fonts preserve character stroke width
        while ((maxSide / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        // Apply high-quality bilinear scaling if still exceeding maxDimension
        val curMax = maxOf(decoded.width, decoded.height)
        return if (curMax > maxDimension) {
            val scale = maxDimension.toFloat() / curMax
            val dstW = (decoded.width * scale).toInt()
            val dstH = (decoded.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
            if (scaled != decoded && !decoded.isRecycled) {
                decoded.recycle()
            }
            scaled
        } else {
            decoded
        }
    }

    /**
     * Contrast-enhances and desaturates bitmap to maximize OCR recognition
     * on thin font strokes, light font weights, serifs, faint print, and colored text.
     */
    fun enhanceBitmapForOcr(source: Bitmap): Bitmap {
        val enhanced = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Desaturate colors to eliminate chromatic aberration / colored background interference
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        // 2. Apply contrast boost (1.35x) with centered midpoint
        // This darkens thin and low-contrast font glyphs and cleans background paper
        val contrast = 1.35f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return enhanced
    }

    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source && !source.isRecycled) {
            source.recycle()
        }
        return rotated
    }

    /**
     * Preserves exact physical line breaks as detected by OCR.
     * Filters out notebook paper ruling lines and pen-lift specks.
     */
    fun buildExactLines(visionText: Text): String {
        if (visionText.textBlocks.isEmpty()) return ""
        return visionText.textBlocks.mapNotNull { block ->
            val validLines = block.lines
                .map { it.text.trim() }
                .filter { it.isNotEmpty() && !isRulingLineArtifact(it) && !isPenSpeck(it) }
            if (validLines.isEmpty()) null else validLines.joinToString("\n")
        }.joinToString("\n\n").trim()
    }

    /**
     * Smart Paragraph Flow:
     * - Heals broken hyphenations across line wraps
     * - Keeps bullet points and numbered points intact on separate lines
     * - Flows sentences across line wraps smoothly
     * - Cleans up notebook ruling lines and pen-lift noise
     */
    fun buildSmartFlow(visionText: Text): String {
        if (visionText.textBlocks.isEmpty()) return ""

        val blockStrings = mutableListOf<String>()

        for (block in visionText.textBlocks) {
            val blockBuilder = StringBuilder()
            val lines = block.lines

            for (line in lines) {
                val rawLine = line.text.trim()
                if (rawLine.isEmpty() || isRulingLineArtifact(rawLine) || isPenSpeck(rawLine)) continue

                val listItem = formatListItem(rawLine)
                val lineText = listItem ?: rawLine

                if (blockBuilder.isEmpty()) {
                    blockBuilder.append(lineText)
                    continue
                }

                val prevEndsWithHyphen = blockBuilder.endsWith("-")
                val isListItem = listItem != null
                val prevEndsWithPunctuation = blockBuilder.endsWith(".") ||
                        blockBuilder.endsWith("!") ||
                        blockBuilder.endsWith("?") ||
                        blockBuilder.endsWith(":") ||
                        blockBuilder.endsWith(";")

                if (prevEndsWithHyphen) {
                    blockBuilder.setLength(blockBuilder.length - 1)
                    blockBuilder.append(lineText)
                } else if (isListItem || prevEndsWithPunctuation) {
                    blockBuilder.append("\n").append(lineText)
                } else {
                    blockBuilder.append(" ").append(lineText)
                }
            }

            val result = blockBuilder.toString().trim()
            if (result.isNotEmpty()) {
                blockStrings.add(result)
            }
        }

        return blockStrings.joinToString("\n\n")
    }

    /**
     * Main entry point for document parsing from ML Kit Text.
     */
    fun buildDocument(visionText: Text, usePointwiseTable: Boolean = true): String {
        val rawLines = mutableListOf<OcrLine>()
        visionText.textBlocks.forEachIndexed { bIdx, block ->
            block.lines.forEach { line ->
                val box = line.boundingBox
                val text = line.text.trim()
                if (text.isNotEmpty() && box != null && !isRulingLineArtifact(text) && !isPenSpeck(text)) {
                    rawLines.add(
                        OcrLine(
                            text = text,
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            blockIndex = bIdx
                        )
                    )
                }
            }
        }
        return processOcrLines(rawLines, usePointwiseTable)
    }

    /**
     * Core layout parser that operates on OCR lines with 2D bounding boxes.
     * Exported for direct verification with unit tests and synthetic bounding boxes.
     */
    fun processOcrLines(rawLines: List<OcrLine>, usePointwiseTable: Boolean = true): String {
        if (rawLines.isEmpty()) return ""

        val sortedLines = rawLines.sortedWith(compareBy({ it.top }, { it.left }))

        // Detect if there are multi-column table regions in the lines
        val tableRegions = detectTableRegions(sortedLines)

        val outputSections = mutableListOf<String>()
        var idx = 0

        while (idx < sortedLines.size) {
            // Check if current line is inside a detected table region
            val currentTable = tableRegions.firstOrNull { sortedLines[idx] in it.lines }
            if (currentTable != null) {
                val tableOutput = processTableRegion(currentTable, usePointwiseTable)
                if (tableOutput.isNotBlank()) {
                    outputSections.add(tableOutput)
                }
                idx = sortedLines.indexOfLast { it in currentTable.lines } + 1
                continue
            }

            // Normal non-table line (Heading, Bullet, Key-Value, or Paragraph)
            val line = sortedLines[idx]
            val text = line.text.trim()
            if (text.isEmpty() || isRulingLineArtifact(text) || isPenSpeck(text)) {
                idx++
                continue
            }

            // Non-table Heading
            if (isHeading(text)) {
                val clean = text.replace(Regex("""^#{1,3}\s*"""), "").trim()
                outputSections.add("## $clean")
                idx++
                continue
            }

            // Non-table List item
            val listItem = formatListItem(text)
            if (listItem != null) {
                outputSections.add(listItem)
                idx++
                continue
            }

            // Non-table Key-Value pair (e.g. "Step score (1 to 5):" or "Status: Approved")
            val kvMatch = Regex("""^([A-Za-z0-9\s()/\-]{2,35}):\s*(.*)""").find(text)
            if (kvMatch != null && !text.startsWith("http")) {
                val key = kvMatch.groupValues[1].trim()
                val value = kvMatch.groupValues[2].trim()
                val formattedLabel = formatLabel(key)
                if (value.isNotEmpty()) {
                    outputSections.add("$formattedLabel $value")
                } else {
                    outputSections.add(formattedLabel)
                }
                idx++
                continue
            }

            // Flow regular paragraph
            val lastOutput = outputSections.lastOrNull()
            if (lastOutput != null && !lastOutput.startsWith("## ") && !lastOutput.startsWith("• ") &&
                !lastOutput.startsWith("|") && !lastOutput.startsWith("**") &&
                !lastOutput.matches(Regex("""^[0-9]+[.)].*"""))
            ) {
                val prevEndsHyphen = lastOutput.endsWith("-")
                val prevEndsPunctuation = lastOutput.endsWith(".") || lastOutput.endsWith("!") ||
                        lastOutput.endsWith("?") || lastOutput.endsWith(":") || lastOutput.endsWith(";")

                if (prevEndsHyphen) {
                    outputSections[outputSections.lastIndex] = lastOutput.dropLast(1) + text
                } else if (!prevEndsPunctuation) {
                    outputSections[outputSections.lastIndex] = "$lastOutput $text"
                } else {
                    outputSections.add(text)
                }
            } else {
                outputSections.add(text)
            }

            idx++
        }

        return sanitizeMarkdown(outputSections.joinToString("\n\n").trim())
    }

    // ─────────────────────────────────────────────────────────────
    //  Table Region Detection & 2D Column Clustering
    // ─────────────────────────────────────────────────────────────

    data class TableRegion(
        val lines: List<OcrLine>,
        val columnBounds: List<IntRange>, // X-intervals for columns [col0, col1, ...]
        val rowAnchorLines: List<OcrLine>  // Lines defining row starts (e.g. 8.1, 8.2)
    )

    private fun detectTableRegions(lines: List<OcrLine>): List<TableRegion> {
        if (lines.size < 2) return emptyList()

        // 1. Check for Row Anchors (e.g. 8.1, 8.2, 1., 2., A., etc.)
        val rowAnchorCandidates = lines.filter { isRowAnchor(it.text) }

        if (rowAnchorCandidates.size >= 2) {
            // We have explicit row anchors! Group lines belonging to this anchor group
            val firstAnchor = rowAnchorCandidates.first()
            val lastAnchor = rowAnchorCandidates.last()

            // Find all lines within vertical range of table, extending to include last row's cells
            val medianH = lines.map { it.height }.sorted().let { it[it.size / 2] }
            val tableTop = firstAnchor.top - medianH
            val lastAnchorIndex = rowAnchorCandidates.lastIndex
            val avgAnchorGap = if (lastAnchorIndex > 0) {
                (lastAnchor.top - firstAnchor.top) / lastAnchorIndex
            } else medianH * 4
            val tableBottom = lastAnchor.bottom + maxOf(avgAnchorGap, medianH * 4)

            val tableLines = lines.filter { line ->
                line.centerY >= tableTop && line.top <= tableBottom
            }

            val colBounds = computeColumnBounds(tableLines)
            if (colBounds.size >= 2) {
                return listOf(TableRegion(tableLines, colBounds, rowAnchorCandidates))
            }
        }

        // 2. Generic multi-column table detection by X-coordinate clustering
        val colBounds = computeColumnBounds(lines)
        if (colBounds.size >= 2) {
            // Check if there are lines in at least 2 distinct columns with overlapping Y
            var multiColRowCount = 0
            val sorted = lines.sortedBy { it.top }
            for (i in sorted.indices) {
                val l1 = sorted[i]
                val hasAdjacentCol = sorted.any { l2 ->
                    l1 != l2 && Math.abs(l1.centerY - l2.centerY) < maxOf(16, l1.height) &&
                            getColumnIdx(l1.left, colBounds) != getColumnIdx(l2.left, colBounds)
                }
                if (hasAdjacentCol) multiColRowCount++
            }

            if (multiColRowCount >= 3) {
                // Determine anchor lines: items in column 0
                val col0Lines = lines.filter { getColumnIdx(it.left, colBounds) == 0 }.sortedBy { it.top }
                return listOf(TableRegion(lines, colBounds, col0Lines))
            }
        }

        return emptyList()
    }

    private fun computeColumnBounds(lines: List<OcrLine>): List<IntRange> {
        if (lines.isEmpty()) return emptyList()

        // Cluster left-edges with a threshold
        val lefts = lines.map { it.left }.sorted()
        val clusters = mutableListOf<MutableList<Int>>()

        for (x in lefts) {
            val matching = clusters.firstOrNull { cluster ->
                val avg = cluster.average()
                Math.abs(x - avg) < 45 // 45px tolerance for column start
            }
            if (matching != null) {
                matching.add(x)
            } else {
                clusters.add(mutableListOf(x))
            }
        }

        // Keep clusters that have at least 2 lines or a significant presence
        val significantClusters = clusters.filter { it.size >= 2 }.sortedBy { it.average() }
        if (significantClusters.size < 2) return emptyList()

        // Build X-ranges for each column
        val bounds = mutableListOf<IntRange>()
        for (i in significantClusters.indices) {
            val minX = if (i == 0) 0 else ((significantClusters[i - 1].average() + significantClusters[i].average()) / 2).toInt()
            val maxX = if (i == significantClusters.lastIndex) Int.MAX_VALUE else ((significantClusters[i].average() + significantClusters[i + 1].average()) / 2).toInt()
            bounds.add(minX..maxX)
        }

        return bounds
    }

    private fun getColumnIdx(x: Int, bounds: List<IntRange>): Int {
        for (i in bounds.indices) {
            if (x in bounds[i]) return i
        }
        return bounds.lastIndex
    }

    private fun isRowAnchor(text: String): Boolean {
        val clean = text.trim()
        return clean.matches(Regex("""^#$""")) ||
                clean.matches(Regex("""^[0-9]+(\.[0-9]+)*[.)]?$""")) ||
                clean.matches(Regex("""^[A-Z][.)]$""")) ||
                clean.matches(Regex("""^(Step|Q|Item)\s*[0-9]+[.:]?$""", RegexOption.IGNORE_CASE))
    }

    // ─────────────────────────────────────────────────────────────
    //  Table Row Segmentation & Formatting
    // ─────────────────────────────────────────────────────────────

    private fun processTableRegion(table: TableRegion, usePointwiseTable: Boolean): String {
        val lines = table.lines
        val colBounds = table.columnBounds
        val anchors = table.rowAnchorLines.sortedBy { it.top }

        // Group lines into logical rows using Row Anchors
        val logicalRows = mutableListOf<MutableMap<Int, MutableList<OcrLine>>>()

        if (anchors.size >= 2) {
            for (aIdx in anchors.indices) {
                val anchor = anchors[aIdx]
                val nextAnchorTop = if (aIdx < anchors.lastIndex) anchors[aIdx + 1].top else Int.MAX_VALUE

                // Collect every line whose vertical center is between this anchor's top and the next anchor's top
                val rowLines = lines.filter { line ->
                    line.centerY >= anchor.top - 10 && line.centerY < nextAnchorTop - 10
                }

                val rowCells = mutableMapOf<Int, MutableList<OcrLine>>()
                for (line in rowLines) {
                    val col = getColumnIdx(line.left, colBounds)
                    rowCells.getOrPut(col) { mutableListOf() }.add(line)
                }

                // Sort lines inside each cell from top to bottom
                rowCells.values.forEach { cellLines -> cellLines.sortBy { it.top } }
                if (rowCells.isNotEmpty()) {
                    logicalRows.add(rowCells)
                }
            }
        } else {
            // Fallback for tables without explicit anchors: group by Column 0 items or Y-cluster
            val col0Lines = lines.filter { getColumnIdx(it.left, colBounds) == 0 }.sortedBy { it.top }
            if (col0Lines.size >= 2) {
                for (cIdx in col0Lines.indices) {
                    val c0 = col0Lines[cIdx]
                    val nextTop = if (cIdx < col0Lines.lastIndex) col0Lines[cIdx + 1].top else Int.MAX_VALUE
                    val rowLines = lines.filter { it.centerY >= c0.top - 10 && it.centerY < nextTop - 10 }
                    val rowCells = mutableMapOf<Int, MutableList<OcrLine>>()
                    for (line in rowLines) {
                        val col = getColumnIdx(line.left, colBounds)
                        rowCells.getOrPut(col) { mutableListOf() }.add(line)
                    }
                    rowCells.values.forEach { it.sortBy { l -> l.top } }
                    if (rowCells.isNotEmpty()) logicalRows.add(rowCells)
                }
            }
        }

        if (logicalRows.isEmpty()) return ""

        // Check if table contains checklists/options
        val isChecklistTable = logicalRows.any { row ->
            row.values.any { cell -> cell.any { isLikelyCheckboxText(it.text) } }
        }

        return if (isChecklistTable || usePointwiseTable) {
            formatPointwiseChecklistTable(logicalRows, isChecklistTable)
        } else {
            formatMarkdownGridTableFromCells(logicalRows, colBounds.size)
        }
    }

    /**
     * Formats segmented table rows into clean, readable entries without breaking questions
     * or detaching option lines into fake headings.
     */
    private fun formatPointwiseChecklistTable(
        rows: List<Map<Int, List<OcrLine>>>,
        isChecklistTable: Boolean
    ): String {
        val sb = StringBuilder()

        for (rIdx in rows.indices) {
            val row = rows[rIdx]

            // Check if this row is a Table Header (e.g. "#", "Ask", "Tick what they said", "Remarks")
            if (rIdx == 0 && isHeaderRow(row)) {
                val headerTitles = row.entries.sortedBy { it.key }.map { cleanSentence(it.value.map { l -> l.text }) }
                val validHeaders = headerTitles.filter { it.isNotEmpty() && !it.matches(Regex("""^#+$""")) }
                if (validHeaders.isNotEmpty()) {
                    sb.append("## ").append(validHeaders.joinToString(" • ")).append("\n\n")
                }
                continue
            }

            // Extract cells
            val col0Lines = row[0]?.map { it.text } ?: emptyList()
            val col1Lines = row[1]?.map { it.text } ?: emptyList()
            val col2Lines = row[2]?.map { it.text } ?: emptyList()
            val col3Lines = row[3]?.map { it.text } ?: emptyList()

            // 1. Column 0: Index / Anchor (e.g. "8.1")
            val indexText = cleanSentence(col0Lines).trim()

            // 2. Column 1: Question / Prose Cell (Unwrap lines cleanly!)
            val questionText = cleanQuestion(cleanSentence(col1Lines))

            // 3. Column 2: Options / Checklist Cell (Keep each option distinct & normalize checkboxes!)
            val optionsList = col2Lines.map { normalizeCheckboxOption(it) }.filter { it.isNotBlank() }

            // 4. Column 3: Remarks Cell
            val remarksText = cleanSentence(col3Lines).trim()

            // Assemble formatted row
            if (isChecklistTable) {
                // Heading/Question line
                if (indexText.isNotEmpty() && questionText.isNotEmpty()) {
                    sb.append("**$indexText** $questionText\n")
                } else if (questionText.isNotEmpty()) {
                    sb.append("**$questionText**\n")
                } else if (indexText.isNotEmpty()) {
                    sb.append("**$indexText**\n")
                }

                // Checklist options line (inline with bullet dots)
                if (optionsList.isNotEmpty()) {
                    val formattedOptions = optionsList.joinToString("  •  ")
                    sb.append("  ").append(formattedOptions).append("\n")
                }

                // Remarks line
                if (remarksText.isNotEmpty()) {
                    sb.append("  *(Remarks: $remarksText)*\n")
                }

                sb.append("\n")
            } else {
                // General data table pointwise format
                val primary = if (indexText.isNotEmpty() && questionText.isNotEmpty()) {
                    "**$indexText:** $questionText"
                } else if (indexText.isNotEmpty()) {
                    "**$indexText**"
                } else {
                    "**$questionText**"
                }

                val otherCols = mutableListOf<String>()
                if (optionsList.isNotEmpty()) otherCols.add(optionsList.joinToString(" / "))
                if (remarksText.isNotEmpty()) otherCols.add(remarksText)

                if (otherCols.isNotEmpty()) {
                    sb.append("• $primary — ").append(otherCols.joinToString(" — ")).append("\n")
                } else {
                    sb.append("• $primary\n")
                }
            }
        }

        return sb.toString().trimEnd()
    }

    private fun formatMarkdownGridTableFromCells(
        rows: List<Map<Int, List<OcrLine>>>,
        colCount: Int
    ): String {
        val sb = StringBuilder()
        var headerDone = false

        for (row in rows) {
            val cellTexts = (0 until colCount).map { col ->
                val lines = row[col]?.map { it.text } ?: emptyList()
                val text = cleanSentence(lines)
                normalizeCheckboxOption(text).replace("|", "\\|")
            }

            sb.append("| ").append(cellTexts.joinToString(" | ")).append(" |\n")

            if (!headerDone) {
                val sep = (0 until colCount).map { "---" }
                sb.append("| ").append(sep.joinToString(" | ")).append(" |\n")
                headerDone = true
            }
        }

        return sb.toString().trimEnd()
    }

    private fun isHeaderRow(row: Map<Int, List<OcrLine>>): Boolean {
        val allText = row.values.flatMap { cell -> cell.map { it.text.lowercase() } }.joinToString(" ")
        return allText.contains("ask") ||
                allText.contains("tick") ||
                allText.contains("remark") ||
                allText.contains("question") ||
                allText.contains("item") ||
                allText.contains("score") ||
                allText.contains("description")
    }

    // ─────────────────────────────────────────────────────────────
    //  Text Cleaning, Checkbox Normalization & Safe Markdown
    // ─────────────────────────────────────────────────────────────

    /**
     * Unwraps lines inside a prose cell into one clean continuous sentence.
     * Re-joins hyphenated words across line wraps and removes broken newlines.
     */
    fun cleanSentence(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()

        for (raw in lines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue

            if (sb.isEmpty()) {
                sb.append(trimmed)
            } else {
                if (sb.endsWith("-")) {
                    sb.setLength(sb.length - 1)
                    sb.append(trimmed)
                } else {
                    sb.append(" ").append(trimmed)
                }
            }
        }

        return sb.toString().trim()
    }

    /**
     * Cleans question text: removes double colons and fix artifacts like "?:".
     */
    fun cleanQuestion(text: String): String {
        var s = text.trim()
        if (s.endsWith("?:")) {
            s = s.dropLast(1) // Keep '?'
        } else if (s.endsWith("::")) {
            s = s.dropLast(1) // Keep single ':'
        }
        return s.trim()
    }

    /**
     * Normalizes OCR checkbox misreads to "☐ ":
     * - Standalone "D ", "O ", "0 ", "[] ", "[ ] "
     * - Glued "D" or "O" before TitleCase word ("DMe" -> "☐ Me", "OYes" -> "☐ Yes", "DEvasive" -> "☐ Evasive")
     * - Preserves standard English words like "Do", "Or", "On", "Done", "Order", "DESIGN".
     */
    fun normalizeCheckboxOption(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return ""

        // 1. Existing checkbox symbols
        s = s.replace(Regex("""^(\[\s*\]|\[\s*[xXvV✓]\s*\]|[\u2610\u25A1\u25A2\u25FB\u25FC])\s*"""), "☐ ")

        // 2. Standalone D, O, 0, o, [] at start of string
        s = s.replace(Regex("""^([DO0o]|\[\])\s+"""), "☐ ")

        // 3. Glued D or O before a TitleCase word at start of string
        // e.g. DMe -> ☐ Me, OThe -> ☐ The, DEvasive -> ☐ Evasive, DShared -> ☐ Shared
        s = s.replace(Regex("""^([DO])([A-Z][a-z]+)"""), "☐ $2")

        // 4. Glued or standalone D/O options within the line (e.g. "☐ Me  DShared  OThe agency")
        s = s.replace(Regex("""(?<=\s|^)([DO])([A-Z][a-z]+)"""), "☐ $2")
        s = s.replace(Regex("""(?<=\s|^)([DO0o]|\[\])(?=\s+[A-Z])"""), "☐")

        return s.trim()
    }

    /**
     * Returns true if text appears to contain checkbox options or OCR checkbox artifacts.
     */
    fun isLikelyCheckboxText(text: String): Boolean {
        val s = text.trim()
        return s.startsWith("☐") ||
                s.startsWith("[ ]") || s.startsWith("[]") ||
                s.matches(Regex("""^[DO0o]\s+.*""")) ||
                s.matches(Regex("""^[DO][A-Z][a-z]+.*""")) ||
                s.contains("RED FLAG", ignoreCase = true)
    }

    /**
     * Detects horizontal notebook ruling line artifacts (e.g. "----------", "______", "~~~~~~")
     * commonly misread from lined notebook paper.
     */
    fun isRulingLineArtifact(text: String): Boolean {
        val clean = text.replace(Regex("""\s+"""), "")
        return clean.length >= 3 && clean.all { it in "-_~=—." }
    }

    /**
     * Detects isolated pen-lift specks or resting-pen dots (e.g. solitary ".", "'", ",", "~").
     */
    fun isPenSpeck(text: String): Boolean {
        val clean = text.trim()
        return clean.length == 1 && clean[0] in ".,'`\"~^-_°·"
    }

    /**
     * Safe Label Formatter:
     * Prevents doubled colons "**Step score (1 to 5)::**" and unbalanced bold syntax.
     */
    fun formatLabel(label: String): String {
        val clean = label.trim().removeSurrounding("**").trim()
        return when {
            clean.endsWith("::") -> "**${clean.dropLast(1)}**"
            clean.endsWith("?:") -> "**${clean.dropLast(1)}**"
            clean.endsWith(":") || clean.endsWith("?") -> "**$clean**"
            else -> "**$clean:**"
        }
    }

    /**
     * Ensures every bold span in the markdown text is properly opened and closed.
     */
    fun sanitizeMarkdown(text: String): String {
        val lines = text.lines().map { line ->
            var l = line
            // Check for unbalanced **
            val count = l.windowed(2).count { it == "**" }
            if (count % 2 != 0) {
                // Unbalanced bolding detected: close it at the end
                l = if (l.endsWith("**")) l.dropLast(2) else "$l**"
            }
            l
        }
        return lines.joinToString("\n")
    }

    private fun isHeading(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 3 || clean.length > 55) return false
        val words = clean.split(Regex("""\s+"""))
        if (words.size > 8) return false

        // Not a bullet or numbered point
        if (clean.startsWith("•") || clean.startsWith("-") || clean.startsWith("*") ||
            clean.matches(Regex("""^[0-9]+[.)].*"""))
        ) return false

        // Not ending in comma, period, or semicolon
        if (clean.endsWith(",") || clean.endsWith(";")) return false

        val isAllUpper = clean.length >= 4 && clean.all { it.isUpperCase() || !it.isLetter() }
        val isTitleCase = words.size >= 2 && words.all { it.firstOrNull()?.isUpperCase() == true || it.length <= 3 }

        return isAllUpper || isTitleCase
    }

    private fun formatListItem(lineText: String): String? {
        val trimmed = lineText.trim()

        // Bullet points: •, ·, °, *, -, ~, —, o, O
        val bulletMatch = Regex("""^([•·°*~—\-_oO]|(?<!\w)[+])\s+(.*)""").find(trimmed)
        if (bulletMatch != null) {
            val content = bulletMatch.groupValues[2].trim()
            if (content.isNotEmpty()) return "• $content"
        }

        // Numbered / Lettered lists: 1., 1), 2., 2), A., A), etc.
        val numberMatch = Regex("""^([0-9]{1,3}|[a-zA-Z]|[ivxIVX]{1,4})[.)\]]\s+(.*)""").find(trimmed)
        if (numberMatch != null) {
            val num = numberMatch.groupValues[1]
            val content = numberMatch.groupValues[2].trim()
            if (content.isNotEmpty()) return "$num. $content"
        }

        // Checkboxes: [ ], [x], [X]
        val checkMatch = Regex("""^\[[\sxX]?\]\s+(.*)""").find(trimmed)
        if (checkMatch != null) {
            val content = checkMatch.groupValues[1].trim()
            return "• [ ] $content"
        }

        return null
    }
}
