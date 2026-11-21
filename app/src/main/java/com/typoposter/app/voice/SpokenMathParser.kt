package com.typoposter.app.voice

import com.typoposter.app.calc.SmartCalculatorHelper
import java.math.BigDecimal

/**
 * 100% offline spoken voice math parser.
 * Triggered strictly when a math expression is followed by "equals to", "equals", "equal to",
 * "is equal to", "barabar", "बराबर", or an '=' symbol that is NOT already followed by a numeric answer.
 *
 * Normal speech without these triggers (e.g. "walk into the room", "5 times 4") is completely untouched.
 *
 * Examples:
 * - "20 * 39 =" -> "20 * 39 = 780" (idempotent, never doubled)
 * - "25 / 36 =" -> "25 / 36 = 0.694444" (idempotent, never doubled)
 * - "100 + 100 + 100 + 100 times 4 - 36 - 54 - 100 equals" -> "100 + 100 + 100 + 100 * 4 - 36 - 54 - 100 = 510"
 * - "5 times 4 equals" -> "5 * 4 = 20"
 * - "five times four equals" -> "5 * 4 = 20"
 * - "18 percent of 4200 equals" -> "18% of 4,200 = 756"
 * - "10% or 500 equals to" -> "10% of 500 = 50"
 */
object SpokenMathParser {

    private val WORD_VALUES = mapOf(
        "zero" to 0L, "shunya" to 0L,
        "one" to 1L, "ek" to 1L, "एक" to 1L,
        "two" to 2L, "do" to 2L, "दो" to 2L,
        "three" to 3L, "teen" to 3L, "तीन" to 3L,
        "four" to 4L, "char" to 4L, "chaar" to 4L, "चार" to 4L,
        "five" to 5L, "paanch" to 5L, "panch" to 5L, "पांच" to 5L,
        "six" to 6L, "chhe" to 6L, "che" to 6L, "छह" to 6L,
        "seven" to 7L, "saat" to 7L, "सात" to 7L,
        "eight" to 8L, "aath" to 8L, "आठ" to 8L,
        "nine" to 9L, "nau" to 9L, "नौ" to 9L,
        "ten" to 10L, "das" to 10L, "दस" to 10L,
        "eleven" to 11L, "gyarah" to 11L,
        "twelve" to 12L, "barah" to 12L,
        "thirteen" to 13L, "terah" to 13L,
        "fourteen" to 14L, "chaudah" to 14L,
        "fifteen" to 15L, "pandrah" to 15L,
        "sixteen" to 16L, "solah" to 16L,
        "seventeen" to 17L, "satrah" to 17L,
        "eighteen" to 18L, "atharah" to 18L,
        "nineteen" to 19L, "unnis" to 19L,
        "twenty" to 20L, "bees" to 20L,
        "thirty" to 30L, "tees" to 30L,
        "forty" to 40L, "chalis" to 40L,
        "fifty" to 50L, "pachas" to 50L, "pachaas" to 50L,
        "sixty" to 60L, "saath" to 60L,
        "seventy" to 70L, "sattar" to 70L,
        "eighty" to 80L, "assi" to 80L,
        "ninety" to 90L, "nabbe" to 90L,
        "hundred" to 100L, "sau" to 100L, "सौ" to 100L,
        "thousand" to 1000L, "hazaar" to 1000L, "hazar" to 1000L, "हज़ार" to 1000L, "हजार" to 1000L
    )

    private val WORD_KEYS_PATTERN =
        WORD_VALUES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val NUMBER_WORDS_SEQ_REGEX =
        Regex("""(?i)\b(?:$WORD_KEYS_PATTERN)(?:\s+(?:and\s+)?(?:$WORD_KEYS_PATTERN))*\b""")

    private const val DIGIT_NUM_FIRST = """[-+]?\d+(?:,\d{3})*(?:\.\d+)?"""
    private const val DIGIT_NUM_NEXT = """\d+(?:,\d{3})*(?:\.\d+)?"""
    private val WORD_NUM_PATTERN = """(?:$WORD_KEYS_PATTERN)(?:\s+(?:and\s+)?(?:$WORD_KEYS_PATTERN))*"""

    private val FIRST_NUM_PATTERN =
        """(?:$DIGIT_NUM_FIRST|$WORD_NUM_PATTERN)\s*(?:%|percent(?:age)?)?"""

    private val NEXT_NUM_PATTERN =
        """(?:$DIGIT_NUM_NEXT|$WORD_NUM_PATTERN)\s*(?:%|percent(?:age)?)?"""

    private const val SPOKEN_OP =
        """multiplied\s+by|into|times|guna|divided\s+by|bata|by|plus|minus|less|(?:percent(?:age)?|%)\s*(?:of|or|off)|of|or|off"""

    private const val SYMBOL_OP =
        """[+*×÷/\-]|(?<=\d|\s)[xX](?=\d|\s)"""

    private val OP_PATTERN =
        """(?:\s*(?:$SYMBOL_OP)\s*|\s+(?:$SPOKEN_OP)\s+)"""

    private const val TRIGGER_WORDS =
        """(?:\b(?:is\s+equal\s+to|is\s+equals\s+to|equals\s+to|equals|equal\s+to|barabar)\b|(?<=^|[\s,.;:!?।])बराबर(?=$|[\s,.;:!?।]))"""

