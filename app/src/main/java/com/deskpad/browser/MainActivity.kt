package com.deskpad.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class BrowserTab(val webView: DesktopWebView) {
    var title: String = ""
    var url: String = ""
    var loading: Boolean = false
    var progress: Int = 0
}

/** Garis kemajuan tipis di bawah toolbar. */
class ProgressLine(context: Context) : View(context) {
    var fraction: Float = 0f
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint().apply { color = Pal.led }
    override fun onDraw(canvas: Canvas) {
        if (fraction > 0f) canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
    }
}

class MainActivity : Activity() {

    companion object {
        const val HOME = "https://www.google.com"
        const val REQ_FILE = 1001
        val RESOLUTIONS = arrayOf(480 to 270, 640 to 360, 800 to 450, 960 to 540, 1024 to 576, 1280 to 720, 1600 to 900, 1920 to 1080)
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var screen: ScreenFrame
    private lateinit var keyboard: KeyboardPanel
    private lateinit var mouse: MouseController
    private lateinit var mousePanel: MousePanel
    private lateinit var urlField: EditText
    private lateinit var btnBack: TextView
    private lateinit var btnFwd: TextView
    private lateinit var btnReload: TextView
    private lateinit var btnTabs: TextView
    private lateinit var progressLine: ProgressLine
    private lateinit var modeKeyboard: TextView
    private lateinit var modeMouse: TextView
    private lateinit var resLabel: TextView

    private val tabs = mutableListOf<BrowserTab>()
    private var current = -1
    private var cursorReady = false
    private var imeAllowed = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val desktopUa: String by lazy {
        val def = WebSettings.getDefaultUserAgent(this)
        val major = Regex("Chrome/(\\d+)").find(def)?.groupValues?.get(1) ?: "130"
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("deskpad", MODE_PRIVATE)
        buildUi()

        val startUrl = intent?.data?.toString() ?: HOME
        createTab(startUrl)
    }

    override fun onResume() {
        super.onResume()
        tabs.forEach { it.webView.onResume() }
    }

    override fun onPause() {
        keyboard.releaseAll()
        tabs.forEach { it.webView.onPause() }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        for (t in tabs) {
            (t.webView.parent as? android.view.ViewGroup)?.removeView(t.webView)
            t.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            screen.isCustomViewShown() -> screen.hideCustomView()
            currentWebView()?.canGoBack() == true -> currentWebView()?.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val result = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            fileCallback?.onReceiveValue(result)
            fileCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun toolBtn(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Pal.textLight)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = false
        background = keySelector(
            rounded(Pal.bezelDark, dpf(8f)),
            rounded(Pal.keyPressed, dpf(8f))
        )
        layoutParams = lp(dpi(34f), dpi(30f)).apply { setMargins(dpi(2f), 0, dpi(2f), 0) }
    }

    private fun buildUi() {
        val outer = FrameLayout(this).apply {
            setBackgroundColor(Pal.body)
            fitsSystemWindows = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
            isFocusableInTouchMode = true
        }
        outer.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))

        // ---- Bezel (bingkai layar) ----
        val bezel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Pal.bezel, dpf(16f))
            setPadding(dpi(10f), dpi(8f), dpi(10f), dpi(8f))
        }

        // Toolbar browser
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        btnBack = toolBtn("◀").apply { setOnClickListener { currentWebView()?.let { if (it.canGoBack()) it.goBack() } } }
        btnFwd = toolBtn("▶").apply { setOnClickListener { currentWebView()?.let { if (it.canGoForward()) it.goForward() } } }
        btnReload = toolBtn("⟳").apply {
            setOnClickListener {
                val t = tabs.getOrNull(current) ?: return@setOnClickListener
                if (t.loading) t.webView.stopLoading() else t.webView.reload()
            }
        }
        urlField = EditText(this).apply {
            background = rounded(Pal.urlBg, dpf(10f))
            setPadding(dpi(10f), 0, dpi(10f), 0)
            setTextColor(Pal.textLight)
            setHintTextColor(Pal.textDim)
            hint = "Cari atau ketik alamat web"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            layoutParams = lp(0, dpi(30f), 1f).apply { setMargins(dpi(3f), 0, dpi(3f), 0) }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    navigate(text.toString())
                    true
                } else false
            }
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) updateChrome() }
        }
        btnTabs = toolBtn("1").apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            background = keySelector(
                rounded(Pal.bezelDark, dpf(8f), Pal.textDim, dpi(1.5f)),
                rounded(Pal.keyPressed, dpf(8f), Pal.textDim, dpi(1.5f))
            )
            setOnClickListener { showTabList() }
        }
        val btnMenu = toolBtn("⋮").apply { setOnClickListener { showMenu() } }

        toolbar.addView(btnBack)
        toolbar.addView(btnFwd)
        toolbar.addView(btnReload)
        toolbar.addView(urlField)
        toolbar.addView(btnTabs)
        toolbar.addView(btnMenu)
        bezel.addView(toolbar, lp(MATCH, dpi(32f)))

        progressLine = ProgressLine(this)
        bezel.addView(progressLine, lp(MATCH, dpi(2f)).apply { topMargin = dpi(3f) })

        // Layar 16:9
        screen = ScreenFrame(this).apply {
            layoutParams = lp(MATCH, WRAP).apply { topMargin = dpi(3f) }
            touchEnabled = prefs.getBoolean("touch", false)
        }
        val res = RESOLUTIONS[prefs.getInt("res2", 4).coerceIn(0, RESOLUTIONS.size - 1)]
        screen.setVirtualSize(res.first, res.second)
        bezel.addView(screen)

        // Label bawah layar
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labelRow.addView(View(this).apply {
            background = oval(Pal.led)
        }, lp(dpi(7f), dpi(7f)).apply { rightMargin = dpi(6f) })
        labelRow.addView(TextView(this).apply {
            text = "DESKPAD  DESKTOP BROWSER"
            setTextColor(Color.parseColor("#C9CBE0"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
        }, lp(0, WRAP, 1f))
        resLabel = TextView(this).apply {
            setTextColor(Pal.textDim)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
        }
        labelRow.addView(resLabel)
        bezel.addView(labelRow, lp(MATCH, WRAP).apply { topMargin = dpi(5f) })
        updateResLabel()

        root.addView(bezel, lp(MATCH, WRAP))

        // ---- Kontrol ----
        keyboard = KeyboardPanel(this) { currentWebView() }.apply {
            background = rounded(Pal.bezelDark, dpf(14f))
        }
        mouse = MouseController(screen) { keyboard.currentMeta() }
        mouse.gainIndex = prefs.getInt("gain", 1).coerceIn(0, MouseController.GAINS.size - 1)
        mouse.scrollIndex = prefs.getInt("scroll", 1).coerceIn(0, MouseController.SCROLL_GAINS.size - 1)
        mousePanel = MousePanel(this, mouse)
        screen.onSized = {
            if (!cursorReady) {
                cursorReady = true
                mouse.centerCursor()
            }
        }

        // Pemilih mode: KEYBOARD | MOUSE
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeKeyboard = modeButton("KEYBOARD").apply { setOnClickListener { showMode(true) } }
        modeMouse = modeButton("MOUSE").apply { setOnClickListener { showMode(false) } }
        modeRow.addView(modeKeyboard)
        modeRow.addView(modeMouse)
        root.addView(modeRow, lp(MATCH, dpi(40f)).apply { topMargin = dpi(8f) })

        val controls = FrameLayout(this)
        controls.addView(keyboard, FrameLayout.LayoutParams(MATCH, MATCH))
        controls.addView(mousePanel, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(controls, lp(MATCH, 0, 1f).apply { topMargin = dpi(6f) })

        showMode(prefs.getBoolean("modeKeyboard", false))
        setContentView(outer)
    }

    private fun modeButton(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Pal.textLight)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = false
        background = keySelector(
            rounded(Pal.bezel, dpf(18f)),
            rounded(Pal.keyPressed, dpf(18f)),
            rounded(Pal.magenta, dpf(18f))
        )
        layoutParams = lp(0, MATCH, 1f).apply { setMargins(dpi(4f), 0, dpi(4f), 0) }
    }

    private fun showMode(keyboardMode: Boolean) {
        prefs.edit().putBoolean("modeKeyboard", keyboardMode).apply()
        keyboard.visibility = if (keyboardMode) View.VISIBLE else View.GONE
        mousePanel.visibility = if (keyboardMode) View.GONE else View.VISIBLE
        modeKeyboard.isActivated = keyboardMode
        modeMouse.isActivated = !keyboardMode
        if (!keyboardMode) keyboard.releaseAll()
        hideSystemKeyboard()
        currentWebView()?.requestFocus()
    }

    private fun updateResLabel() {
        resLabel.text = "${screen.cssW}×${screen.cssH}"
    }

    private fun updateChrome() {
        val t = tabs.getOrNull(current) ?: return
        val wv = t.webView
        if (!urlField.hasFocus()) urlField.setText(t.url)
        btnBack.alpha = if (wv.canGoBack()) 1f else 0.35f
        btnFwd.alpha = if (wv.canGoForward()) 1f else 0.35f
        btnReload.text = if (t.loading) "✕" else "⟳"
        progressLine.fraction = if (t.loading) (t.progress / 100f).coerceIn(0.05f, 1f) else 0f
        btnTabs.text = tabs.size.toString()
    }

    private fun hideSystemKeyboard() {
        val token = urlField.windowToken ?: return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(token, 0)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------
    // Navigasi
    // ------------------------------------------------------------------

    private fun currentWebView(): DesktopWebView? = tabs.getOrNull(current)?.webView

    private fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("file://") || lower.startsWith("about:")
        ) return t
        val local = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$").matches(t)
        if (local) return "http://$t"
        val looksLikeHost = !t.contains(' ') && t.contains('.')
        return if (looksLikeHost) "https://$t"
        else "https://www.google.com/search?q=" + Uri.encode(t)
    }

    private fun navigate(input: String) {
        currentWebView()?.loadUrl(toUrl(input))
        urlField.clearFocus()
        hideSystemKeyboard()
        currentWebView()?.requestFocus()
    }

    // ------------------------------------------------------------------
    // Tab
    // ------------------------------------------------------------------

    private fun tabFor(view: WebView?): BrowserTab? = tabs.firstOrNull { it.webView === view }

    private fun createTab(url: String?): BrowserTab {
        val wv = DesktopWebView(this)
        configure(wv)
        wv.allowIme = imeAllowed
        wv.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        screen.host.addView(wv)
        val tab = BrowserTab(wv)
        tabs.add(tab)
        wv.webViewClient = makeClient()
        wv.webChromeClient = makeChrome()
        switchTo(tabs.size - 1)
        if (url != null) wv.loadUrl(url)
        return tab
    }

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        current = index
        tabs.forEachIndexed { i, t ->
            t.webView.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }
        screen.currentWebView = tabs[index].webView
        tabs[index].webView.requestFocus()
        updateChrome()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val t = tabs.removeAt(index)
        screen.host.removeView(t.webView)
        t.webView.destroy()
        if (tabs.isEmpty()) {
            current = -1
            createTab(HOME)
        } else {
            switchTo((index - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1))
        }
    }

    private fun showTabList() {
        val titles = tabs.mapIndexed { i, t ->
            val name = if (t.title.isNotBlank()) t.title else t.url.ifBlank { "Tab baru" }
            (if (i == current) "● " else "○ ") + name
        }.toMutableList()
        titles += "＋ Tab baru"
        AlertDialog.Builder(this)
            .setTitle("Tab (${tabs.size})")
            .setItems(titles.toTypedArray()) { _, which ->
                if (which == tabs.size) createTab(HOME) else switchTo(which)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ------------------------------------------------------------------
    // WebView
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: DesktopWebView) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // false = lebar layout SELALU selebar WebView (1280 piksel CSS), meta viewport diabaikan.
        s.useWideViewPort = false
        s.loadWithOverviewMode = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = desktopUa
        s.allowFileAccess = false
        s.textZoom = 100

        wv.setBackgroundColor(Color.WHITE)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = true
        wv.isHorizontalScrollBarEnabled = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setTitle(name)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Mengunduh $name")
            } catch (e: Exception) {
                toast("Gagal mengunduh: ${e.message}")
            }
        }
    }

    private fun errorHtml(url: String, msg: String): String =
        "<html><head><meta charset='utf-8'><style>body{font-family:sans-serif;margin:10% 8%;color:#333}" +
            "h1{font-size:30px;font-weight:500}code{background:#eee;padding:2px 6px;border-radius:4px}</style></head>" +
            "<body><h1>Halaman tidak dapat dimuat</h1><p>${TextUtils.htmlEncode(msg)}</p>" +
            "<p><code>${TextUtils.htmlEncode(url)}</code></p></body></html>"

    private fun makeClient() = object : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            when (u.scheme) {
                "http", "https", "about", "data", "blob", "javascript", "file" -> return false
            }
            try {
                val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                else Intent(Intent.ACTION_VIEW, u)
                startActivity(i)
            } catch (e: Exception) {
                // tidak ada aplikasi yang bisa menangani tautan ini
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val t = tabFor(view) ?: return
            t.url = url
            t.loading = true
            t.progress = 0
            if (t === tabs.getOrNull(current)) updateChrome()
        }

        override fun onPageFinished(view: WebView, url: String) {
            val t = tabFor(view) ?: return
            t.url = url
            t.loading = false
            if (t === tabs.getOrNull(current)) updateChrome()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            val t = tabFor(view) ?: return
            t.url = url
            if (t === tabs.getOrNull(current)) updateChrome()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                val u = request.url.toString()
                view.loadDataWithBaseURL(u, errorHtml(u, error.description.toString()), "text/html", "UTF-8", u)
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            view.loadDataWithBaseURL(
                error.url, errorHtml(error.url, "Sertifikat keamanan situs ini tidak valid."),
                "text/html", "UTF-8", error.url
            )
        }
    }

    private fun makeChrome() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            val t = tabFor(view) ?: return
            t.progress = newProgress
            if (t === tabs.getOrNull(current)) updateChrome()
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tabFor(view)?.title = title ?: ""
        }

        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val newTab = createTab(null)
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = newTab.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            // Tutup di iterasi berikutnya; menghancurkan WebView dari dalam callback-nya sendiri berbahaya.
            screen.post {
                val i = tabs.indexOfFirst { it.webView === window }
                if (i >= 0) closeTab(i)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            screen.showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            screen.hideCustomView()
        }

        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_FILE)
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> result.confirm() }
                .setNegativeButton("Batal") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult
        ): Boolean {
            val input = EditText(this@MainActivity).apply { setText(defaultValue ?: "") }
            AlertDialog.Builder(this@MainActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton("Batal") { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    private fun showMenu() {
        val items = arrayOf(
            "＋  Tab baru",
            "▣  Daftar tab (${tabs.size})",
            "✕  Tutup tab ini",
            "⌂  Beranda",
            "⌨  Ketik / tempel teks…",
            "Resolusi layar: ${screen.cssW}×${screen.cssH}",
            "Kecepatan kursor: ${mouse.gainIndex + 1}/${MouseController.GAINS.size}",
            "Kecepatan scroll: ${mouse.scrollIndex + 1}/${MouseController.SCROLL_GAINS.size}",
            "Sentuh layar langsung: ${if (screen.touchEnabled) "AKTIF" else "MATI"}",
            "Keyboard sistem HP: ${if (imeAllowed) "AKTIF" else "MATI"}",
            "Hapus cookie & cache",
            "Tentang"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> createTab(HOME)
                    1 -> showTabList()
                    2 -> closeTab(current)
                    3 -> currentWebView()?.loadUrl(HOME)
                    4 -> showTypeDialog()
                    5 -> showResolutionDialog()
                    6 -> {
                        mouse.gainIndex = (mouse.gainIndex + 1) % MouseController.GAINS.size
                        prefs.edit().putInt("gain", mouse.gainIndex).apply()
                        mousePanel.updateSpeedLabel()
                        toast("Kecepatan kursor ${mouse.gainIndex + 1}/${MouseController.GAINS.size}")
                    }
                    7 -> {
                        mouse.scrollIndex = (mouse.scrollIndex + 1) % MouseController.SCROLL_GAINS.size
                        prefs.edit().putInt("scroll", mouse.scrollIndex).apply()
                        toast("Kecepatan scroll ${mouse.scrollIndex + 1}/${MouseController.SCROLL_GAINS.size}")
                    }
                    8 -> {
                        screen.touchEnabled = !screen.touchEnabled
                        prefs.edit().putBoolean("touch", screen.touchEnabled).apply()
                        toast(if (screen.touchEnabled) "Layar bisa disentuh langsung" else "Layar hanya dikontrol lewat mouse/keyboard")
                    }
                    9 -> {
                        imeAllowed = !imeAllowed
                        tabs.forEach { it.webView.allowIme = imeAllowed }
                        toast(if (imeAllowed) "Ketuk kolom teks di halaman untuk memunculkan keyboard HP" else "Keyboard HP dimatikan")
                    }
                    10 -> clearData()
                    11 -> showAbout()
                }
            }
            .show()
    }

    private fun showResolutionDialog() {
        val labels = RESOLUTIONS.map { "${it.first} × ${it.second}" + if (it.first >= 1920) "  (berat)" else "" }
        AlertDialog.Builder(this)
            .setTitle("Resolusi layar virtual")
            .setItems(labels.toTypedArray()) { _, which ->
                val r = RESOLUTIONS[which]
                screen.setVirtualSize(r.first, r.second)
                prefs.edit().putInt("res2", which).apply()
                updateResLabel()
            }
            .show()
    }

    private fun showTypeDialog() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        val input = EditText(this).apply {
            setText(clip)
            hint = "Teks yang akan diketik ke kolom aktif di halaman"
            minLines = 3
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Ketik / tempel teks")
            .setView(input)
            .setPositiveButton("Ketik") { _, _ -> insertText(input.text.toString()) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun insertText(text: String) {
        val wv = currentWebView() ?: return
        wv.requestFocus()
        val js = "(function(t){var e=document.activeElement;if(!e)return;" +
            "if(document.execCommand&&document.execCommand('insertText',false,t))return;" +
            "if('value' in e){var s=e.selectionStart||0,n=e.selectionEnd||0;" +
            "e.value=e.value.slice(0,s)+t+e.value.slice(n);e.selectionStart=e.selectionEnd=s+t.length;" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));}})(" + JSONObject.quote(text) + ");"
        wv.evaluateJavascript(js, null)
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        currentWebView()?.clearCache(true)
        toast("Cookie, penyimpanan situs, dan cache dihapus")
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("DeskPad")
            .setMessage(
                "Browser desktop di HP.\n\n" +
                    "• Layar atas = viewport desktop ${screen.cssW}×${screen.cssH} (UA Windows/Chrome).\n" +
                    "• KEYBOARD: tombol PC asli (Shift/Ctrl/Alt/Win — ketuk = sekali, tahan = kunci).\n" +
                    "• MOUSE: trackpad (1 jari gerak, ketuk klik, 2 jari scroll, ketuk 2 jari klik kanan, " +
                    "ketuk lalu tahan = drag), D-pad, A = klik kiri, B = klik kanan.\n" +
                    "• Menu ⋮ untuk resolusi, kecepatan, dan mode layar sentuh."
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
