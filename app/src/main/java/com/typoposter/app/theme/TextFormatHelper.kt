package com.typoposter.app.theme

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

enum class FormatType {
    BOLD,
    ITALIC,
    HIGHLIGHT_1, // Acid Lime (light & vibrant)
    HIGHLIGHT_2, // Sunset Coral (warm & punchy)
    HIGHLIGHT_3, // Cyber Sky (electric & fresh)
    HIGHLIGHT_4, // Deep Plum (rich & moody darker shade)
    HIGHLIGHT_5  // Forest Pine (deep emerald darker shade)
}

object TextFormatHelper {

    // ── 5 Curated Aesthetic Highlight Colors ─────────────────
    // 3 vibrant + 2 deep darker shades, balanced for dark and light themes

    object Highlights {
        // 1. Acid Lime
        val LimeLight = Color(0xFFD6F928)
        val LimeTextOnLight = Color(0xFF141A00)
        val LimeDark = Color(0xFF38450A)
        val LimeTextOnDark = Color(0xFFE8FF3B)
        val SwatchLime = Color(0xFFD6F928)

        // 2. Sunset Coral
        val CoralLight = Color(0xFFFF9E7D)
        val CoralTextOnLight = Color(0xFF2E0D07)
        val CoralDark = Color(0xFF4A1B14)
        val CoralTextOnDark = Color(0xFFFFB4A2)
        val SwatchCoral = Color(0xFFFF9E7D)

        // 3. Cyber Sky
        val SkyLight = Color(0xFF64E9EE)
        val SkyTextOnLight = Color(0xFF032228)
        val SkyDark = Color(0xFF0E3A42)
        val SkyTextOnDark = Color(0xFF7CE7E6)
        val SwatchSky = Color(0xFF64E9EE)

        // 4. Deep Plum (Darker color 1)
        val PlumLight = Color(0xFF801B53)
        val PlumTextOnLight = Color(0xFFFFFFFF)
        val PlumDark = Color(0xFF4D0E30)
        val PlumTextOnDark = Color(0xFFFFBDE2)
        val SwatchPlum = Color(0xFF801B53)

        // 5. Forest Pine (Darker color 2)
        val PineLight = Color(0xFF185338)
        val PineTextOnLight = Color(0xFFFFFFFF)
        val PineDark = Color(0xFF0C2B1D)
        val PineTextOnDark = Color(0xFFA1F0C9)
        val SwatchPine = Color(0xFF185338)
    }

    data class HighlightStyle(
        val background: Color,
        val text: Color,
    )

    fun getHighlightStyle(index: Int, isDark: Boolean): HighlightStyle = when (index) {
        1 -> HighlightStyle(
            background = if (isDark) Highlights.LimeDark else Highlights.LimeLight,
            text = if (isDark) Highlights.LimeTextOnDark else Highlights.LimeTextOnLight
        )
        2 -> HighlightStyle(
            background = if (isDark) Highlights.CoralDark else Highlights.CoralLight,
            text = if (isDark) Highlights.CoralTextOnDark else Highlights.CoralTextOnLight
        )
        3 -> HighlightStyle(
            background = if (isDark) Highlights.SkyDark else Highlights.SkyLight,
            text = if (isDark) Highlights.SkyTextOnDark else Highlights.SkyTextOnLight
        )
        4 -> HighlightStyle(
            background = if (isDark) Highlights.PlumDark else Highlights.PlumLight,
            text = if (isDark) Highlights.PlumTextOnDark else Highlights.PlumTextOnLight
        )
        else -> HighlightStyle(
            background = if (isDark) Highlights.PineDark else Highlights.PineLight,
            text = if (isDark) Highlights.PineTextOnDark else Highlights.PineTextOnLight
        )
    }

