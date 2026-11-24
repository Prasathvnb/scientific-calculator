package com.sci.calc.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.sci.calc.data.Db
import com.sci.calc.security.SecureActivity
import com.sci.calc.vault.Storage

/**
 * Built-in media player (Media3 / ExoPlayer). Plays encrypted locked-folder files
 * without decrypting them to storage, normal files, and online streams (MP4, HLS, DASH).
 * Controls include seek, playback speed, subtitle/audio track selection and rotate.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : SecureActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(this).apply {
            setShowNextButton(false); setShowPreviousButton(false)
            setShowSubtitleButton(true)
            setFullscreenButtonClickListener { full ->
                requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            keepScreenOn = true
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(view)
        })
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        play(savedInstanceState?.getLong("pos") ?: 0L)
    }

    private fun play(startAt: Long) {
        val fileId = intent.getLongExtra(EXTRA_FILE_ID, -1)
        val p: ExoPlayer
        if (fileId >= 0) {
            val f = Db.get(this).file(fileId) ?: run { finish(); return }
            title = f.name
            val uri = Storage.uriFor(f.location)
            p = if (f.encrypted) {
                val factory = DataSource.Factory { EncryptedDataSource(this) }
                ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().also {
                    it.setMediaSource(ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri)))
                }
            } else {
                ExoPlayer.Builder(this).build().also { it.setMediaItem(MediaItem.fromUri(uri)) }
            }
        } else {
            val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
            val headers = HashMap<String, String>()
            intent.getStringExtra(EXTRA_REFERER)?.let { headers["Referer"] = it }
            android.webkit.CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)
            intent.getStringExtra(EXTRA_UA)?.let { http.setUserAgent(it) }
            val lower = url.lowercase()
            val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
                when {
                    lower.contains(".m3u8") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    lower.contains(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }.build()
            p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
            p.setMediaItem(item)
        }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Can't play: " + error.errorCodeName, Toast.LENGTH_LONG).show()
            }
        })
        view.player = p
        p.prepare()
        if (startAt > 0) p.seekTo(startAt)
        p.playWhenReady = true
        player = p
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_UA = "ua"
        const val EXTRA_TITLE = "title"
    }
}
