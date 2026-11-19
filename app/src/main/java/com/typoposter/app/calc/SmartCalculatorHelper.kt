package com.typoposter.app.calc

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Result suggestions produced by the Inline Smart Calculator.
 */
sealed class CalcSuggestion {
    data class InlineMath(
        val expression: String,
        val formattedResult: String,
        val insertPos: Int,
        val insertText: String
    ) : CalcSuggestion()

    data class AutoSum(
        val count: Int,
        val formattedTotal: String,
        val replaceRange: TextRange,
        val insertText: String
    ) : CalcSuggestion()
}

/**
 * 100% offline Inline Smart Calculator for Typo Poster.
 *
 * Supports:
 * 1. Inline Math (=):
 *    - Arithmetic: +, -, *, /, ^, parentheses, decimals, commas in numbers.
 *    - Percentages: "18% of 4200 =", "4200 + 18% =", "4200 - 10% =", "50 * 20% =", etc.
 * 2. Auto-Sum (/sum):
 *    - Scans lines upward until a blank line or heading.
 *    - Extracts numbers from plain lines, currency amounts, bulleted lists, and tables.
 */
object SmartCalculatorHelper {

    private val mathContext = MathContext(16, RoundingMode.HALF_UP)

    // ─────────────────────────────────────────────────────────────
    //  Detection & Suggestion
    // ─────────────────────────────────────────────────────────────

    /**
     * Inspects the current cursor position in the editor and returns a calculation
     * suggestion if an inline math trigger ('=') or auto-sum trigger ('/sum') is present.
     */
    fun detectCalcSuggestion(value: TextFieldValue): CalcSuggestion? {
        val text = value.text
        if (text.isEmpty()) return null

        val cursor = value.selection.end.coerceIn(0, text.length)

        // Find active line boundaries around cursor
        val lineStart = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let {
            if (it == -1) 0 else it + 1
        }
        val lineEnd = text.indexOf('\n', cursor).let {
            if (it == -1) text.length else it
        }

        val fullLine = text.substring(lineStart, lineEnd)
        val lineUpToCursor = text.substring(lineStart, cursor)

        // 1. Check for Auto-Sum (/sum) on active line
        val sumSuggestion = checkAutoSum(text, lineStart, fullLine)
        if (sumSuggestion != null) return sumSuggestion

        // 2. Check for Inline Math (=) on active line
        val mathSuggestion = checkInlineMath(lineStart, fullLine, lineUpToCursor)
        if (mathSuggestion != null) return mathSuggestion

        // 3. Fallback: If cursor is on an empty line, check previous line
        // (common when user writes an expression like "10 + 20 =" and hits Enter)
        if (fullLine.isBlank() && lineStart > 0) {
            val prevLineEnd = lineStart - 1
            val prevLineStart = text.lastIndexOf('\n', (prevLineEnd - 1).coerceAtLeast(0)).let {
                if (it == -1) 0 else it + 1
            }
            val prevLine = text.substring(prevLineStart, prevLineEnd)
            val prevSum = checkAutoSum(text, prevLineStart, prevLine)
            if (prevSum != null) return prevSum
            val prevMath = checkInlineMath(prevLineStart, prevLine, prevLine)
            if (prevMath != null) return prevMath
        }

        return null
    }

