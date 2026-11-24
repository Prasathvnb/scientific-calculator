package com.sci.calc.security

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.sci.calc.calc.CalculatorActivity

/**
 * Base for every hidden screen. Blocks screenshots / recent-apps previews and
 * puts the calculator back on top whenever the app has been locked.
 */
abstract class SecureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        if (!AppLock.unlocked) {
            window.decorView.visibility = View.INVISIBLE
            startActivity(
                Intent(this, CalculatorActivity::class.java)
                    .putExtra(CalculatorActivity.EXTRA_LOCK_MODE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            overridePendingTransition(0, 0)
        } else {
            window.decorView.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        AppLock.allowOneExternalTrip = false
        if (AppLock.unlocked) window.decorView.visibility = View.VISIBLE
    }

    /** Call before launching a system activity (picker, share, camera…). */
    fun goingExternal() {
        AppLock.allowOneExternalTrip = true
    }
}
