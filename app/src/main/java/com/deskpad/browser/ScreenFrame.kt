package com.deskpad.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * "Layar" Game Boy: jendela landscape 16:9 yang menampilkan WebView berukuran
 * desktop sungguhan (mis. 1280x720 piksel CSS) lalu diskalakan agar muat di lebar HP.
 * Viewport halaman web benar-benar 1280x720, bukan sekadar "zoom out".
 */
class ScreenFrame(context: Context) : FrameLayout(context) {

    /** Ukuran viewport virtual dalam piksel CSS. */
    var cssW = 1280
        private set
    var cssH = 720
        private set

    private val density = resources.displayMetrics.density

    /** Wadah WebView; ukurannya = viewport virtual, lalu diskalakan ke layar. */
    val host = FrameLayout(context)

    /** Lapisan untuk video layar penuh (tidak diskalakan). */
    private val fullscreenLayer = FrameLayout(context)

    val cursor = CursorView(context)

    /** Skala dari piksel WebView -> piksel layar (container). */
    var scaleFactor = 1f
        private set

    /** Jika true, sentuhan langsung ke layar diteruskan ke halaman (mode layar sentuh). */
    var touchEnabled = false

    var currentWebView: WebView? = null

    /** Dipanggil setiap ukuran layar berubah (dipakai untuk menaruh kursor di tengah pertama kali). */
    var onSized: (() -> Unit)? = null

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        host.pivotX = 0f
        host.pivotY = 0f
        addView(host, LayoutParams((cssW * density).roundToInt(), (cssH * density).roundToInt()))
        addView(fullscreenLayer, LayoutParams(MATCH, MATCH))
        addView(cursor, LayoutParams(MATCH, MATCH))
        fullscreenLayer.visibility = View.GONE
    }

    fun setVirtualSize(w: Int, h: Int) {
        cssW = w
        cssH = h
        host.layoutParams = LayoutParams((cssW * density).roundToInt(), (cssH * density).roundToInt())
        updateScale()
        requestLayout()
    }

    /** Lebar viewport virtual dalam piksel WebView. */
    fun virtualPxWidth(): Float = cssW * density

    private fun updateScale() {
        if (width <= 0) return
        scaleFactor = width / virtualPxWidth()
        host.scaleX = scaleFactor
        host.scaleY = scaleFactor
        cursor.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * cssH.toFloat() / cssW.toFloat()).roundToInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateScale()
        cursor.bounds(w.toFloat(), h.toFloat())
        if (w > 0 && h > 0) onSized?.invoke()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = !touchEnabled

    override fun onTouchEvent(event: MotionEvent): Boolean = !touchEnabled

    // ---- Video layar penuh ----

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        fullscreenLayer.addView(view, LayoutParams(MATCH, MATCH))
        fullscreenLayer.visibility = View.VISIBLE
    }

    fun hideCustomView() {
        val v = customView ?: return
        fullscreenLayer.removeView(v)
        fullscreenLayer.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    fun isCustomViewShown(): Boolean = customView != null
}

/** Kursor mouse virtual berbentuk panah, digambar di atas layar. */
class CursorView(context: Context) : View(context) {

    var curX = 40f
        private set
    var curY = 40f
        private set
    var down = false
        set(value) {
            field = value
            invalidate()
        }

    private var maxX = 0f
    private var maxY = 0f

    private val arrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.magenta
        style = Paint.Style.STROKE
    }

    init {
        val u = context.dpf(1f)
        stroke.strokeWidth = u * 1.2f
        ring.strokeWidth = u * 2f
        val s = u * 1.05f
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 17f * s)
        arrow.lineTo(4.4f * s, 13.2f * s)
        arrow.lineTo(7.6f * s, 20.5f * s)
        arrow.lineTo(10.6f * s, 19.2f * s)
        arrow.lineTo(7.5f * s, 12f * s)
        arrow.lineTo(13f * s, 12f * s)
        arrow.close()
    }

    fun bounds(w: Float, h: Float) {
        maxX = w - 1f
        maxY = h - 1f
        setPosition(curX, curY)
    }

    fun setPosition(nx: Float, ny: Float) {
        curX = nx.coerceIn(0f, maxOf(0f, maxX))
        curY = ny.coerceIn(0f, maxOf(0f, maxY))
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(curX, curY)
        if (down) {
            canvas.drawCircle(0f, 0f, context.dpf(9f), ring)
        }
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, stroke)
        canvas.restoreToCount(save)
    }
}
