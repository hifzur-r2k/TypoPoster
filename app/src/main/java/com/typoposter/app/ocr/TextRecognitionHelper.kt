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
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OcrLanguage {
    ENGLISH,
    HINDI
}

data class OcrResult(
    val pointwiseText: String, // Point-wise table and structured document (Default)
    val gridTableText: String, // Markdown grid table and structured document
    val smartFlowText: String, // Clean paragraph flow with list points
    val exactText: String,     // Raw physical lines
    val cleanText: String = "",// Enhanced structured cleanup for receipts, notes, rates, lists
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

    private val englishRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private fun getRecognizer(language: OcrLanguage): TextRecognizer = when (language) {
        OcrLanguage.ENGLISH -> englishRecognizer
        OcrLanguage.HINDI -> devanagariRecognizer
    }

    suspend fun extractText(
        context: Context,
        uri: Uri,
        additionalRotationDegrees: Int = 0,
        language: OcrLanguage = OcrLanguage.ENGLISH
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

            val recognizer = getRecognizer(language)

            // 4. Dual-Pass OCR for Maximum Accuracy on Print, Handwriting & Colored Ink:
            // Pass 1: High-resolution RGB (optimal for human handwriting, blue/black ballpoint ink,
            // pencil shading, and natural stroke continuity).
            val primaryImage = InputImage.fromBitmap(orientedBitmap, 0)
            val primaryVisionText = processImageAsync(recognizer, primaryImage)
            val primaryWordCount = countRecognizedWords(primaryVisionText)

            // Pass 2: If primary pass extracted sparse text (e.g. low-contrast faint print or faint pencil),
            // evaluate a contrast-enhanced pass and select whichever produced higher yield
            var enhancedBitmap: Bitmap? = null
            val finalVisionText = if (primaryWordCount < 10) {
                enhancedBitmap = enhanceBitmapForOcr(orientedBitmap)
                val secondaryImage = InputImage.fromBitmap(enhancedBitmap, 0)
                val secondaryVisionText = processImageAsync(recognizer, secondaryImage)
                val secondaryWordCount = countRecognizedWords(secondaryVisionText)
                if (secondaryWordCount > primaryWordCount) secondaryVisionText else primaryVisionText
            } else {
                primaryVisionText
            }

            // Always recycle enhancedBitmap immediately after contrast-enhanced pass
            if (enhancedBitmap != null && !enhancedBitmap.isRecycled) {
                enhancedBitmap.recycle()
            }

            val pointwise = buildDocument(finalVisionText, usePointwiseTable = true)
            val gridTable = buildDocument(finalVisionText, usePointwiseTable = false)
            val smart = buildSmartFlow(finalVisionText)
            val exact = buildExactLines(finalVisionText)
            val clean = cleanScannedText(pointwise, smart, exact)

            // Generate a lightweight thumbnail bitmap (max ~240px on longest side) for 44dp preview
            val thumbnail = createThumbnailBitmap(orientedBitmap, maxDimension = 240)

            // Recycle full 4K orientedBitmap immediately to free memory
            if (orientedBitmap != thumbnail && !orientedBitmap.isRecycled) {
                orientedBitmap.recycle()
            }

            Result.success(
                OcrResult(
                    pointwiseText = pointwise,
                    gridTableText = gridTable,
                    smartFlowText = smart,
                    exactText = exact,
                    cleanText = clean,
                    bitmap = thumbnail,
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

    private fun createThumbnailBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val curMax = maxOf(source.width, source.height)
        if (curMax <= maxDimension) {
            return source.copy(source.config, false)
        }
        val scale = maxDimension.toFloat() / curMax
        val dstW = maxOf(1, (source.width * scale).toInt())
        val dstH = maxOf(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, dstW, dstH, true)
    }

    /**
     * Preserves exact physical line breaks as detected by OCR.
     * Filters out notebook paper ruling lines and pen-lift specks.
     */
    fun buildExactLines(visionText: Text): String {
        if (visionText.textBlocks.isEmpty()) return ""
        return visionText.textBlocks.mapNotNull { block ->
            val validLines = block.lines
                .map { normalizeMaskedAsterisks(it.text.trim()) }
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
                val rawLine = normalizeMaskedAsterisks(line.text.trim())
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
                val text = normalizeMaskedAsterisks(line.text.trim())
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
            val text = normalizeMaskedAsterisks(line.text.trim())
            if (text.isEmpty() || isRulingLineArtifact(text) || isPenSpeck(text)) {
                idx++
                continue
            }

            // Non-table Key-Value pair (e.g. "Payee Name: Deepak Kumar Padit" or "Status: Approved")
            // Evaluated BEFORE isHeading() to prevent receipt / bank fields from becoming ## headings.
            val kvMatch = Regex("""^([A-Za-z0-9][A-Za-z0-9\s()/\-•·]{1,39}):\s*(.*)""").find(text)
            val isTimeColon = if (kvMatch != null) {
                val key = kvMatch.groupValues[1].trim()
                val value = kvMatch.groupValues[2].trim()
                key.lastOrNull()?.isDigit() == true && Regex("""^\d{2}(?!\d)""").containsMatchIn(value)
            } else false

            if (kvMatch != null && !isTimeColon && !text.startsWith("http")) {
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

            // Non-table List item
            val listItem = formatListItem(text)
            if (listItem != null) {
                outputSections.add(listItem)
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

            // Flow regular paragraph
            val lastOutput = outputSections.lastOrNull()
            if (lastOutput != null && !lastOutput.startsWith("## ") && !lastOutput.startsWith("• ") &&
                !lastOutput.startsWith("|") && !isKeyValueLine(lastOutput) &&
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

        // Compact Spacing: use single newline \n between consecutive Key-Value lines, \n\n elsewhere
        val sb = StringBuilder()
        for (i in outputSections.indices) {
            val section = outputSections[i]
            if (i == 0) {
                sb.append(section)
            } else {
                val prevSection = outputSections[i - 1]
                val separator = if (isKeyValueLine(prevSection) && isKeyValueLine(section)) "\n" else "\n\n"
                sb.append(separator).append(section)
            }
        }

        return sanitizeMarkdown(sb.toString().trim())
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

    fun computeColumnBounds(lines: List<OcrLine>): List<IntRange> {
        if (lines.isEmpty()) return emptyList()

        val minLeft = lines.minOfOrNull { it.left } ?: 0
        val maxRight = lines.maxOfOrNull { it.right } ?: minLeft
        // Proportional column clustering tolerance: scales with resolution (e.g. 24px on 720p, 126px on 3600px 4K)
        val tolerance = maxOf(24, ((maxRight - minLeft) * 0.035f).toInt())

        // Cluster left-edges with proportional tolerance
        val lefts = lines.map { it.left }.sorted()
        val clusters = mutableListOf<MutableList<Int>>()

        for (x in lefts) {
            val matching = clusters.firstOrNull { cluster ->
                val avg = cluster.average()
                Math.abs(x - avg) < tolerance
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
            val sortedCols = row.keys.sorted()
            if (sortedCols.isEmpty()) continue

            // Check if this row is a Table Header (e.g. "#", "Ask", "Tick what they said", "Remarks")
            if (rIdx == 0 && isHeaderRow(row)) {
                val headerTitles = sortedCols.map { colIdx -> cleanSentence(row[colIdx]?.map { l -> l.text } ?: emptyList()) }
                val validHeaders = headerTitles.filter { it.isNotEmpty() && !it.matches(Regex("""^#+$""")) }
                if (validHeaders.isNotEmpty()) {
                    sb.append("## ").append(validHeaders.joinToString(" • ")).append("\n\n")
                }
                continue
            }

            val col0Lines = row[0]?.map { it.text } ?: emptyList()
            val col1Lines = row[1]?.map { it.text } ?: emptyList()

            // 1. Column 0: Index / Anchor (e.g. "8.1" or "#")
            val indexText = cleanSentence(col0Lines).trim()

            // 2. Column 1: Question / Item Prose Cell
            val questionOrDescText = cleanQuestion(cleanSentence(col1Lines))

            // Assemble formatted row dynamically across all present columns (including 5+ columns)
            if (isChecklistTable) {
                // Checklist table: Column 2 contains options / checkboxes
                val col2Lines = row[2]?.map { it.text } ?: emptyList()
                val optionsList = col2Lines.map { normalizeCheckboxOption(it) }.filter { it.isNotBlank() }

                // Dynamic extra columns (columns 3, 4, 5, etc.)
                val extraColTexts = sortedCols.filter { it >= 3 }
                    .map { colIdx -> cleanSentence(row[colIdx]?.map { it.text } ?: emptyList()).trim() }
                    .filter { it.isNotEmpty() }

                // Heading/Question line
                if (indexText.isNotEmpty() && questionOrDescText.isNotEmpty()) {
                    sb.append("**$indexText** $questionOrDescText\n")
                } else if (questionOrDescText.isNotEmpty()) {
                    sb.append("**$questionOrDescText**\n")
                } else if (indexText.isNotEmpty()) {
                    sb.append("**$indexText**\n")
                }

                // Checklist options line (inline with bullet dots)
                if (optionsList.isNotEmpty()) {
                    val formattedOptions = optionsList.joinToString("  •  ")
                    sb.append("  ").append(formattedOptions).append("\n")
                }

                // Extra columns line (remarks, scores, etc.)
                if (extraColTexts.isNotEmpty()) {
                    sb.append("  *(").append(extraColTexts.joinToString(" • ")).append(")*\n")
                }

                sb.append("\n")
            } else {
                // General data table pointwise format: preserves ALL 5+ columns without truncation
                val primary = if (indexText.isNotEmpty() && questionOrDescText.isNotEmpty()) {
                    "**$indexText:** $questionOrDescText"
                } else if (indexText.isNotEmpty()) {
                    "**$indexText**"
                } else {
                    "**$questionOrDescText**"
                }

                // Collect all columns from index 2 onwards dynamically (col 2, col 3, col 4, col 5, etc.)
                val otherCols = sortedCols.filter { it >= 2 }
                    .map { colIdx -> cleanSentence(row[colIdx]?.map { it.text } ?: emptyList()).trim() }
                    .filter { it.isNotEmpty() }

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
            val trimmed = normalizeMaskedAsterisks(raw.trim())
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

    /**
     * Normalizes runs of 2 or more asterisks used for masking (e.g. "****", "***7")
     * into bullet dots ("••••", "•••7") so markdown parsers do not treat them as bold/italic formatting.
     */
    fun normalizeMaskedAsterisks(text: String): String {
        return Regex("""\*{2,}""").replace(text) { match ->
            "•".repeat(match.value.length)
        }
    }

    /**
     * Identifies a formatted single-line Key-Value pair (e.g. "**Amount:** ₹4,500.00").
     */
    fun isKeyValueLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains("\n")) return false
        return trimmed.startsWith("**") && (trimmed.contains(":**") || trimmed.contains("?**") || trimmed.contains("::**"))
    }

    private fun isHeading(text: String): Boolean {
        val clean = text.trim()
        if (clean.length < 3 || clean.length > 55) return false

        // Headings never contain colons, currency symbols, or decimal numbers
        if (clean.contains(":") || clean.contains("₹") ||
            clean.contains("Rs", ignoreCase = true) || clean.contains("INR", ignoreCase = true)
        ) {
            return false
        }

        // Numbers with decimals (e.g. 100.50, 4,200.00)
        if (clean.contains(Regex("""\d+\.\d+"""))) {
            return false
        }

        // Common bank / receipt fields (e.g. Ref No, Txn ID, UTR, A/c, IFSC, IMPS, NEFT, RTGS, UPI)
        if (clean.contains(Regex("""\b(UTR|IFSC|IMPS|NEFT|RTGS|UPI|Txn|Ref|A/c|Acc)\b""", RegexOption.IGNORE_CASE))) {
            return false
        }

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

    // ─────────────────────────────────────────────────────────────
    //  OcrFormatMode.CLEAN: Context-Aware Document Cleaner
    // ─────────────────────────────────────────────────────────────

    /**
     * Non-destructive, context-aware cleanup for diverse real-world documents:
     * - Payment / Receipt / Bank Slips: Normalizes labels to clean single-line entries separated by single \n,
     *   and strips bottom notification bar actions (Reply, Mark as read, Mute).
     * - Lists, Quantities & Rates: Preserves numbered/bulleted items and keeps Item — Value pairs on a single line
     *   without turning list items into ## headings.
     * - Handwritten / Hinglish / Mixed-Script: Collapses accidental mid-sentence line wraps, cleans punctuation spacing,
     *   and keeps Devanagari + Latin text intact.
     * - IDs, Codes & Numbers: Prevents breaking short alphanumeric codes/IDs or currency values across lines.
     */
    fun cleanScannedText(
        pointwiseText: String,
        smartFlowText: String = "",
        exactText: String = ""
    ): String {
        val rawSource = when {
            pointwiseText.isNotBlank() -> pointwiseText
            smartFlowText.isNotBlank() -> smartFlowText
            else -> exactText
        }.trim()

        if (rawSource.isEmpty()) return ""

        val rawLines = rawSource.lines().map { it.trim() }

        // 1. Strip trailing notification bar actions (e.g. Reply, Mark as read, Mute)
        val filteredLines = mutableListOf<String>()
        var i = 0
        while (i < rawLines.size) {
            val line = rawLines[i]
            if (isNotificationActionLine(line)) {
                i++
                continue
            }
            filteredLines.add(line)
            i++
        }

        // Trim trailing empty lines
        while (filteredLines.isNotEmpty() && filteredLines.last().isBlank()) {
            filteredLines.removeAt(filteredLines.lastIndex)
        }

        if (filteredLines.isEmpty()) return ""

        // 2. Pre-process lines: normalize masked asterisks, fix false headings, normalize receipt fields
        val preprocessed = mutableListOf<String>()
        for (line in filteredLines) {
            if (line.isBlank()) {
                preprocessed.add("")
                continue
            }

            var l = normalizeMaskedAsterisks(line)
            l = fixFalseHeading(l)
            l = normalizeReceiptField(l)
            preprocessed.add(l)
        }

        // 3. Merge hanging lines and accidental mid-sentence wraps
        val mergedLines = mutableListOf<String>()
        var idx = 0
        while (idx < preprocessed.size) {
            var current = preprocessed[idx]

            // If empty line, preserve as paragraph separator
            if (current.isBlank()) {
                if (mergedLines.isNotEmpty() && mergedLines.last().isNotBlank()) {
                    mergedLines.add("")
                }
                idx++
                continue
            }

            // Lookahead for hanging values or mid-sentence wraps
            while (idx + 1 < preprocessed.size) {
                val next = preprocessed[idx + 1]
                if (next.isBlank()) break

                // Case A: Hanging currency, code, or rate following a colon or label
                val currentEndsInColon = current.endsWith(":") || current.endsWith(":**")
                val nextIsCurrencyOrCode = next.matches(Regex("""^(₹|Rs\.?|INR|\$|€|£)\s*[0-9A-Za-z,./•]+$""")) ||
                        next.matches(Regex("""^[0-9A-Za-z\-_./•]{3,25}$""")) ||
                        next.startsWith("— ") || next.startsWith("- ")
                val currentIsItemRatePair = (current.startsWith("• ") || current.matches(Regex("""^[0-9]+[.)]\s+.*"""))) &&
                        (next.startsWith("—") || next.matches(Regex("""^(₹|Rs\.?|INR)\s*[0-9]+.*""")))

                if ((currentEndsInColon && nextIsCurrencyOrCode) || currentIsItemRatePair) {
                    current = if (current.endsWith("-") && !current.endsWith(" —") && !current.endsWith(" -")) {
                        current.dropLast(1) + next
                    } else {
                        "$current $next"
                    }
                    idx++
                    continue
                }

                // Case B: Accidental mid-sentence wrap in paragraph/note
                // Neither current nor next is a heading, list item, table row, or key-value line
                val currentIsSpecial = current.startsWith("## ") || current.startsWith("• ") ||
                        current.matches(Regex("""^[0-9]+[.)]\s+.*""")) || current.startsWith("|") ||
                        isKeyValueLine(current)
                val nextIsSpecial = next.startsWith("## ") || next.startsWith("• ") ||
                        next.matches(Regex("""^[0-9]+[.)]\s+.*""")) || next.startsWith("|") ||
                        isKeyValueLine(next) || isKnownReceiptField(next)

                val currentEndsSentence = current.endsWith(".") || current.endsWith("!") ||
                        current.endsWith("?") || current.endsWith(":") || current.endsWith(";") ||
                        current.endsWith("।") || current.endsWith("॥")

                if (!currentIsSpecial && !nextIsSpecial && !currentEndsSentence) {
                    current = if (current.endsWith("-")) {
                        current.dropLast(1) + next
                    } else {
                        "$current $next"
                    }
                    idx++
                    continue
                }

                break
            }

            mergedLines.add(current)
            idx++
        }

        // 4. Punctuation and spacing cleanup on all lines
        val cleanedLines = mergedLines.map { line ->
            if (line.isBlank()) "" else cleanPunctuationAndSpacing(line)
        }

        // 5. Build final output with compact single \n between consecutive Key-Value lines, \n\n elsewhere
        val finalSections = mutableListOf<String>()
        for (line in cleanedLines) {
            if (line.isBlank()) continue
            finalSections.add(line)
        }

        val sb = StringBuilder()
        for (j in finalSections.indices) {
            val section = finalSections[j]
            if (j == 0) {
                sb.append(section)
            } else {
                val prev = finalSections[j - 1]
                val separator = if (isKeyValueLine(prev) && isKeyValueLine(section)) "\n" else "\n\n"
                sb.append(separator).append(section)
            }
        }

        return sanitizeMarkdown(sb.toString().trim())
    }

    private fun isNotificationActionLine(line: String): Boolean {
        val clean = line.trim()
            .replace(Regex("""^[•·°*~—\-_|]+\s*"""), "")
            .replace(Regex("""[•·|]"""), " ")
            .trim()
        if (clean.isEmpty()) return false
        val lower = clean.lowercase()
        val fullActionMatches = listOf(
            "reply", "mark as read", "mute", "dismiss", "copy code",
            "remind me later", "reply mark as read mute", "reply mark as read",
            "mark as read mute"
        )
        if (fullActionMatches.any { lower == it }) return true

        val tokens = lower.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val validTokens = setOf("reply", "mark", "as", "read", "mute", "dismiss", "copy", "code", "remind", "me", "later")
        return tokens.isNotEmpty() && tokens.all { it in validTokens }
    }

    private val receiptFieldLabels = listOf(
        "Payee Name", "Beneficiary Name", "Account Details", "Account Number",
        "A/c Number", "A/c No", "Transfer Mode", "Payment Mode", "Transfer Date",
        "Payment Date", "Transaction Date", "Reference ID", "Reference No", "Ref No",
        "Transaction ID", "Txn ID", "UTR", "UTR No", "UTR Number", "Status",
        "Payment Status", "Transaction Status", "Total Amount", "Amount", "To"
    )

    private fun isKnownReceiptField(line: String): Boolean {
        val clean = line.removePrefix("## ").removePrefix("• ").trim()
        return receiptFieldLabels.any { label ->
            clean.startsWith(label, ignoreCase = true) ||
                    clean.startsWith("**$label", ignoreCase = true)
        }
    }

    private fun normalizeReceiptField(line: String): String {
        for (label in receiptFieldLabels) {
            val regex = Regex(
                """^(?:##\s*)?(?:\*\*)?${Regex.escape(label)}(?::\*\*|\*\*:|\*\*:?|:)?\s*[:\-—]?\s*(.*)$""",
                RegexOption.IGNORE_CASE
            )
            val match = regex.find(line)
            if (match != null) {
                val rawValue = match.groupValues[1].trim()
                if (rawValue.isNotEmpty()) {
                    val valClean = rawValue.removeSurrounding("**").trim()
                    if (label.equals("To", ignoreCase = true)) {
                        if (!line.startsWith("To:", ignoreCase = true) && !line.startsWith("**To:", ignoreCase = true) &&
                            !line.startsWith("To -", ignoreCase = true)
                        ) {
                            continue
                        }
                    }
                    return "**$label:** $valClean"
                }
            }
        }
        return line
    }

    private fun fixFalseHeading(line: String): String {
        if (!line.startsWith("## ")) return line
        val content = line.removePrefix("## ").trim()
        if (content.startsWith("•") || content.startsWith("-") || content.startsWith("*") ||
            content.matches(Regex("""^[0-9]+[.)\]]\s+.*"""))
        ) {
            return content
        }
        if (content.contains("—") && (content.contains("₹") || content.contains("Rs", ignoreCase = true) || content.contains(Regex("""\d+""")))) {
            return "• $content"
        }
        if (content.contains(":") && !content.contains("http")) {
            return content
        }
        return line
    }

    private fun cleanPunctuationAndSpacing(line: String): String {
        var s = line
        // Fix spacing before punctuation: commas, periods, semicolons, question/exclamation marks, danda
        s = s.replace(Regex("""\s+([,.;?!।॥])"""), "$1")

        // Fix spacing before colon (avoiding time like 8:51)
        s = s.replace(Regex("""\s+:(?!\d{2}\b)"""), ":")

        // Ensure space after colon in key-values: "**Label:**Value" -> "**Label:** Value"
        s = s.replace(Regex("""(\*\*[^*]+:\*\*)([^\s])"""), "$1 $2")

        // Fix currency spacing: "₹ 5000" -> "₹5000", "Rs . 500" -> "Rs. 500"
        s = s.replace(Regex("""₹\s+(\d)"""), "₹$1")
        s = s.replace(Regex("""Rs\s*\.\s*(\d)"""), "Rs. $1")
        s = s.replace(Regex("""Rs\s+(\d)"""), "Rs $1")

        // Collapse multiple horizontal spaces
        s = s.replace(Regex("""[ \t]{2,}"""), " ")

        return s.trim()
    }
}
