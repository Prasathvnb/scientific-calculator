package com.sci.calc.calc

import kotlin.math.*

/**
 * Recursive-descent evaluator for calculator expressions.
 * Supports + − × ÷ ^ %, !, parentheses, implicit multiplication (2π, 3(4), 2sin30),
 * functions sin cos tan asin acos atan sinh cosh tanh ln log √ abs, constants π e.
 */
class ExpressionEvaluator(private val degrees: Boolean) {
    private lateinit var s: String
    private var pos = 0

    fun evaluate(expr: String): Double {
        s = expr.replace(" ", "")
            .replace('×', '*').replace('÷', '/').replace('−', '-')
            .replace("sin⁻¹", "asin").replace("cos⁻¹", "acos").replace("tan⁻¹", "atan")
        pos = 0
        if (s.isEmpty()) throw IllegalArgumentException("empty")
        val v = parseExpression()
        if (pos < s.length) throw IllegalArgumentException("Unexpected '${s[pos]}'")
        return v
    }

    private fun peek(): Char? = if (pos < s.length) s[pos] else null

    private fun eat(c: Char): Boolean {
        if (peek() == c) { pos++; return true }
        return false
    }

    private fun parseExpression(): Double {
        var v = parseTerm()
        while (true) {
            v = when {
                eat('+') -> v + parseTerm()
                eat('-') -> v - parseTerm()
                else -> return v
            }
        }
    }

    private fun parseTerm(): Double {
        var v = parseUnary()
        while (true) {
            v = when {
                eat('*') -> v * parseUnary()
                eat('/') -> {
                    val d = parseUnary()
                    if (d == 0.0) throw ArithmeticException("Can't divide by 0")
                    v / d
                }
                startsPrimary() -> v * parsePower() // implicit multiplication
                else -> return v
            }
        }
    }

    private fun startsPrimary(): Boolean {
        val c = peek() ?: return false
        return c == '(' || c == 'π' || c == '√' || c.isLetter() || c.isDigit() || c == '.'
    }

    private fun parseUnary(): Double {
        if (eat('-')) return -parseUnary()
        if (eat('+')) return parseUnary()
        return parsePower()
    }

    private fun parsePower(): Double {
        val base = parsePostfix()
        if (eat('^')) {
            val exp = parseUnary() // right associative
            return base.pow(exp)
        }
        return base
    }

    private fun parsePostfix(): Double {
        var v = parsePrimary()
        while (true) {
            v = when {
                eat('!') -> factorial(v)
                eat('%') -> v / 100.0
                eat('²') -> v * v
                else -> return v
            }
        }
    }

    private fun parsePrimary(): Double {
        val c = peek() ?: throw IllegalArgumentException("Incomplete")
        if (eat('(')) {
            val v = parseExpression()
            eat(')') // tolerate a missing closing bracket
            return v
        }
        if (c.isDigit() || c == '.') return parseNumber()
        if (eat('π')) return PI
        if (eat('√')) return sqrt(parsePostfix().also { if (it < 0) throw ArithmeticException("Invalid input") })

        val start = pos
        while (peek()?.isLetter() == true) pos++
        val name = s.substring(start, pos)
        if (name == "e") return E
        if (name.isEmpty()) throw IllegalArgumentException("Unexpected '$c'")
        val arg = parsePostfixArgument()
        return applyFunction(name, arg)
    }

    /** Function argument: either a bracket group or the next power-term (sin30, ln2). */
    private fun parsePostfixArgument(): Double = if (peek() == '-') { pos++; -parsePower() } else parsePower()

    private fun parseNumber(): Double {
        val start = pos
        while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
        if (peek() == 'E' && pos + 1 < s.length && (s[pos + 1].isDigit() || s[pos + 1] == '-')) {
            pos++
            if (peek() == '-') pos++
            while (peek()?.isDigit() == true) pos++
        }
        return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("Bad number")
    }

    private fun toRad(x: Double) = if (degrees) Math.toRadians(x) else x
    private fun fromRad(x: Double) = if (degrees) Math.toDegrees(x) else x

    private fun applyFunction(name: String, x: Double): Double = when (name) {
        "sin" -> cleanTrig(sin(toRad(x)))
        "cos" -> cleanTrig(cos(toRad(x)))
        "tan" -> {
            val c = cleanTrig(cos(toRad(x)))
            if (c == 0.0) throw ArithmeticException("Undefined") else cleanTrig(tan(toRad(x)))
        }
        "asin" -> if (x < -1 || x > 1) throw ArithmeticException("Invalid input") else fromRad(asin(x))
        "acos" -> if (x < -1 || x > 1) throw ArithmeticException("Invalid input") else fromRad(acos(x))
        "atan" -> fromRad(atan(x))
        "sinh" -> sinh(x)
        "cosh" -> cosh(x)
        "tanh" -> tanh(x)
        "ln" -> if (x <= 0) throw ArithmeticException("Invalid input") else ln(x)
        "log" -> if (x <= 0) throw ArithmeticException("Invalid input") else log10(x)
        "abs" -> abs(x)
        "sqrt" -> sqrt(x)
        else -> throw IllegalArgumentException("Unknown function $name")
    }

    private fun cleanTrig(v: Double) = if (abs(v) < 1e-12) 0.0 else v

    private fun factorial(x: Double): Double {
        if (x < 0 || x != floor(x)) throw ArithmeticException("Invalid input")
        if (x > 170) throw ArithmeticException("Too large")
        var r = 1.0
        for (i in 2..x.toInt()) r *= i
        return r
    }

    companion object {
        fun format(v: Double): String {
            if (v.isNaN()) return "Error"
            if (v.isInfinite()) return if (v > 0) "∞" else "-∞"
            val a = abs(v)
            if (a != 0.0 && (a >= 1e15 || a < 1e-9)) {
                return "%.10E".format(v).replace(Regex("0+E"), "E").replace(".E", "E")
            }
            val bd = java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros()
            return bd.toPlainString().replace('-', '−')
        }
    }
}
