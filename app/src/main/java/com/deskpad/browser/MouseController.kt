package com.deskpad.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Menyuntikkan event mouse ASLI (MotionEvent dengan TOOL_TYPE_MOUSE) ke WebView:
 * hover, klik kiri/kanan/tengah, drag, dan roda scroll. Urutan event meniru
 * urutan yang dikirim Android saat memakai mouse fisik.
 */
class MouseController(
    private val screen: ScreenFrame,
    private val metaState: () -> Int
) {
    companion object {
        const val LEFT = MotionEvent.BUTTON_PRIMARY
        const val RIGHT = MotionEvent.BUTTON_SECONDARY
        const val MIDDLE = MotionEvent.BUTTON_TERTIARY

        /** Kecepatan kursor (pengali gerak trackpad). */
        val GAINS = floatArrayOf(1.0f, 1.6f, 2.4f, 3.6f)

        /** Kecepatan scroll. */
        val SCROLL_GAINS = floatArrayOf(0.5f, 1.0f, 2.0f, 3.5f)
    }

    private val density = screen.resources.displayMetrics.density

    var gainIndex = 1
    var scrollIndex = 1

    /** Tombol yang sedang ditahan (bitmask BUTTON_*). */
    var buttons = 0
        private set

    /** Jika true, tombol kiri ditahan terus (untuk drag tanpa menahan jari). */
    var dragLock = false
        private set

    var onDragLockChanged: ((Boolean) -> Unit)? = null

    private var gestureDownTime = 0L

    val gain: Float get() = GAINS[gainIndex]

    init {
        // Posisi awal kursor di tengah layar akan diset saat ukuran diketahui.
    }

    fun centerCursor() {
        screen.cursor.setPosition(screen.width / 2f, screen.height / 2f)
    }

    // ---- Gerak ----

    /** Gerakkan kursor relatif (dalam piksel layar/container). */
    fun moveBy(dx: Float, dy: Float) {
        val c = screen.cursor
        val nx = (c.curX + dx).coerceIn(0f, (screen.width - 1).toFloat().coerceAtLeast(0f))
        val ny = (c.curY + dy).coerceIn(0f, (screen.height - 1).toFloat().coerceAtLeast(0f))
        if (nx == c.curX && ny == c.curY) return
        c.setPosition(nx, ny)
        if (buttons != 0) {
            send(MotionEvent.ACTION_MOVE)
        } else {
            send(MotionEvent.ACTION_HOVER_MOVE)
        }
    }

    // ---- Tombol ----

    fun press(button: Int) {
        if (buttons and button != 0) return
        val first = buttons == 0
        screen.currentWebView?.let { if (!it.hasFocus()) it.requestFocus() }
        if (first) gestureDownTime = SystemClock.uptimeMillis()
        buttons = buttons or button
        if (first) {
            send(MotionEvent.ACTION_DOWN)
        } else {
            send(MotionEvent.ACTION_MOVE)
        }
        send(MotionEvent.ACTION_BUTTON_PRESS, actionButton = button)
        screen.cursor.down = true
    }

    fun release(button: Int) {
        if (buttons and button == 0) return
        buttons = buttons and button.inv()
        send(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = button)
        if (buttons == 0) {
            send(MotionEvent.ACTION_UP)
            screen.cursor.down = false
        }
    }

    fun click(button: Int) {
        press(button)
        release(button)
    }

    fun setDragLock(on: Boolean) {
        if (on == dragLock) return
        dragLock = on
        if (on) press(LEFT) else release(LEFT)
        onDragLockChanged?.invoke(on)
    }

    // ---- Scroll ----

    /**
     * Scroll bergaya sentuh (natural): jari bergerak ke atas -> halaman bergulir ke bawah.
     * [dxPx]/[dyPx] dalam piksel layar HP.
     */
    fun scrollByPx(dxPx: Float, dyPx: Float) {
        val screenWdp = screen.width / density
        if (screenWdp <= 0f) return
        val cssPerDp = screen.cssW / screenWdp
        // Satu "tick" roda mouse ~ 64 piksel CSS pada WebView.
        val k = cssPerDp / 64f * SCROLL_GAINS[scrollIndex] / density
        val v = dyPx * k
        val h = -dxPx * k
        if (v == 0f && h == 0f) return
        send(MotionEvent.ACTION_SCROLL, vscroll = v, hscroll = h)
    }

    // ---- Pengiriman event ----

    private fun send(
        action: Int,
        actionButton: Int = 0,
        vscroll: Float = 0f,
        hscroll: Float = 0f
    ) {
        val wv = screen.currentWebView ?: return
        val scale = if (screen.scaleFactor > 0f) screen.scaleFactor else 1f
        val now = SystemClock.uptimeMillis()

        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = screen.cursor.curX / scale
            y = screen.cursor.curY / scale
            pressure = if (buttons != 0) 1f else 0f
            size = 1f
            if (action == MotionEvent.ACTION_SCROLL) {
                setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll)
            }
        }

        val isTouchPath = action == MotionEvent.ACTION_DOWN ||
            action == MotionEvent.ACTION_MOVE ||
            action == MotionEvent.ACTION_UP
        val downTime = if (isTouchPath) gestureDownTime else now

        val ev = MotionEvent.obtain(
            downTime, now, action, 1,
            arrayOf(props), arrayOf(coords),
            metaState(), buttons, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        try {
            if (isTouchPath) wv.dispatchTouchEvent(ev) else wv.dispatchGenericMotionEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    /** Jarak jari (px) -> jarak kursor (px) dengan akselerasi ringan. */
    fun accelerate(dx: Float, dy: Float): Pair<Float, Float> {
        val speed = hypot(dx, dy) / density
        val accel = 1f + (speed / 6f).coerceAtMost(1.6f)
        val g = gain * accel
        return Pair(dx * g, dy * g)
    }
}
