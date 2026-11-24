package com.sci.calc.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import android.text.format.Formatter
import com.sci.calc.download.HlsQuality
import kotlin.concurrent.thread
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sci.calc.R
import com.sci.calc.data.Db
import com.sci.calc.data.Entry
import com.sci.calc.data.Prefs
import com.sci.calc.download.DownloadRegistry
import com.sci.calc.download.DownloadService
import com.sci.calc.download.DownloadsActivity
import com.sci.calc.player.PlayerActivity
import com.sci.calc.security.AppLock
import com.sci.calc.security.SecureActivity
import com.sci.calc.settings.SettingsActivity
import com.sci.calc.vault.VaultActivity
import java.io.ByteArrayInputStream
import java.util.Locale

class BrowserActivity : SecureActivity() {

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var secureIcon: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var tabsBtn: TextView
    private lateinit var fabVideo: ExtendedFloatingActionButton
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private lateinit var findCount: TextView
    private lateinit var fullscreen: FrameLayout

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    private val qrScan = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { navigate(it) }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        pendingPermission?.let { req ->
            val granted = req.resources.filter { r ->
                when (r) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> res[Manifest.permission.CAMERA] == true || has(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> res[Manifest.permission.RECORD_AUDIO] == true || has(Manifest.permission.RECORD_AUDIO)
                    else -> false
                }
            }
            if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            pendingPermission = null
        }
        pendingGeo?.let { (origin, cb) ->
            cb.invoke(origin, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
            pendingGeo = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db.get(this)
        setContentView(R.layout.activity_browser)
        bindViews()

        val start = intent?.takeIf { it.action == ACTION_OPEN_URL }?.dataString
        val saved = getSharedPreferences("tabs", MODE_PRIVATE).getString("urls", null)
            ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (saved.isNotEmpty()) {
            saved.forEach { newTab(it, incognito = false, select = false) }
            selectTab(tabs.last())
        }
        if (start != null) newTab(start, false) else if (tabs.isEmpty()) newTab(prefs.homeUrl(), false)

        // Downloads that were running when the app was closed continue now.
        DownloadService.resumeInterrupted(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> current?.webView?.webChromeClient?.onHideCustomView()
                    findBar.visibility == View.VISIBLE -> closeFind()
                    current?.webView?.canGoBack() == true -> current?.webView?.goBack()
                    tabs.size > 1 -> closeTab(current!!)
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_OPEN_URL) intent.dataString?.let { newTab(it, false) }
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed.
        tabs.forEach { applySettings(it) }
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
    }

    override fun onDestroy() {
        if (isFinishing) {
            AppLock.lock()
            tabs.filter { it.incognito }.forEach { it.webView.clearCache(true); it.webView.clearHistory() }
        }
        tabs.forEach { (it.webView.parent as? ViewGroup)?.removeView(it.webView); it.webView.destroy() }
        tabs.clear()
        super.onDestroy()
    }

    private fun saveTabs() {
        val urls = tabs.filter { !it.incognito }.map { it.url }.filter { it.startsWith("http") }
        getSharedPreferences("tabs", MODE_PRIVATE).edit().putString("urls", urls.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------ views

    private fun bindViews() {
        urlBar = findViewById(R.id.url)
        secureIcon = findViewById(R.id.secure)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        webContainer = findViewById(R.id.webContainer)
        tabsBtn = findViewById(R.id.btnTabs)
        fabVideo = findViewById(R.id.fabVideo)
        findBar = findViewById(R.id.findBar)
        findText = findViewById(R.id.findText)
        findCount = findViewById(R.id.findCount)
        fullscreen = findViewById(R.id.fullscreen)

        tabsBtn.background = ContextCompat.getDrawable(this, R.drawable.tab_count_bg)

        urlBar.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigate(urlBar.text.toString()); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (!focused) showUrl()
        }
        urlBar.setAdapter(SuggestionAdapter())
        urlBar.setOnItemClickListener { parent, _, position, _ ->
            val e = parent.getItemAtPosition(position) as Entry
            navigate(e.url)
        }

        findViewById<ImageButton>(R.id.btnQr).setOnClickListener {
            qrScan.launch(ScanOptions().setPrompt("Scan a QR code").setBeepEnabled(false).setOrientationLocked(false))
        }
        tabsBtn.setOnClickListener { showTabSwitcher() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { current?.webView?.let { if (it.canGoBack()) it.goBack() } }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { current?.webView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { navigate(prefs.homeUrl()) }
        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener { current?.webView?.reload() }
        findViewById<ImageButton>(R.id.btnDownloads).setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        fabVideo.setOnClickListener { showVideos() }

        swipe.setOnRefreshListener { current?.webView?.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> (current?.webView?.scrollY ?: 0) > 0 }

        findText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { current?.webView?.findAllAsync(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<ImageButton>(R.id.findNext).setOnClickListener { current?.webView?.findNext(true) }
        findViewById<ImageButton>(R.id.findPrev).setOnClickListener { current?.webView?.findNext(false) }
        findViewById<ImageButton>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun showUrl() {
        val t = current ?: return
        if (!urlBar.hasFocus()) urlBar.setText(t.url, false)
        val secure = t.url.startsWith("https://")
        secureIcon.setImageResource(if (secure) R.drawable.ic_lock else R.drawable.ic_info)
        tabsBtn.text = tabs.size.toString()
        updateVideoFab()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") || text.startsWith("about:") || text.startsWith("file:") -> text
            !text.contains(' ') && text.contains('.') && !text.endsWith('.') -> "https://$text"
            else -> prefs.searchUrl(text)
        }
        hideKeyboard()
        val t = current ?: run { newTab(url, false); return }
        load(t, url)
    }

    private fun load(t: Tab, url: String) {
        val headers = HashMap<String, String>()
        if (prefs.doNotTrack) { headers["DNT"] = "1"; headers["Sec-GPC"] = "1" }
        t.webView.loadUrl(url, headers)
    }

    // ------------------------------------------------------------------ tabs

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, incognito: Boolean, select: Boolean = true): Tab {
        val wv = WebView(this)
        val tab = Tab(wv, incognito)
        tabs.add(tab)
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.settings.apply {
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            if (incognito) {
                cacheMode = WebSettings.LOAD_NO_CACHE
                @Suppress("DEPRECATION")
                saveFormData = false
            }
        }
        applySettings(tab)
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.addJavascriptInterface(VideoBridge(tab), "__vd")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, PAGE_JS, setOf("*"))
        }
        wv.setFindListener { active, count, _ ->
            if (tab == current) findCount.text = if (count == 0) "0/0" else "${active + 1}/$count"
        }
        wv.setDownloadListener { dUrl, ua, disposition, mime, _ ->
            val name = URLUtil.guessFileName(dUrl, disposition, mime)
            confirmDownload(dUrl, name, mime ?: "application/octet-stream", false, ua, tab.url)
        }
        wv.setOnLongClickListener { onLongPress(tab) }
        var downX = 0f; var downY = 0f; var downAt = 0L
        wv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downAt = ev.eventTime }
                android.view.MotionEvent.ACTION_UP -> {
                    val slop = 24 * resources.displayMetrics.density
                    val isTap = Math.abs(ev.x - downX) < slop && Math.abs(ev.y - downY) < slop && ev.eventTime - downAt < 600
                    if (isTap) tab.lastTap = System.currentTimeMillis()
                }
            }
            false
        }
        if (url != null) load(tab, url)
        if (select) selectTab(tab)
        tabsBtn.text = tabs.size.toString()
        return tab
    }