    /**
     * Parses raw text for the Compose editor:
     * Highlights words with pure background color without any heading symbols.
     */
    fun parseToAnnotatedString(rawText: String, theme: PosterTheme): AnnotatedString {
        val isDark = isColorDark(theme.background)
        val tagStyle = SpanStyle(
            color = theme.textSecondary.copy(alpha = 0.22f),
            fontSize = 10.sp
        )

        return buildAnnotatedString {
            append(rawText)

            // Apply all 5 highlights [h1]..[h5]
            for (i in 1..5) {
                val style = getHighlightStyle(i, isDark)
                applyTagPattern(
                    text = rawText,
                    regex = Regex("""\[h$i\](.*?)\[/h$i\]""", RegexOption.DOT_MATCHES_ALL),
                    openLen = 4,
                    closeLen = 5,
                    contentStyle = SpanStyle(
                        background = style.background,
                        color = style.text,
                    ),
                    tagStyle = tagStyle
                )
            }

            // Bold **...**
            applyTagPattern(
                text = rawText,
                regex = Regex("""\*\*(.*?)\*\*""", RegexOption.DOT_MATCHES_ALL),
                openLen = 2,
                closeLen = 2,
                contentStyle = SpanStyle(fontWeight = FontWeight.Bold),
                tagStyle = tagStyle
            )

            // Italic *...*
            applyTagPattern(
                text = rawText,
                regex = Regex("""(?<!\*)\*([^*]+?)\*(?!\*)""", RegexOption.DOT_MATCHES_ALL),
                openLen = 1,
                closeLen = 1,
                contentStyle = SpanStyle(fontStyle = FontStyle.Italic),
                tagStyle = tagStyle
            )
        }
    }

    private fun AnnotatedString.Builder.applyTagPattern(
        text: String,
        regex: Regex,
        openLen: Int,
        closeLen: Int,
        contentStyle: SpanStyle,
        tagStyle: SpanStyle
    ) {
        regex.findAll(text).forEach { match ->
            val fullStart = match.range.first
            val fullEnd = match.range.last + 1
            val contentStart = fullStart + openLen
            val contentEnd = fullEnd - closeLen

            addStyle(tagStyle, fullStart, contentStart)
            addStyle(tagStyle, contentEnd, fullEnd)

            if (contentStart < contentEnd) {
                addStyle(contentStyle, contentStart, contentEnd)
            }
        }
    }