    /**
     * Applies the calculator suggestion to the TextFieldValue, replacing the trigger
     * or inserting the formatted answer right after '='.
     */
    fun applySuggestion(value: TextFieldValue, suggestion: CalcSuggestion): TextFieldValue {
        val text = value.text
        return when (suggestion) {
            is CalcSuggestion.InlineMath -> {
                val insertPos = suggestion.insertPos.coerceIn(0, text.length)
                val newText = text.substring(0, insertPos) + suggestion.insertText + text.substring(insertPos)
                val newCursor = insertPos + suggestion.insertText.length
                TextFieldValue(
                    text = newText,
                    selection = TextRange(newCursor)
                )
            }
            is CalcSuggestion.AutoSum -> {
                val start = suggestion.replaceRange.min.coerceIn(0, text.length)
                val end = suggestion.replaceRange.max.coerceIn(0, text.length)
                val newText = text.substring(0, start) + suggestion.insertText + text.substring(end)
                val newCursor = start + suggestion.insertText.length
                TextFieldValue(
                    text = newText,
                    selection = TextRange(newCursor)
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Inline Math Trigger (=)
    // ─────────────────────────────────────────────────────────────

    private fun checkInlineMath(
        lineStart: Int,
        fullLine: String,
        lineUpToCursor: String
    ): CalcSuggestion.InlineMath? {
        val targetLine = if (fullLine.trimEnd().endsWith("=")) fullLine else lineUpToCursor
        val trimmed = targetLine.trimEnd()
        if (!trimmed.endsWith("=")) return null

        val lastEqualRel = targetLine.lastIndexOf('=')
        if (lastEqualRel == -1) return null

        val rawExpr = targetLine.substring(0, lastEqualRel).trim()
        if (rawExpr.isEmpty()) return null

        val cleanExpr = extractMathExpression(rawExpr)
        if (cleanExpr.isEmpty()) return null

        // Must contain at least one math operator (+, -, *, /, ^, %, or "of")
        if (!hasMathOperator(cleanExpr)) return null

        val result = evaluate(cleanExpr) ?: return null
        val formatted = formatResult(result)

        // Determine insertion position and text right after '='
        val hasTrailingSpace = targetLine.substring(lastEqualRel + 1).startsWith(" ")
        val insertPos = if (hasTrailingSpace) {
            lineStart + lastEqualRel + 2
        } else {
            lineStart + lastEqualRel + 1
        }
        val insertText = if (hasTrailingSpace) formatted else " $formatted"

        return CalcSuggestion.InlineMath(
            expression = cleanExpr,
            formattedResult = formatted,
            insertPos = insertPos,
            insertText = insertText
        )
    }

    private fun extractMathExpression(raw: String): String {
        var s = raw.trim()
        // Strip markdown bold tags (**bold**) and highlight tags ([h1]..[/h1])
        // Do NOT strip single '*' because '*' is multiplication!
        s = s.replace("**", "").replace(Regex("""\[/?h[1-5]\]"""), "").trim()

        // If line has a colon (e.g. "Groceries: 120 + 350"), take portion after colon
        if (s.contains(":")) {
            val afterColon = s.substringAfterLast(':').trim()
            if (afterColon.isNotEmpty() && hasMathOperator(afterColon)) {
                s = afterColon
            }
        }

        // Strip leading list bullets, checkboxes, or numbered list indicators (e.g. "• ", "- ", "1. ", "[ ] ")
        s = s.replace(Regex("""^([\s]*[•\-\~—]|\[\s*\]|[0-9]{1,3}[.)])\s+"""), "").trim()

        return s
    }

    private fun hasMathOperator(expr: String): Boolean {
        return expr.contains("+") ||
                expr.contains("-") ||
                expr.contains("−") ||
                expr.contains("*") ||
                expr.contains("×") ||
                expr.contains("x", ignoreCase = true) && expr.matches(Regex(""".*\d\s*[xX]\s*\d.*""")) ||
                expr.contains("/") ||
                expr.contains("÷") ||
                expr.contains("^") ||
                expr.contains("%") ||
                expr.contains(Regex("""\bof\b""", RegexOption.IGNORE_CASE))
    }

    // ─────────────────────────────────────────────────────────────
    //  Auto-Sum Trigger (/sum)
    // ─────────────────────────────────────────────────────────────

    private fun checkAutoSum(
        fullDocText: String,
        lineStart: Int,
        lineText: String
    ): CalcSuggestion.AutoSum? {
        val sumRegex = Regex("""(?i)/sum\b""")
        val match = sumRegex.find(lineText) ?: return null

        val sumRelStart = match.range.first
        val sumRelEnd = match.range.last + 1

        val replaceStart = lineStart + sumRelStart
        val replaceEnd = lineStart + sumRelEnd

        // Scan lines above lineStart
        val textBeforeLine = fullDocText.substring(0, lineStart)
        val linesAbove = textBeforeLine.lines().let {
            if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
        }

        val extractedNumbers = mutableListOf<BigDecimal>()

        for (i in linesAbove.indices.reversed()) {
            val line = linesAbove[i].trim()

            // Stop condition 1: Blank line
            if (line.isEmpty()) break

            // Stop condition 2: Markdown heading (# Heading, ## Heading)
            if (line.startsWith("#")) break

            val num = extractNumberFromLine(line)
            if (num != null) {
                extractedNumbers.add(num)
            }
        }

        if (extractedNumbers.isEmpty()) return null

        val total = extractedNumbers.fold(BigDecimal.ZERO) { acc, n -> acc.add(n, mathContext) }
        val formatted = formatResult(total)

        // If line is standalone "/sum" (possibly with whitespace), replace the full line cleanly
        val isStandalone = lineText.trim().equals("/sum", ignoreCase = true)
        val finalStart = if (isStandalone) lineStart else replaceStart
        val finalEnd = if (isStandalone) lineStart + lineText.length else replaceEnd

        return CalcSuggestion.AutoSum(
            count = extractedNumbers.size,
            formattedTotal = formatted,
            replaceRange = TextRange(finalStart, finalEnd),
            insertText = formatted
        )
    }

    /**
     * Extracts a numeric value from a line for auto-summing.
     */
    fun extractNumberFromLine(rawLine: String): BigDecimal? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null

        // Strip formatting tags
        line = line.replace("**", "").replace("*", "").replace(Regex("""\[/?h[1-5]\]"""), "").trim()

        // If line has an inline math result (e.g. "Coffee: 4 + 2 = 6"), take the value after '='
        if (line.contains("=")) {
            val afterEq = line.substringAfterLast('=').trim()
            val parsed = parseSingleNumber(afterEq)
            if (parsed != null) return parsed
        }

        // If line has a colon (e.g. "Rent: $1,200" or "• Flight: 450"), take portion after colon
        if (line.contains(":")) {
            val afterColon = line.substringAfterLast(':').trim()
            val parsed = parseSingleNumber(afterColon)
            if (parsed != null) return parsed
        }

        // Strip list bullets (e.g. "• 150", "- 200", "1. 300")
        val withoutBullet = line.replace(Regex("""^([\s]*[•\-\*~—]|\[\s*\]|[0-9]{1,3}[.)])\s+"""), "").trim()
        val parsedBullet = parseSingleNumber(withoutBullet)
        if (parsedBullet != null) return parsedBullet

        return parseSingleNumber(line)
    }

    private fun parseSingleNumber(text: String): BigDecimal? {
        // Match numbers, optionally with currency prefix ($1,200.50, €45, ₹14,000, -50.00)
        val regex = Regex("""[-+]?[0-9]{1,3}(?:,[0-9]{3})+(?:\.[0-9]+)?|[-+]?[0-9]+(?:\.[0-9]+)?""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return null

        // If there's a match preceded by a currency symbol, prioritize it
        for (m in matches) {
            val idx = m.range.first
            if (idx > 0 && text[idx - 1] in "$€£¥₹") {
                val clean = m.value.replace(",", "")
                return try { BigDecimal(clean) } catch (_: Exception) { null }
            }
        }

        // Otherwise pick the rightmost number on the line
        val lastMatch = matches.last()
        val clean = lastMatch.value.replace(",", "")
        return try { BigDecimal(clean) } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────
    //  Math Expression Parser & Evaluator
    // ─────────────────────────────────────────────────────────────

    /**
     * Evaluates a mathematical expression string offline.
     * Supports +, -, *, /, ^, parentheses, decimals, commas, and percentages.
     * Returns null silently if expression is malformed or causes division by zero.
     */
    fun evaluate(expression: String): BigDecimal? {
        return try {
            val tokens = tokenize(expression)
            if (tokens.isEmpty()) return null
            val parser = Parser(tokens)
            val ast = parser.parse() ?: return null
            val result = ast.eval(BigDecimal.ZERO)
            // Guard against NaN or infinite results
            result
        } catch (_: ArithmeticException) {
            null // Division by zero or overflow
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Formats a BigDecimal cleanly for display:
     * - Thousands commas: 17,500
     * - No trailing zeros for whole numbers: 4,956 (not 4,956.0)
     * - Maximum 6 decimal places with clean rounding
     */
    fun formatResult(value: BigDecimal): String {
        val stripped = if (value.scale() > 0) value.stripTrailingZeros() else value
        val scale = maxOf(0, minOf(6, stripped.scale()))
        val rounded = stripped.setScale(scale, RoundingMode.HALF_UP)

        val formatter = NumberFormat.getNumberInstance(Locale.US) as DecimalFormat
        formatter.isGroupingUsed = true
        formatter.groupingSize = 3
        formatter.maximumFractionDigits = 6
        formatter.minimumFractionDigits = 0
        return formatter.format(rounded)
    }

    // ─────────────────────────────────────────────────────────────
    //  Tokenization & AST
    // ─────────────────────────────────────────────────────────────

    sealed class Token {
        data class Number(val value: BigDecimal, val isPercent: Boolean) : Token()
        data class Op(val char: Char) : Token()
        object LParen : Token()
        object RParen : Token()
        object Of : Token()
    }

    private fun tokenize(raw: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var s = raw.trim()

        // Normalize unicode math symbols
        s = s.replace("×", "*")
            .replace("÷", "/")
            .replace("−", "-")

        var i = 0
        val len = s.length

        while (i < len) {
            val c = s[i]

            if (c.isWhitespace()) {
                i++
                continue
            }

            // Word "of" (case insensitive)
            if ((c == 'o' || c == 'O') && i + 1 < len && (s[i + 1] == 'f' || s[i + 1] == 'F') &&
                (i + 2 == len || !s[i + 2].isLetterOrDigit())
            ) {
                tokens.add(Token.Of)
                i += 2
                continue
            }

            // 'x' or 'X' as multiplication between numbers
            if ((c == 'x' || c == 'X') && tokens.isNotEmpty() && tokens.last() is Token.Number) {
                tokens.add(Token.Op('*'))
                i++
                continue
            }

            // Numbers (including commas e.g. 1,500.50 and % suffix e.g. 18%)
            if (c.isDigit() || (c == '.' && i + 1 < len && s[i + 1].isDigit())) {
                val numStart = i
                while (i < len && (s[i].isDigit() || s[i] == '.' || s[i] == ',')) {
                    i++
                }
                val rawNum = s.substring(numStart, i).replace(",", "")
                val bd = BigDecimal(rawNum)

                // Check for percentage suffix (%)
                var isPercent = false
                var peekIdx = i
                while (peekIdx < len && s[peekIdx].isWhitespace()) peekIdx++
                if (peekIdx < len && s[peekIdx] == '%') {
                    isPercent = true
                    i = peekIdx + 1
                }

                tokens.add(Token.Number(bd, isPercent))
                continue
            }

            when (c) {
                '+', '-', '*', '/', '^' -> tokens.add(Token.Op(c))
                '(' -> tokens.add(Token.LParen)
                ')' -> {
                    // Check if closing paren has a % suffix, e.g. (10 + 5)%
                    tokens.add(Token.RParen)
                    var peekIdx = i + 1
                    while (peekIdx < len && s[peekIdx].isWhitespace()) peekIdx++
                    if (peekIdx < len && s[peekIdx] == '%') {
                        // Insert * 0.01 equivalent
                        tokens.add(Token.Op('%'))
                        i = peekIdx
                    }
                }
                '%' -> tokens.add(Token.Op('%'))
                else -> {
                    // Unknown character, skip
                }
            }
            i++
        }

        return tokens
    }

    // ─────────────────────────────────────────────────────────────
    //  Recursive-Descent Parser
    // ─────────────────────────────────────────────────────────────

    sealed class AstNode {
        abstract fun eval(parentContext: BigDecimal): BigDecimal

        data class Num(val value: BigDecimal, val isPercent: Boolean) : AstNode() {
            override fun eval(parentContext: BigDecimal): BigDecimal {
                return if (isPercent) {
                    value.divide(BigDecimal(100), mathContext)
                } else {
                    value
                }
            }
        }

        data class Binary(val op: Char, val left: AstNode, val right: AstNode) : AstNode() {
            override fun eval(parentContext: BigDecimal): BigDecimal {
                val leftVal = left.eval(parentContext)

                // Percentage aware evaluation:
                // E.g., 4200 + 18% -> 4200 + (4200 * 0.18) = 4956
                // E.g., 4200 - 10% -> 4200 - (4200 * 0.10) = 3780
                // E.g., 50 * 20% -> 50 * 0.20 = 10
                if (right is Num && right.isPercent) {
                    val pct = right.value.divide(BigDecimal(100), mathContext)
                    return when (op) {
                        '+' -> leftVal.add(leftVal.multiply(pct, mathContext), mathContext)
                        '-' -> leftVal.subtract(leftVal.multiply(pct, mathContext), mathContext)
                        '*' -> leftVal.multiply(pct, mathContext)
                        '/' -> leftVal.divide(pct, mathContext)
                        else -> leftVal.add(leftVal.multiply(pct, mathContext), mathContext)
                    }
                }

                val rightVal = right.eval(leftVal)

                return when (op) {
                    '+' -> leftVal.add(rightVal, mathContext)
                    '-' -> leftVal.subtract(rightVal, mathContext)
                    '*' -> leftVal.multiply(rightVal, mathContext)
                    '/' -> {
                        if (rightVal.compareTo(BigDecimal.ZERO) == 0) {
                            throw ArithmeticException("Division by zero")
                        }
                        leftVal.divide(rightVal, mathContext)
                    }
                    '^' -> {
                        val intExp = try { rightVal.intValueExact() } catch (_: Exception) { null }
                        if (intExp != null && intExp in 0..999) {
                            leftVal.pow(intExp, mathContext)
                        } else {
                            BigDecimal(Math.pow(leftVal.toDouble(), rightVal.toDouble()), mathContext)
                        }
                    }
                    else -> leftVal
                }
            }
        }

        data class Unary(val op: Char, val child: AstNode) : AstNode() {
            override fun eval(parentContext: BigDecimal): BigDecimal {
                val v = child.eval(parentContext)
                return if (op == '-') v.negate(mathContext) else v
            }
        }
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): AstNode? {
            val node = parseAdditive() ?: return null
            if (pos < tokens.size) return null // Unconsumed tokens
            return node
        }

        private fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null

        private fun consume(): Token? = if (pos < tokens.size) tokens[pos++] else null

        // Additive: + and -
        private fun parseAdditive(): AstNode? {
            var left = parseMultiplicative() ?: return null

            while (true) {
                val token = peek()
                if (token is Token.Op && (token.char == '+' || token.char == '-')) {
                    consume()
                    val right = parseMultiplicative() ?: return null
                    left = AstNode.Binary(token.char, left, right)
                } else {
                    break
                }
            }
            return left
        }

        // Multiplicative: *, /, and "of"
        private fun parseMultiplicative(): AstNode? {
            var left = parsePower() ?: return null

            while (true) {
                val token = peek()
                if (token is Token.Op && (token.char == '*' || token.char == '/')) {
                    consume()
                    val right = parsePower() ?: return null
                    left = AstNode.Binary(token.char, left, right)
                } else if (token is Token.Of) {
                    consume()
                    val right = parsePower() ?: return null
                    // "X% of Y" -> X% * Y
                    left = AstNode.Binary('*', left, right)
                } else {
                    break
                }
            }
            return left
        }

        // Power: ^
        private fun parsePower(): AstNode? {
            var left = parseUnary() ?: return null

            while (true) {
                val token = peek()
                if (token is Token.Op && token.char == '^') {
                    consume()
                    val right = parseUnary() ?: return null
                    left = AstNode.Binary('^', left, right)
                } else {
                    break
                }
            }
            return left
        }

        // Unary: + or -
        private fun parseUnary(): AstNode? {
            val token = peek()
            if (token is Token.Op && (token.char == '+' || token.char == '-')) {
                consume()
                val child = parseUnary() ?: return null
                return AstNode.Unary(token.char, child)
            }
            return parsePrimary()
        }

        // Primary: Number or (Expression)
        private fun parsePrimary(): AstNode? {
            val token = consume() ?: return null

            return when (token) {
                is Token.Number -> AstNode.Num(token.value, token.isPercent)
                is Token.LParen -> {
                    val expr = parseAdditive() ?: return null
                    val closing = consume()
                    if (closing !is Token.RParen) return null
                    expr
                }
                else -> null
            }
        }
    }
}