    private fun applySettings(t: Tab) {
        val s = t.webView.settings
        s.javaScriptEnabled = prefs.javascript
        s.textZoom = prefs.textZoom
        val desktop = t.desktop || prefs.desktopDefault
        val mobileUa = WebSettings.getDefaultUserAgent(this)
        s.userAgentString = if (desktop) DESKTOP_UA else mobileUa
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(t.webView, !prefs.blockThirdPartyCookies)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.forceDark)
        }
        @Suppress("DEPRECATION")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(s, if (prefs.forceDark) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        }
    }

    private fun selectTab(t: Tab) {
        current?.let { if (it != t) captureThumb(it) }
        current?.webView?.onPause()
        webContainer.removeAllViews()
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        webContainer.addView(t.webView)
        t.webView.onResume()
        current = t
        closeFind()
        showUrl()
    }

    private fun closeTab(t: Tab) {
        val idx = tabs.indexOf(t)
        tabs.remove(t)
        if (t.incognito) t.webView.clearCache(true)
        (t.webView.parent as? ViewGroup)?.removeView(t.webView)
        t.webView.destroy()
        if (tabs.isEmpty()) newTab(prefs.homeUrl(), false)
        else if (current == t) selectTab(tabs[(idx - 1).coerceAtLeast(0)])
        tabsBtn.text = tabs.size.toString()
    }

    private fun showTabSwitcher() {
        current?.let { captureThumb(it) }
        TabGridDialog(
            this, tabs, current,
            onSelect = { selectTab(it) },
            onClose = { closeTab(it) },
            onNew = { incognito -> newTab(prefs.homeUrl(), incognito) },
            onCloseAll = { tabs.toList().forEach { closeTab(it) } },
        ).show()
    }

    /** Takes a small screenshot of the visible part of a tab's page. */
    private fun captureThumb(t: Tab) {
        val wv = t.webView
        if (wv.parent == null || wv.width <= 0 || wv.height <= 0) return
        runCatching {
            val scale = 0.4f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt(), (wv.height * scale).toInt(), Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            c.scale(scale, scale)
            c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(c)
            t.thumbnail?.takeIf { it != bmp }?.recycle()
            t.thumbnail = bmp
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val t = current ?: return
        val pm = PopupMenu(this, anchor)
        pm.menuInflater.inflate(R.menu.browser, pm.menu)
        val host = Uri.parse(t.url).host.orEmpty()
        pm.menu.findItem(R.id.m_bookmark).title = if (db.isBookmarked(t.url)) "Remove bookmark" else "Add bookmark"
        pm.menu.findItem(R.id.m_desktop).isChecked = t.desktop
        pm.menu.findItem(R.id.m_adsite).apply {
            isVisible = prefs.adBlock && host.isNotEmpty()
            isChecked = host !in prefs.whitelist
        }
        pm.menu.findItem(R.id.m_videos).title = "Videos on this page (${t.videos.size})"
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.m_new_tab -> newTab(prefs.homeUrl(), false)
                R.id.m_new_private -> newTab(prefs.homeUrl(), true)
                R.id.m_bookmark -> {
                    if (db.isBookmarked(t.url)) { db.removeBookmark(t.url); toast("Bookmark removed") }
                    else { db.addBookmark(t.title.ifBlank { t.url }, t.url); toast("Bookmarked") }
                }
                R.id.m_bookmarks -> library.launch(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_BOOKMARKS))
                R.id.m_history -> library.launch(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, LibraryActivity.MODE_HISTORY))
                R.id.m_downloads -> startActivity(Intent(this, DownloadsActivity::class.java))
                R.id.m_vault -> startActivity(Intent(this, VaultActivity::class.java))
                R.id.m_videos -> showVideos()
                R.id.m_find -> openFind()
                R.id.m_desktop -> { t.desktop = !t.desktop; applySettings(t); t.webView.reload() }
                R.id.m_adsite -> {
                    val wl = prefs.whitelist.toMutableSet()
                    if (host in wl) wl.remove(host) else wl.add(host)
                    prefs.whitelist = wl
                    t.webView.reload()
                }
                R.id.m_share -> {
                    goingExternal()
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.url), "Share"))
                }
                R.id.m_copy -> copy(t.url)
                R.id.m_shortcut -> addShortcut(t)
                R.id.m_pdf -> {
                    goingExternal()
                    val pmgr = getSystemService(PrintManager::class.java)
                    val title = t.title.ifBlank { "page" }
                    pmgr.print(title, t.webView.createPrintDocumentAdapter(title), PrintAttributes.Builder().build())
                }
                R.id.m_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.m_lock -> { AppLock.lock(); finish() }
            }
            true
        }
        pm.show()
    }

    private val library = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.dataString?.let { navigate(it) }
    }

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findText.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(findText, 0)
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        findText.setText("")
        findCount.text = ""
        current?.webView?.clearMatches()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(findText.windowToken, 0)
    }

    private fun addShortcut(t: Tab) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) { toast("Your launcher doesn't support this"); return }
        val icon = t.favicon?.let { IconCompat.createWithBitmap(it) } ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher)
        val info = ShortcutInfoCompat.Builder(this, "site_" + t.url.hashCode())
            .setShortLabel(t.title.ifBlank { Uri.parse(t.url).host ?: "Site" }.take(24))
            .setIcon(icon)
            .setIntent(Intent(ACTION_OPEN_URL, Uri.parse(t.url), this, BrowserActivity::class.java))
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
    }

    // ------------------------------------------------------------------ long press

    private fun onLongPress(tab: Tab): Boolean {
        val r = tab.webView.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> linkMenu(extra)
            WebView.HitTestResult.IMAGE_TYPE -> imageMenu(extra, null)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val msg = android.os.Handler(mainLooper).obtainMessage()
                tab.webView.requestFocusNodeHref(msg)
                val link = msg.data.getString("url")
                imageMenu(extra, link)
            }
            else -> return false
        }
        return true
    }

    private fun linkMenu(link: String) {
        val items = arrayOf("Open in new tab", "Open in private tab", "Copy link", "Share link", "Download link")
        AlertDialog.Builder(this).setTitle(link).setItems(items) { _, i ->
            when (i) {
                0 -> newTab(link, false)
                1 -> newTab(link, true)
                2 -> copy(link)
                3 -> { goingExternal(); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share")) }
                4 -> confirmDownload(link, URLUtil.guessFileName(link, null, null), "application/octet-stream", false, null, current?.url)
            }
        }.show()
    }

    private fun imageMenu(img: String, link: String?) {
        val items = mutableListOf("Open image in new tab", "Save image", "Copy image link")
        if (link != null) items += listOf("Open link in new tab", "Copy link")
        AlertDialog.Builder(this).setItems(items.toTypedArray()) { _, i ->
            when (i) {
                0 -> newTab(img, false)
                1 -> if (img.startsWith("http")) confirmDownload(img, URLUtil.guessFileName(img, null, "image/*"), "image/*", false, null, current?.url) else toast("Can't save this image")
                2 -> copy(img)
                3 -> link?.let { newTab(it, false) }
                4 -> link?.let { copy(it) }
            }
        }.show()
    }

    // ------------------------------------------------------------------ videos & downloads

    private fun updateVideoFab() {
        fabVideo.visibility = View.GONE
    }

    private fun showVideos() {
        val t = current ?: return
        val list = t.videos.values.toList().reversed()
        if (list.isEmpty()) { toast("Play the video, then tap the ⬇ Download button on it."); return }
        val labels = list.map { v ->
            val name = v.url.substringBefore('?').substringAfterLast('/').take(50)
            "${v.kind}  ·  $name"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Videos you downloaded or played")
            .setItems(labels) { _, i -> videoActions(list[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun videoActions(v: VideoInfo) {
        val opts = if (v.downloadable) arrayOf("Download", "Play in player", "Copy link") else arrayOf("Play in player", "Copy link")
        AlertDialog.Builder(this).setTitle(v.kind + " video").setItems(opts) { _, i ->
            when (opts[i]) {
                "Download" -> confirmDownload(v.url, v.suggestedName(), mimeFor(v.kind), v.kind == "HLS", null, v.pageUrl)
                "Play in player" -> startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, v.url)
                        .putExtra(PlayerActivity.EXTRA_REFERER, v.pageUrl)
                        .putExtra(PlayerActivity.EXTRA_UA, current?.webView?.settings?.userAgentString)
                        .putExtra(PlayerActivity.EXTRA_TITLE, v.pageTitle)
                )
                "Copy link" -> copy(v.url)
            }
        }.show()
    }

    private fun mimeFor(kind: String) = when (kind) {
        "HLS" -> "video/mp2t"; "WEBM" -> "video/webm"; "MP3" -> "audio/mpeg"; "M4A", "AAC" -> "audio/mp4"
        "OGG", "OPUS" -> "audio/ogg"; else -> "video/mp4"
    }

    private fun confirmDownload(url: String, name: String, mime: String, hls: Boolean, ua: String?, referer: String?) {
        val input = EditText(this).apply { setText(name); setSelection(0, name.substringBeforeLast('.').length) }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Download")
            .setMessage(if (prefs.encryptDownloads) "Saved encrypted in the locked folder." else "Saved to your download folder.")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                val cookies = CookieManager.getInstance().getCookie(url)
                val task = DownloadRegistry.add(
                    url, com.sci.calc.vault.Storage.safeName(input.text.toString()), mime, hls,
                    ua ?: current?.webView?.settings?.userAgentString, referer, cookies
                )
                askNotificationPermission()
                DownloadService.start(this, task)
                toast("Download started")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
        toast("Copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sitePermission(origin: String, kind: String): Boolean? {
        val sp = getSharedPreferences("site_perms", MODE_PRIVATE)
        return if (sp.contains("$kind|$origin")) sp.getBoolean("$kind|$origin", false) else null
    }

    private fun rememberSitePermission(origin: String, kind: String, allow: Boolean) =
        getSharedPreferences("site_perms", MODE_PRIVATE).edit().putBoolean("$kind|$origin", allow).apply()

    private fun addVideo(tab: Tab, v: VideoInfo) {
        if (tab.videos.containsKey(v.url)) return
        tab.videos[v.url] = v
        if (tab == current) updateVideoFab()
    }

    /** User tapped ⬇ on a specific video: work out which file/stream that video is playing. */
    private fun onVideoButton(tab: Tab, o: JSONObject) {
        val page = o.optString("page").ifBlank { tab.url }
        val title = o.optString("title").ifBlank { tab.title }
        val found = LinkedHashMap<String, VideoInfo>()
        val src = o.optString("src")
        if (src.startsWith("http")) {
            found[src] = VideoInfo.detect(src, page, title) ?: VideoInfo(src, "MP4", page, title)
        }
        // Stream files the player itself requested (blob: players), most recent first.
        val urls = o.optJSONArray("urls")
        if (found.isEmpty() && urls != null) {
            for (i in urls.length() - 1 downTo 0) {
                val u = urls.optString(i)
                VideoInfo.detect(u, page, title)?.let { found.putIfAbsent(u, it) }
            }
        }
        // Last resort: media seen on the network shortly before/after this video started.
        if (found.isEmpty()) {
            val now = System.currentTimeMillis()
            tab.candidates.values.filter { now - it.second < 120_000 }.reversed()
                .forEach { found.putIfAbsent(it.first.url, it.first) }
        }
        val usable = found.values.filter { it.downloadable }
        when {
            usable.isEmpty() && found.isNotEmpty() -> toast("This video uses a format that can't be downloaded (DASH/DRM).")
            usable.isEmpty() -> toast("Couldn't find this video's file. Let it play for a few seconds, then try again.")
            usable.size == 1 -> startVideoDownload(tab, usable[0])
            else -> {
                // Prefer the master playlist: named "master", else the first .m3u8 the player requested
                // (players load the master first, then the quality playlists).
                val hls = usable.filter { it.kind == "HLS" }
                val master = hls.firstOrNull { it.url.contains("master", true) } ?: hls.lastOrNull()
                if (master != null) startVideoDownload(tab, master)
                else AlertDialog.Builder(this)
                    .setTitle("Choose stream")
                    .setItems(usable.map { "${it.kind} · " + it.url.substringBefore('?').substringAfterLast('/').take(40) }.toTypedArray()) { _, i ->
                        startVideoDownload(tab, usable[i])
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun startVideoDownload(tab: Tab, v: VideoInfo) {
        addVideo(tab, v)
        val referer = v.pageUrl ?: tab.url
        if (v.kind != "HLS") {
            confirmDownload(v.url, v.suggestedName(), mimeFor(v.kind), false, null, referer)
            return
        }
        // Streams usually come in several qualities: ask which one, like other browsers do.
        val ua = tab.webView.settings.userAgentString
        val cookies = CookieManager.getInstance().getCookie(v.url)
        toast("Checking available qualities…")
        thread {
            val variants = runCatching { HlsQuality.probe(v.url, ua, referer, cookies) }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (variants.isNullOrEmpty() || variants.size == 1) {
                    val url = variants?.firstOrNull()?.url ?: v.url
                    confirmDownload(url, v.suggestedName(), mimeFor("HLS"), true, ua, referer)
                    return@runOnUiThread
                }
                val labels = variants.map { q ->
                    val size = if (q.estBytes > 0) "  ·  ~" + Formatter.formatShortFileSize(this, q.estBytes) else ""
                    q.label + size
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Choose quality")
                    .setItems(labels) { _, i ->
                        val q = variants[i]
                        val base = v.suggestedName().substringBeforeLast('.')
                        confirmDownload(q.url, "${base}_${q.label.replace(" ", "")}.ts", mimeFor("HLS"), true, ua, referer)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    /** A media URL appeared in network traffic. Shown only if it follows a tap (play press). */
    private fun onCandidate(tab: Tab, v: VideoInfo) {
        val now = System.currentTimeMillis()
        if (!tab.candidates.containsKey(v.url)) tab.candidates[v.url] = v to now
        while (tab.candidates.size > 80) tab.candidates.remove(tab.candidates.keys.first())
    }

    /** The user pressed play on a <video>. Show it, plus stream files fetched just before. */
    private fun onUserPlay(tab: Tab, src: String?, page: String?, title: String?) {
        val now = System.currentTimeMillis()
        tab.lastTap = now
        if (src != null && src.startsWith("http")) {
            val v = VideoInfo.detect(src, page, title) ?: VideoInfo(src, "MP4", page, title)
            addVideo(tab, v)
        }
    }

    // ------------------------------------------------------------------ WebView clients

    private inner class VideoBridge(private val tab: Tab) {
        @JavascriptInterface
        fun played(url: String?, page: String?, title: String?) {
            runOnUiThread { onUserPlay(tab, url, page, title) }
        }

        /** Called by the ⬇ Download button drawn on top of a video. */
        @JavascriptInterface
        fun download(json: String?) {
            val o = runCatching { JSONObject(json ?: "") }.getOrNull() ?: return
            runOnUiThread { onVideoButton(tab, o) }
        }

        /** Ad-hiding CSS for a frame, requested by the page script at document start. */
        @JavascriptInterface
        fun css(host: String?): String {
            if (!prefs.adBlock) return ""
            val top = pageHosts[tab]
            if (top != null && top in whitelistCache) return ""
            return AdBlocker.cssFor(host)
        }
    }

    private inner class Client(private val tab: Tab) : WebViewClient() {
        private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val host = request.url.host
            if (prefs.adBlock && !request.isForMainFrame) {
                val pageHost = pageHosts[tab]
                if ((pageHost == null || pageHost !in whitelistCache) && AdBlocker.isAd(host)) {
                    AdBlocker.blockedCount.incrementAndGet()
                    return empty
                }
            }
            val v = VideoInfo.detect(url, pageUrls[tab], tab.title)
            if (v != null) runOnUiThread { onCandidate(tab, v) }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (prefs.adBlock && request.isForMainFrame && (pageHosts[tab] ?: "") !in whitelistCache && AdBlocker.isAd(uri.host)) {
                toast("Blocked an ad redirect")
                return true
            }
            return when (uri.scheme?.lowercase(Locale.US)) {
                "http", "https", "about", "data", "blob", "javascript", "file" -> false
                "intent" -> {
                    try {
                        val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        val fallback = i.getStringExtra("browser_fallback_url")
                        if (fallback != null) view.loadUrl(fallback) else { goingExternal(); startActivity(i) }
                    } catch (e: Exception) { toast("Can't open this link") }
                    true
                }
                else -> {
                    // tel:, mailto:, upi:, market: … hand over to the right app
                    try { goingExternal(); startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("No app can open this link") }
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (pageUrls[tab] != url) { tab.videos.clear(); tab.candidates.clear(); tab.lastTap = 0 }
            pageUrls[tab] = url
            pageHosts[tab] = Uri.parse(url).host.orEmpty()
            whitelistCache = prefs.whitelist
            tab.favicon = favicon
            if (tab == current) { progress.visibility = View.VISIBLE; showUrl() }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (tab == current) {
                progress.visibility = View.GONE; swipe.isRefreshing = false; showUrl()
                view.postDelayed({ if (tab == current) captureThumb(tab) }, 800)
            }
            // Fallback for old WebViews without document-start scripts (no-op if already injected).
            view.evaluateJavascript(PAGE_JS, null)
            if (!tab.incognito && prefs.saveHistory && url.startsWith("http")) {
                db.addHistory(view.title ?: url, url)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (tab == current) showUrl()
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            AlertDialog.Builder(this@BrowserActivity)
                .setTitle("Connection not secure")
                .setMessage("The certificate for ${Uri.parse(error.url).host} isn't valid. Someone could be trying to read your data.")
                .setPositiveButton("Go back") { _, _ -> handler.cancel() }
                .setNegativeButton("Continue anyway") { _, _ -> handler.proceed() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }
    }

    private val pageUrls = java.util.concurrent.ConcurrentHashMap<Tab, String>()
    private val pageHosts = java.util.concurrent.ConcurrentHashMap<Tab, String>()

    private fun siteOf(host: String?): String {
        val parts = host.orEmpty().lowercase().split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val n = if (parts[parts.size - 1].length == 2 && parts[parts.size - 2].length <= 3) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }

    /** Opens same-site pop-ups (e.g. target=_blank links); blocks ad pop-ups; asks for others. */
    private fun handlePopup(opener: Tab, url: String) {
        val host = Uri.parse(url).host
        val adsOn = prefs.adBlock && (pageHosts[opener] ?: "") !in whitelistCache
        when {
            adsOn && AdBlocker.isAd(host) -> toast("Pop-up ad blocked")
            !url.startsWith("http") -> {}
            siteOf(host) == siteOf(pageHosts[opener]) || !prefs.blockPopups -> newTab(url, opener.incognito)
            else -> Snackbar.make(findViewById(R.id.main), "Pop-up blocked: " + (host ?: url), Snackbar.LENGTH_LONG)
                .setAction("Open") { newTab(url, opener.incognito) }
                .show()
        }
    }

    @Volatile private var whitelistCache: Set<String> = emptySet()

    private inner class Chrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (tab == current) {
                progress.progress = newProgress
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title.orEmpty()
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreen.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            fullscreen.removeView(v)
            fullscreen.visibility = View.GONE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (prefs.blockPopups && !isUserGesture) return false
            // Load the pop-up in a hidden WebView just long enough to see where it goes.
            val probe = WebView(this@BrowserActivity)
            probe.settings.javaScriptEnabled = false
            var decided = false
            fun decide(url: String) {
                if (decided || url.isBlank() || url == "about:blank") return
                decided = true
                probe.stopLoading()
                probe.post { probe.destroy() }
                handlePopup(tab, url)
            }
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { decide(r.url.toString()); return true }
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { decide(url) }
            }
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = probe
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.webView == window }?.let { closeTab(it) }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            return try {
                goingExternal()
                fileChooser.launch(params.createIntent())
                true
            } catch (e: Exception) {
                fileCallback = null; false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val origin = request.origin.toString()
                val wanted = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (wanted.isEmpty()) { request.deny(); return@runOnUiThread }
                val names = wanted.joinToString(" and ") { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "camera" else "microphone" }
                fun grant() {
                    val sys = wanted.map {
                        if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
                    }
                    if (sys.all { has(it) }) request.grant(wanted.toTypedArray())
                    else { pendingPermission = request; askPermissions.launch(sys.toTypedArray()) }
                }
                when (sitePermission(origin, "media")) {
                    true -> grant()
                    false -> request.deny()
                    null -> AlertDialog.Builder(this@BrowserActivity)
                        .setTitle(Uri.parse(origin).host)
                        .setMessage("This site wants to use your $names.")
                        .setPositiveButton("Allow") { _, _ -> rememberSitePermission(origin, "media", true); grant() }
                        .setNegativeButton("Block") { _, _ -> rememberSitePermission(origin, "media", false); request.deny() }
                        .setOnCancelListener { request.deny() }
                        .show()
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            fun grant() {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) callback.invoke(origin, true, false)
                else {
                    pendingGeo = origin to callback
                    askPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            when (sitePermission(origin, "geo")) {
                true -> grant()
                false -> callback.invoke(origin, false, false)
                null -> AlertDialog.Builder(this@BrowserActivity)
                    .setTitle(Uri.parse(origin).host ?: origin)
                    .setMessage("This site wants to know your location.")
                    .setPositiveButton("Allow") { _, _ -> rememberSitePermission(origin, "geo", true); grant() }
                    .setNegativeButton("Block") { _, _ -> rememberSitePermission(origin, "geo", false); callback.invoke(origin, false, false) }
                    .setOnCancelListener { callback.invoke(origin, false, false) }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------------ suggestions

    private inner class SuggestionAdapter : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
        private var items: List<Entry> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val e = items[position]
            v.findViewById<TextView>(android.R.id.text1).text = e.title.ifBlank { e.url }
            v.findViewById<TextView>(android.R.id.text2).text = e.url
            return v
        }
        override fun getFilter() = object : Filter() {
            override fun performFiltering(c: CharSequence?): FilterResults {
                val list = db.suggestions(c?.toString().orEmpty())
                return FilterResults().apply { values = list; count = list.size }
            }
            @Suppress("UNCHECKED_CAST")
            override fun publishResults(c: CharSequence?, r: FilterResults?) {
                items = (r?.values as? List<Entry>).orEmpty()
                notifyDataSetChanged()
            }
            override fun convertResultToString(resultValue: Any?) = (resultValue as? Entry)?.url ?: ""
        }
    }

    companion object {
        const val ACTION_OPEN_URL = "com.sci.calc.OPEN_URL"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * Injected into every page and frame at document start:
         *  - ad-hiding CSS
         *  - remembers stream/file URLs the page's player requests (fetch / XHR)
         *  - draws a "⬇ Download" button on a video once the user presses play on it
         */
        private val PAGE_JS = """
            (function(){
              if(window.__vdInit) return; window.__vdInit=true;
              try{
                var css=__vd.css(location.hostname);
                if(css){var st=document.createElement('style');st.textContent=css;(document.head||document.documentElement).appendChild(st);}
              }catch(e){}
              var media=[];
              var re=/\.(m3u8|mpd|mp4|m4v|webm|mov|mkv)(\?|#|$)/i, seg=/(range=|bytestart=|\/seg-|&sq=|\.m4s)/i;
              function note(u){
                if(!u) return;
                try{u=new URL(u,location.href).href;}catch(e){return;}
                if(re.test(u)&&!seg.test(u)){media.push({u:u,t:Date.now()}); if(media.length>80) media.shift();}
              }
              try{var of=window.fetch; if(of){window.fetch=function(i){try{note(typeof i==='string'?i:(i&&i.url));}catch(e){} return of.apply(this,arguments);};}}catch(e){}
              try{var oo=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){try{note(u);}catch(e){} return oo.apply(this,arguments);};}catch(e){}
              var lastG=0;
              ['pointerdown','touchend','click','keydown'].forEach(function(t){document.addEventListener(t,function(e){if(e.isTrusted) lastG=Date.now();},true);});
              var vids=[];
              function urlsFor(v){
                var since=(v.__vdPlayAt||Date.now())-30000, out=[];
                media.forEach(function(m){if(m.t>=since&&out.indexOf(m.u)<0) out.push(m.u);});
                return out;
              }
              function makeBtn(v){
                if(v.__vdBtn) return;
                var b=document.createElement('div');
                b.textContent='⬇ Download';
                b.style.cssText='position:fixed;z-index:2147483647;display:none;background:rgba(20,20,20,.78);color:#fff;'+
                  'font:600 13px/1 sans-serif;padding:9px 12px;border-radius:18px;border:1px solid rgba(255,255,255,.4);'+
                  'box-shadow:0 2px 6px rgba(0,0,0,.4);-webkit-user-select:none;user-select:none;pointer-events:auto;';
                var stop=function(e){e.stopPropagation(); if(e.stopImmediatePropagation) e.stopImmediatePropagation();};
                ['pointerdown','pointerup','mousedown','mouseup','touchstart','touchend'].forEach(function(t){b.addEventListener(t,stop,true);});
                b.addEventListener('click',function(e){
                  e.preventDefault(); stop(e);
                  var s=v.currentSrc||v.src||'';
                  __vd.download(JSON.stringify({src:s,urls:urlsFor(v),title:document.title,page:location.href}));
                },true);
                v.__vdBtn=b; vids.push(v);
              }
              function place(){
                var fs=document.fullscreenElement||document.webkitFullscreenElement;
                vids.forEach(function(v){
                  var b=v.__vdBtn; if(!b) return;
                  if(!v.isConnected){ if(b.parentNode) b.parentNode.removeChild(b); return; }
                  var parent=(fs&&fs.tagName!=='VIDEO')?fs:(document.body||document.documentElement);
                  if(b.parentNode!==parent) parent.appendChild(b);
                  var r=v.getBoundingClientRect();
                  var vis=r.width>=120&&r.height>=60&&r.bottom>30&&r.top<innerHeight-30&&getComputedStyle(v).visibility!=='hidden';
                  b.style.display=vis?'block':'none';
                  if(vis){
                    b.style.top=Math.max(6,r.top+10)+'px';
                    b.style.left=Math.max(6,Math.min(innerWidth-b.offsetWidth-6,r.right-b.offsetWidth-10))+'px';
                  }
                });
              }
              document.addEventListener('play',function(e){
                var v=e.target;
                if(!v||!v.tagName||v.tagName.toLowerCase()!=='video') return;
                var user=(Date.now()-lastG<6000)||(navigator.userActivation&&navigator.userActivation.isActive);
                if(!user&&!v.__vdBtn) return;
                if(!v.__vdPlayAt) v.__vdPlayAt=Date.now();
                makeBtn(v); place();
                try{__vd.played(v.currentSrc||v.src||'',location.href,document.title);}catch(x){}
              },true);
              window.addEventListener('scroll',place,true);
              window.addEventListener('resize',place);
              document.addEventListener('fullscreenchange',place);
              setInterval(place,600);
            })();
        """.trimIndent()
    }
}
