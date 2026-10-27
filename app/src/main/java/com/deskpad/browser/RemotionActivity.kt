package com.deskpad.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.File

class RemotionActivity : Activity() {

    private lateinit var web: WebView

    inner class Bridge {
        @JavascriptInterface
        fun save(dataUrl: String, name: String) {
            val ok = try {
                saveBytes(Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT), name)
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                Toast.makeText(
                    this@RemotionActivity,
                    if (ok) "Tersimpan di Downloads: $name" else "Gagal menyimpan video",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = FrameLayout(this).apply {
            setBackgroundColor(0xFF16181D.toInt())
            fitsSystemWindows = true
        }
        web = WebView(this)
        box.addView(web, FrameLayout.LayoutParams(-1, -1))
        setContentView(box)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "DeskBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }
        }

        web.setDownloadListener { url, _, cd, mime, _ ->
            if (url.startsWith("blob:")) {
                val js = "(function(){var x=new XMLHttpRequest();x.open('GET','" + url +
                    "',true);x.responseType='blob';x.onload=function(){var r=new FileReader();" +
                    "r.onloadend=function(){DeskBridge.save(r.result,'remotion-'+Date.now()+'.mp4')};" +
                    "r.readAsDataURL(x.response)};x.send()})()"
                web.evaluateJavascript(js, null)
            } else {
                try {
                    val req = DownloadManager.Request(Uri.parse(url))
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, cd, mime)
                        )
                    (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                    Toast.makeText(this, "Mengunduh ke Downloads...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }

        web.loadUrl("file:///android_asset/remotion.html")
    }

    private fun saveBytes(bytes: ByteArray, name: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return false
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