    /**
     * Builds a clean Spannable for the Home Screen Widget:
     * - Strips tags completely
     * - NO heading symbols, NO bullets
     * - Applies true BackgroundColorSpan directly behind highlighted words
     * - Applies StyleSpan(BOLD) and StyleSpan(ITALIC)
     */
    fun buildSpannableForWidget(rawText: String, theme: PosterTheme): CharSequence {
        val isDark = isColorDark(theme.background)
        val sb = SpannableStringBuilder()

        // Tokenize text into segments
        // Matches [h1]..[/h1], [h2]..[/h2] etc., **..**, *..*
        val pattern = Regex("""(\[h[1-5]\](.*?)\[/h[1-5]\]|\*\*(.*?)\*\*|(?<!\*)\*([^*]+?)\*(?!\*))""", RegexOption.DOT_MATCHES_ALL)

        var lastIndex = 0
        pattern.findAll(rawText).forEach { match ->
            // Plain text before match
            if (match.range.first > lastIndex) {
                sb.append(rawText.substring(lastIndex, match.range.first))
            }

            val fullMatch = match.value
            val startSpan = sb.length

            when {
                fullMatch.startsWith("[h") -> {
                    val colorIndex = fullMatch[2].digitToIntOrNull() ?: 1
                    val content = match.groupValues[2]
                    sb.append(content)
                    val endSpan = sb.length

                    val style = getHighlightStyle(colorIndex, isDark)
                    sb.setSpan(
                        BackgroundColorSpan(style.background.toArgb()),
                        startSpan,
                        endSpan,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(
                        ForegroundColorSpan(style.text.toArgb()),
                        startSpan,
                        endSpan,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                fullMatch.startsWith("**") -> {
                    val content = match.groupValues[3]
                    sb.append(content)
                    val endSpan = sb.length
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        startSpan,
                        endSpan,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                fullMatch.startsWith("*") -> {
                    val content = match.groupValues[4]
                    sb.append(content)
                    val endSpan = sb.length
                    sb.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        startSpan,
                        endSpan,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < rawText.length) {
            sb.append(rawText.substring(lastIndex))
        }

        return sb
    }

    /**
     * Toggles formatting on selected text or the word at cursor.
     */
    fun toggleFormat(value: TextFieldValue, format: FormatType): TextFieldValue {
        val text = value.text
        var start = value.selection.min
        var end = value.selection.max

        if (start == end) {
            if (text.isEmpty()) {
                val (open, close) = getTags(format)
                return TextFieldValue(
                    text = open + close,
                    selection = androidx.compose.ui.text.TextRange(open.length)
                )
            }
            while (start > 0 && !text[start - 1].isWhitespace()) {
                start--
            }
            while (end < text.length && !text[end].isWhitespace()) {
                end++
            }
        }

        if (start >= end) {
            val (open, close) = getTags(format)
            val newText = text.substring(0, start) + open + close + text.substring(end)
            return TextFieldValue(
                text = newText,
                selection = androidx.compose.ui.text.TextRange(start + open.length)
            )
        }

        val selectedText = text.substring(start, end)
        val (open, close) = getTags(format)

        val newSelectedText: String
        val newSelectionEnd: Int

        if (selectedText.startsWith(open) && selectedText.endsWith(close) && selectedText.length >= open.length + close.length) {
            // Unwrap
            newSelectedText = selectedText.substring(open.length, selectedText.length - close.length)
            newSelectionEnd = start + newSelectedText.length
        } else {
            var cleaned = selectedText
            if (format in listOf(
                    FormatType.HIGHLIGHT_1,
                    FormatType.HIGHLIGHT_2,
                    FormatType.HIGHLIGHT_3,
                    FormatType.HIGHLIGHT_4,
                    FormatType.HIGHLIGHT_5
                )) {
                // Remove other highlights first
                cleaned = cleaned
                    .replace(Regex("""\[h[1-5]\]"""), "")
                    .replace(Regex("""\[/h[1-5]\]"""), "")
            }
            newSelectedText = open + cleaned + close
            newSelectionEnd = start + newSelectedText.length
        }

        val newText = text.substring(0, start) + newSelectedText + text.substring(end)
        return TextFieldValue(
            text = newText,
            selection = androidx.compose.ui.text.TextRange(start, newSelectionEnd)
        )
    }

    fun clearFormatting(value: TextFieldValue): TextFieldValue {
        val text = value.text
        val start = value.selection.min
        val end = value.selection.max
        if (start >= end) return value

        val selected = text.substring(start, end)
        val cleared = selected
            .replace("**", "")
            .replace("*", "")
            .replace(Regex("""\[/?h[1-5]\]"""), "")

        val newText = text.substring(0, start) + cleared + text.substring(end)
        return TextFieldValue(
            text = newText,
            selection = androidx.compose.ui.text.TextRange(start, start + cleared.length)
        )
    }

    private fun getTags(format: FormatType): Pair<String, String> = when (format) {
        FormatType.BOLD -> Pair("**", "**")
        FormatType.ITALIC -> Pair("*", "*")
        FormatType.HIGHLIGHT_1 -> Pair("[h1]", "[/h1]")
        FormatType.HIGHLIGHT_2 -> Pair("[h2]", "[/h2]")
        FormatType.HIGHLIGHT_3 -> Pair("[h3]", "[/h3]")
        FormatType.HIGHLIGHT_4 -> Pair("[h4]", "[/h4]")
        FormatType.HIGHLIGHT_5 -> Pair("[h5]", "[/h5]")
    }

    private fun isColorDark(color: Color): Boolean {
        val lum = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
        return lum < 0.5
    }
}

class RichTextVisualTransformation(private val theme: PosterTheme) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = TextFormatHelper.parseToAnnotatedString(text.text, theme)
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
