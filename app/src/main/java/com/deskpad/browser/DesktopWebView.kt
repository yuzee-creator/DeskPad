package com.deskpad.browser

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * WebView desktop untuk DeskPad.
 *
 * - Secara default TIDAK memunculkan keyboard sistem HP. Pengetikan dilakukan lewat
 *   keyboard virtual aplikasi (KeyEvent asli). Jika [allowIme] = true, keyboard HP boleh muncul.
 * - Mengurangi tanda-tanda "WebView tertanam" supaya Google lebih mungkin menerima login:
 *   header X-Requested-With dihapus, dan skrip awal dokumen membuat properti browser
 *   (platform, userAgentData, window.chrome, layar) konsisten dengan Chrome Windows.
 * - Mencegah kedipan/layar kosong: WebView digambar ke layer GPU sendiri sehingga tidak
 *   dihitung ulang oleh Chromium tiap frame saat induknya diskalakan.
 */
class DesktopWebView(context: Context) : WebView(context) {

    var allowIme: Boolean = false

    private var layerOn = false

    init {
        removeEmbeddedTraces()
    }

    override fun onCheckIsTextEditor(): Boolean = allowIme && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (allowIme) super.onCreateInputConnection(outAttrs) else null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Layer GPU tidak boleh melebihi batas tekstur perangkat (aman: 4096 piksel).
        val want = w in 1..MAX_LAYER_PX && h in 1..MAX_LAYER_PX
        if (want != layerOn) {
            layerOn = want
            setLayerType(if (want) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
        }
    }

    private fun removeEmbeddedTraces() {
        // 1. Jangan kirim header X-Requested-With (berisi nama paket aplikasi) ke situs mana pun.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
            }
        } catch (e: Throwable) {
            // fitur tidak tersedia di WebView perangkat ini
        }
        // 2. Skrip yang berjalan sebelum skrip halaman mana pun.
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val major = Regex("Chrome/(\\d+)").find(settings.userAgentString ?: "")
                    ?.groupValues?.get(1) ?: "130"
                WebViewCompat.addDocumentStartJavaScript(
                    this, STEALTH_JS.replace("__V__", major), setOf("https://*.google.com", "https://google.com", "https://*.youtube.com")
                )
            }
        } catch (e: Throwable) {
            // fitur tidak tersedia di WebView perangkat ini
        }
    }

    companion object {
        private const val MAX_LAYER_PX = 4096

        private val STEALTH_JS = """
(function () {
  try {
    var V = '__V__';
    var def = function (obj, key, val) {
      try { Object.defineProperty(obj, key, { get: function () { return val; }, configurable: true }); } catch (e) {}
    };
    if (!window.chrome) {
      window.chrome = { app: { isInstalled: false }, runtime: {}, loadTimes: function () { return {}; }, csi: function () { return {}; } };
    }
    var brands = [
      { brand: 'Not/A)Brand', version: '8' },
      { brand: 'Chromium', version: V },
      { brand: 'Google Chrome', version: V }
    ];
    var full = [
      { brand: 'Not/A)Brand', version: '8.0.0.0' },
      { brand: 'Chromium', version: V + '.0.0.0' },
      { brand: 'Google Chrome', version: V + '.0.0.0' }
    ];
    var uad = {
      brands: brands, mobile: false, platform: 'Windows',
      getHighEntropyValues: function () {
        return Promise.resolve({
          brands: brands, mobile: false, platform: 'Windows', platformVersion: '10.0.0',
          architecture: 'x86', bitness: '64', model: '', uaFullVersion: V + '.0.0.0', fullVersionList: full
        });
      },
      toJSON: function () { return { brands: brands, mobile: false, platform: 'Windows' }; }
    };
    def(navigator, 'userAgentData', uad);
    def(navigator, 'platform', 'Win32');
    def(navigator, 'maxTouchPoints', 0);
    var plug = [
      { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chrome PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'Microsoft Edge PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },
      { name: 'WebKit built-in PDF', filename: 'internal-pdf-viewer', description: 'Portable Document Format' }
    ];
    plug.item = function (i) { return plug[i] || null; };
    plug.namedItem = function (n) { for (var i = 0; i < plug.length; i++) { if (plug[i].name === n) return plug[i]; } return null; };
    def(navigator, 'plugins', plug);
    var W = 1920, H = 1080;
    def(screen, 'width', W);
    def(screen, 'height', H);
    def(screen, 'availWidth', W);
    def(screen, 'availHeight', H - 40);
  } catch (e) {}
})();
"""
    }
}