    // Matches chained math expressions leading up to the trigger.
    // Idempotent guard: (?!\s*[-+]?\d) ensures an '=' or trigger already followed by an answer is NOT matched.
    private val MATH_TRIGGER_REGEX = Regex(
        """(?i)(?:^|(?<=[\s,.;:!?।]))($FIRST_NUM_PATTERN(?:$OP_PATTERN$NEXT_NUM_PATTERN)+)(?:\s+$TRIGGER_WORDS|\s*=)(?!\s*[-+]?\d)(?=$|[\s,.;:!?।])""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String): String {
        if (text.isBlank()) return text

        // Strict trigger guard: do nothing unless one of the equals/barabar trigger words or '=' is present
        val hasTrigger = text.contains("equal", ignoreCase = true) ||
                text.contains("barabar", ignoreCase = true) ||
                text.contains("बराबर") ||
                text.contains("=")
        if (!hasTrigger) return text

        return MATH_TRIGGER_REGEX.replace(text) { match ->
            val rawMathExpr = match.groupValues[1]
            val normalizedExpr = convertWordsToDigits(rawMathExpr)
            val evalExpr = buildEvalExpression(normalizedExpr)
            val result = SmartCalculatorHelper.evaluate(evalExpr) ?: return@replace match.value
            val formattedResult = SmartCalculatorHelper.formatResult(result)
            val displayExpr = buildDisplayExpression(normalizedExpr)
            "$displayExpr = $formattedResult"
        }
    }

    /**
     * Alias for parse() to support SpokenMathParser.convert() calls interchangeably.
     */
    fun convert(text: String): String = parse(text)

    /**
     * Converts spoken number words inside the math chunk to digits.
     */
    fun convertWordsToDigits(chunk: String): String {
        return NUMBER_WORDS_SEQ_REGEX.replace(chunk) { match ->
            val num = parseWordNumberSequence(match.value)
            num.toString()
        }
    }

    private fun parseWordNumberSequence(wordsStr: String): Long {
        val words = wordsStr.trim().lowercase().split(Regex("""\s+""")).filter { it != "and" }
        var total = 0L
        var current = 0L

        for (w in words) {
            val v = WORD_VALUES[w] ?: continue
            when {
                v == 1000000L || v == 100000L || v == 1000L -> {
                    total += (if (current == 0L) 1L else current) * v
                    current = 0L
                }
                v == 100L -> {
                    current = (if (current == 0L) 1L else current) * v
                }
                else -> {
                    current += v
                }
            }
        }
        return total + current
    }

    private fun buildEvalExpression(normalizedExpr: String): String {
        var s = normalizedExpr.trim()

        // 1. Map percentage with of/or/off to "% of":
        s = s.replace(Regex("""(?i)\b(?:percent(?:age)?)\s*(?:of|or|off)\b"""), " % of ")
            .replace(Regex("""(?i)%\s*(?:of|or|off)\b"""), " % of ")
            .replace(Regex("""(?i)\b(?:percent(?:age)?)\b"""), " % ")

        // 2. Map spoken operators and symbols to standard eval operators (*, /, +, -):
        s = s.replace(Regex("""(?i)\b(?:multiplied\s+by|into|times|guna)\b"""), " * ")
            .replace(Regex("""(?i)(?<=\d|\s)[xX](?=\d|\s)"""), " * ")
            .replace("×", " * ")
            .replace(Regex("""(?i)\b(?:divided\s+by|bata|by)\b"""), " / ")
            .replace("÷", " / ")
            .replace(Regex("""(?i)\bplus\b"""), " + ")
            .replace(Regex("""(?i)\b(?:minus|less)\b"""), " - ")

        // 3. Clean up spacing
        s = s.replace(Regex("""[ \t]+"""), " ").trim()
        return s
    }

    private fun buildDisplayExpression(normalizedExpr: String): String {
        var s = normalizedExpr.trim()

        // 1. Map percentage with of/or/off to "% of":
        s = s.replace(Regex("""(?i)\b(?:percent(?:age)?)\s*(?:of|or|off)\b"""), " % of ")
            .replace(Regex("""(?i)%\s*(?:of|or|off)\b"""), " % of ")
            .replace(Regex("""(?i)\b(?:percent(?:age)?)\b"""), " % ")

        // 2. Map spoken operators and unicode symbols cleanly to *, /, +, -
        s = s.replace(Regex("""(?i)\b(?:multiplied\s+by|into|times|guna)\b"""), " * ")
            .replace(Regex("""(?i)(?<=\d|\s)[xX](?=\d|\s)"""), " * ")
            .replace("×", " * ")
            .replace(Regex("""(?i)\b(?:divided\s+by|bata|by)\b"""), " / ")
            .replace("÷", " / ")
            .replace(Regex("""(?i)\bplus\b"""), " + ")
            .replace(Regex("""(?i)\b(?:minus|less)\b"""), " - ")

        // 3. Format individual numbers with standard thousands commas if 4+ digits
        val numberRegex = Regex("""\b\d+(?:,\d{3})*(?:\.\d+)?\b""")
        s = numberRegex.replace(s) { match ->
            val clean = match.value.replace(",", "")
            try {
                val bd = BigDecimal(clean)
                SmartCalculatorHelper.formatResult(bd)
            } catch (_: Exception) {
                match.value
            }
        }

        // 4. Clean up spacing around operators
        s = s.replace(Regex("""\s*\*\s*"""), " * ")
            .replace(Regex("""\s*/\s*"""), " / ")
            .replace(Regex("""\s*\+\s*"""), " + ")
            .replace(Regex("""(?<=\S)\s*-\s*"""), " - ")
            .replace(Regex("""(\d+)\s*%\s*of\b"""), "$1% of")
            .replace(Regex("""(\d+)\s*%\b"""), "$1%")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()

        return s
    }
}
