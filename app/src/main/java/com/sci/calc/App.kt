package com.sci.calc

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.sci.calc.browser.AdBlocker
import com.sci.calc.security.AppLock

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLock.init(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )

        // Whenever the whole app goes to the background (home button, screen off,
        // switching apps) the hidden part locks again.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppLock.onAppBackgrounded()
            }
        })

        AdBlocker.init(this)
        com.sci.calc.download.DownloadRegistry.init(this)
    }

    companion object {
        const val CHANNEL_ID = "bg_tasks"
        lateinit var instance: App
            private set
    }
}
