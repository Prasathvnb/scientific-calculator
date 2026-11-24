package com.sci.calc.calc

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.sci.calc.R
import com.sci.calc.browser.BrowserActivity
import com.sci.calc.security.AppLock

/**
 * A real, fully working scientific calculator. Typing the secret code and pressing
 * "=" opens the hidden browser. Typing the 16-digit backup code and "=" lets the
 * owner set a new secret code.
 */
class CalculatorActivity : AppCompatActivity() {

    private lateinit var exprView: TextView
    private lateinit var resultView: TextView
    private lateinit var modeView: TextView
    private val expr = StringBuilder()
    private var degrees = true
    private var justEvaluated = false
    private val history = ArrayList<String>()

    private val lockMode get() = intent.getBooleanExtra(EXTRA_LOCK_MODE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("calc", MODE_PRIVATE)
        degrees = prefs.getBoolean("deg", true)
        setContentView(buildUi())
        updateDisplay()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // In lock mode never reveal the screen underneath.
                if (lockMode) moveTaskToBack(true) else finish()
            }
        })

        if (!lockMode && !AppLock.isSetUp) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the calculator from the browser: wipe anything typed.
        if (!lockMode && AppLock.unlocked.not()) clearAll()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.calc_bg))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // Display
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(color(R.color.calc_display))
            }
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeView = TextView(this).apply {
            setTextColor(color(R.color.calc_key_op)); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(4), dp(12), dp(4))
            setOnClickListener { toggleMode() }
        }
        val historyBtn = TextView(this).apply {
            text = "History"; setTextColor(color(R.color.calc_text_dim)); textSize = 14f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { showHistory() }
        }
        topRow.addView(modeView)
        topRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topRow.addView(historyBtn)
        display.addView(topRow)

        exprView = TextView(this).apply {
            setTextColor(color(R.color.calc_text)); textSize = 34f
            gravity = Gravity.END; maxLines = 1
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(exprView)
        }
        display.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        display.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        resultView = TextView(this).apply {
            setTextColor(color(R.color.calc_text_dim)); textSize = 22f; gravity = Gravity.END; maxLines = 1
        }
        display.addView(resultView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(display, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.3f))

        val rows = listOf(
            listOf("sin" to K.FN, "cos" to K.FN, "tan" to K.FN, "ln" to K.FN, "log" to K.FN),
            listOf("sin⁻¹" to K.FN, "cos⁻¹" to K.FN, "tan⁻¹" to K.FN, "√" to K.FN, "x²" to K.FN),
            listOf("π" to K.FN, "e" to K.FN, "^" to K.FN, "(" to K.FN, ")" to K.FN),
            listOf("7" to K.NUM, "8" to K.NUM, "9" to K.NUM, "÷" to K.OP, "AC" to K.OP),
            listOf("4" to K.NUM, "5" to K.NUM, "6" to K.NUM, "×" to K.OP, "⌫" to K.OP),
            listOf("1" to K.NUM, "2" to K.NUM, "3" to K.NUM, "−" to K.OP, "%" to K.OP),
            listOf("0" to K.NUM, "." to K.NUM, "!" to K.FN, "+" to K.OP, "=" to K.EQ),
        )
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        rows.forEachIndexed { i, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (label, kind) -> line.addView(makeKey(label, kind)) }
            pad.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, if (i < 3) 0.8f else 1f))
        }
        root.addView(pad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 5f))
        return root
    }

    private enum class K { NUM, FN, OP, EQ }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun makeKey(label: String, kind: K): View {
        val bg = when (kind) {
            K.NUM -> R.color.calc_key
            K.FN -> R.color.calc_key_fn
            K.OP -> R.color.calc_key_op
            K.EQ -> R.color.calc_key_eq
        }
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (kind == K.OP) 0xFF1A1A1A.toInt() else color(R.color.calc_text))
            textSize = when (kind) { K.FN -> 16f; K.EQ -> 26f; else -> 22f }
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color(bg)) }
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey(label)
            }
            if (label == "⌫") setOnLongClickListener { clearAll(); true }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
        }
    }

    // ---------------------------------------------------------------- Logic

    private fun onKey(k: String) {
        val operators = setOf("+", "−", "×", "÷", "^", "%", "!")
        if (justEvaluated) {
            justEvaluated = false
            // Continue from the answer if an operator was pressed, otherwise start fresh.
            if (k !in operators && k != "x²" && k != "=" && k != "⌫") expr.clear()
        }
        when (k) {
            "AC" -> clearAll()
            "⌫" -> if (expr.isNotEmpty()) {
                val tokens = listOf("sin⁻¹(", "cos⁻¹(", "tan⁻¹(", "sin(", "cos(", "tan(", "log(", "ln(")
                val t = tokens.firstOrNull { expr.endsWith(it) }
                expr.setLength(expr.length - (t?.length ?: 1))
            }
            "=" -> onEquals()
            "sin", "cos", "tan", "ln", "log", "sin⁻¹", "cos⁻¹", "tan⁻¹" -> expr.append(k).append("(")
            "x²" -> expr.append("^2")
            "√" -> expr.append("√(")
            else -> expr.append(k)
        }
        updateDisplay()
    }

    private fun onEquals() {
        val typed = expr.toString()
        // Secret entry: only digits, checked against the stored hash.
        if (typed.isNotEmpty() && typed.all { it.isDigit() }) {
            if (typed.length in 4..12 && AppLock.checkCode(typed)) {
                clearAll(); openHidden(); return
            }
            if (typed.length == 16 && AppLock.checkBackupCode(typed)) {
                clearAll()
                startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_RESET, true))
                return
            }
        }
        try {
            val v = ExpressionEvaluator(degrees).evaluate(typed)
            val out = ExpressionEvaluator.format(v)
            history.add(0, "$typed = $out")
            if (history.size > 50) history.removeAt(history.size - 1)
            expr.clear(); expr.append(out)
            resultView.text = ""
            justEvaluated = true
        } catch (e: Exception) {
            resultView.text = e.message?.takeIf { it.length < 30 } ?: "Error"
        }
    }

    private fun updateDisplay() {
        modeView.text = if (degrees) "DEG" else "RAD"
        exprView.text = expr.toString().ifEmpty { "0" }
        (exprView.parent as? HorizontalScrollView)?.post {
            (exprView.parent as HorizontalScrollView).fullScroll(View.FOCUS_RIGHT)
        }
        if (!justEvaluated) {
            val preview = try {
                val t = expr.toString()
                if (t.isEmpty() || t.all { it.isDigit() || it == '.' }) "" else ExpressionEvaluator.format(ExpressionEvaluator(degrees).evaluate(t))
            } catch (e: Exception) { "" }
            resultView.text = preview
        }
    }

    private fun clearAll() {
        expr.clear(); justEvaluated = false
        if (::resultView.isInitialized) { resultView.text = ""; updateDisplay() }
    }

    private fun toggleMode() {
        degrees = !degrees
        getSharedPreferences("calc", MODE_PRIVATE).edit().putBoolean("deg", degrees).apply()
        updateDisplay()
    }

    private fun showHistory() {
        if (history.isEmpty()) {
            AlertDialog.Builder(this).setMessage("No history yet").setPositiveButton("OK", null).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("History")
            .setItems(history.toTypedArray()) { _, which ->
                val answer = history[which].substringAfterLast("= ")
                expr.clear(); expr.append(answer); justEvaluated = true; updateDisplay()
            }
            .setNegativeButton("Clear") { _, _ -> history.clear() }
            .show()
    }

    // ---------------------------------------------------------------- Unlock

    private fun openHidden() {
        if (AppLock.biometricEnabled &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        ) {
            val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = completeUnlock()
            })
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Confirm")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build()
            )
        } else completeUnlock()
    }

    private fun completeUnlock() {
        AppLock.unlock()
        if (lockMode) {
            finish()
            overridePendingTransition(0, 0)
        } else {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
    }

    companion object {
        const val EXTRA_LOCK_MODE = "lock_mode"
    }
}
