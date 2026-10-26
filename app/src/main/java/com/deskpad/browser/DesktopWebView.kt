package com.deskpad.browser

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * WebView yang secara default TIDAK memunculkan keyboard sistem HP.
 * Pengetikan dilakukan lewat keyboard virtual aplikasi (KeyEvent asli).
 * Jika [allowIme] = true, keyboard sistem HP boleh muncul (mis. untuk IME bahasa lain).
 */
class DesktopWebView(context: Context) : WebView(context) {

    var allowIme: Boolean = false

    override fun onCheckIsTextEditor(): Boolean = allowIme && super.onCheckIsTextEditor()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? =
        if (allowIme) super.onCreateInputConnection(outAttrs) else null
}
