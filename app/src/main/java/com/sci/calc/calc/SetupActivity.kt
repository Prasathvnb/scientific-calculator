package com.sci.calc.calc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import com.sci.calc.browser.BrowserActivity
import com.sci.calc.security.AppLock

/** First-run (or reset) screen: choose the secret code and get the backup code. */
class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val reset = intent.getBooleanExtra(EXTRA_RESET, false)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = if (reset) "Set a new secret code" else "Set up your secret code"
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = "The app always opens as a calculator. To open the browser, type your secret code " +
                "on the calculator and press \"=\".\n\nUse 4 to 12 digits."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(16))
        })
        val code = EditText(this).apply {
            hint = "Secret code"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val confirm = EditText(this).apply {
            hint = "Repeat secret code"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(code); root.addView(confirm)

        val canBio = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        val bio = CheckBox(this).apply {
            text = "Also ask for fingerprint after the code"
            isEnabled = canBio
            isChecked = AppLock.biometricEnabled && canBio
        }
        root.addView(bio)

        root.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                val c = code.text.toString()
                when {
                    c.length !in 4..12 || !c.all { it.isDigit() } -> toast("Use 4 to 12 digits")
                    c != confirm.text.toString() -> toast("Codes don't match")
                    else -> {
                        AppLock.setCode(c)
                        AppLock.biometricEnabled = bio.isChecked
                        showBackup(AppLock.newBackupCode())
                    }
                }
            }
        })
        setContentView(root)
    }

    private fun showBackup(backup: String) {
        val pretty = backup.chunked(4).joinToString(" ")
        AlertDialog.Builder(this)
            .setTitle("Your backup code")
            .setMessage(
                "$pretty\n\nWrite this down and keep it safe. If you forget your secret code, " +
                    "type these 16 digits on the calculator and press \"=\" to set a new one.\n\n" +
                    "This code is shown only once."
            )
            .setCancelable(false)
            .setNeutralButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("backup", backup))
                toast("Copied"); finishSetup()
            }
            .setPositiveButton("I've saved it") { _, _ -> finishSetup() }
            .show()
    }

    private fun finishSetup() {
        AppLock.unlock()
        if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) { finish(); return }
        startActivity(Intent(this, BrowserActivity::class.java))
        finish()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_RESET = "reset"
        const val EXTRA_FROM_SETTINGS = "from_settings"
    }
}
