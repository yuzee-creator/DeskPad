package com.deskpad.browser

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.hypot

/**
 * Mengendalikan kursor dan tombol mouse virtual untuk WebView.
 *
 * Cara kerja klik (diubah supaya klik benar-benar sampai ke halaman):
 *  - Klik kiri  : dikirim sebagai KETUKAN JARI asli (DOWN lalu UP) di posisi kursor.
 *                 Chrome di WebView mengubahnya menjadi klik sungguhan (trusted).
 *                 Event mouse tombol-kiri tidak dipakai untuk klik karena
 *                 MotionEvent.setActionButton() bukan API publik Android, sehingga
 *                 Chrome tidak tahu tombol mana yang ditekan dan membuang kliknya.
 *  - Klik kanan : dikirim sebagai event JavaScript (mousedown, mouseup, contextmenu).
 *  - Drag       : jika kursor digerakkan saat tombol kiri ditahan, baru dikirim
 *                 event mouse (DOWN, MOVE, UP) seperti sebelumnya.
 *  - Hover dan scroll tetap memakai event mouse asli.
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

        /** Jeda antara DOWN dan UP pada ketukan jari (milidetik). */
        private const val TAP_MS = 40L
    }

    private val density = screen.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

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

    /** Tombol kiri sudah ditekan tapi belum dikirim ke WebView (menunggu: ketukan atau drag?). */
    private var leftPending = false

    /** Tombol kiri sedang dikirim sebagai event mouse (mode drag). */
    private var leftNative = false

    /**
     * Usaha terbaik: kalau perangkat mengizinkan, isi "action button" pada event mouse
     * lewat refleksi. Kalau tidak bisa (null), drag tetap jalan seadanya.
     */
    private val setActionButtonMethod: java.lang.reflect.Method? = try {
        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    } catch (e: Throwable) {
        null
    }

    val gain: Float get() = GAINS[gainIndex]

    fun centerCursor() {
        screen.cursor.setPosition(screen.width / 2f, screen.height / 2f)
    }

    // ---- Gerak ----

    /** Gerakkan kursor relatif (dalam piksel layar/container). */
    fun moveBy(dx: Float, dy: Float) {
        val c = screen.cursor
        val rawX = c.curX + dx
        val rawY = c.curY + dy
        val nx = rawX.coerceIn(0f, (screen.width - 1).toFloat().coerceAtLeast(0f))
        val ny = rawY.coerceIn(0f, (screen.height - 1).toFloat().coerceAtLeast(0f))
        // Saat zoom, sisa gerakan yang menabrak tepi layar dipakai untuk menggeser tampilan.
        val panDx = if (screen.zoom > 1f) rawX - nx else 0f
        val panDy = if (screen.zoom > 1f) rawY - ny else 0f
        if (nx == c.curX && ny == c.curY && panDx == 0f && panDy == 0f) return
        // Kursor bergerak sambil tombol kiri ditahan = drag: kirim "tekan" dulu di posisi lama.
        if (leftPending) beginMouseLeft()
        c.setPosition(nx, ny)
        if (panDx != 0f || panDy != 0f) screen.panBy(panDx, panDy)
        if (leftNative) {
            send(MotionEvent.ACTION_MOVE)
        } else {
            send(MotionEvent.ACTION_HOVER_MOVE)
        }
    }

    // ---- Tombol ----

    fun press(button: Int) {
        if (buttons and button != 0) return
        screen.currentWebView?.let { if (!it.hasFocus()) it.requestFocus() }
        buttons = buttons or button
        if (button == LEFT) leftPending = true
        screen.cursor.down = true
    }

    fun release(button: Int) {
        if (buttons and button == 0) return
        buttons = buttons and button.inv()
        if (button == LEFT) {
            if (leftNative) {
                leftNative = false
                send(MotionEvent.ACTION_BUTTON_RELEASE, actionButton = LEFT)
                send(MotionEvent.ACTION_UP)
            } else if (leftPending) {
                leftPending = false
                tap()
            }
        } else {
            clickOther(button)
        }
        if (buttons == 0) screen.cursor.down = false
    }

    fun click(button: Int) {
        press(button)
        release(button)
    }

    fun setDragLock(on: Boolean) {
        if (on == dragLock) return
        dragLock = on
        if (on) {
            press(LEFT)
            beginMouseLeft()
        } else {
            release(LEFT)
        }
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
        val cssPerDp = screen.cssW / screenWdp / screen.zoom
        // Satu "tick" roda mouse ~ 64 piksel CSS pada WebView.
        val k = cssPerDp / 64f * SCROLL_GAINS[scrollIndex] / density
        val v = dyPx * k
        val h = -dxPx * k
        if (v == 0f && h == 0f) return
        send(MotionEvent.ACTION_SCROLL, vscroll = v, hscroll = h)
    }

    // ---- Klik kiri: ketukan jari ----

    /** Ketukan jari di posisi kursor: DOWN, jeda singkat, lalu UP. */
    private fun tap() {
        val wv = screen.currentWebView ?: return
        val sx = screen.webX(screen.cursor.curX)
        val sy = screen.webY(screen.cursor.curY)
        val t = SystemClock.uptimeMillis()
        try {
            sendTouch(wv, MotionEvent.ACTION_DOWN, t, sx, sy)
        } catch (e: Exception) {
            return
        }
        handler.postDelayed({
            try {
                sendTouch(wv, MotionEvent.ACTION_UP, t, sx, sy)
            } catch (e: Exception) {
                // WebView mungkin sudah ditutup; abaikan.
            }
        }, TAP_MS)
    }

    /** [sx], [sy] sudah dalam koordinat WebView. */
    private fun sendTouch(wv: WebView, action: Int, downTime: Long, sx: Float, sy: Float) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = sx
            y = sy
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        }
        val ev = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1,
            arrayOf(props), arrayOf(coords),
            metaState(), 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            wv.dispatchTouchEvent(ev)
        } finally {
            ev.recycle()
        }
    }

    // ---- Klik kanan / tengah: event JavaScript ----

    private fun clickOther(button: Int) {
        val wv = screen.currentWebView ?: return
        // Piksel layar -> piksel WebView -> piksel CSS.
        val x = screen.webX(screen.cursor.curX) / density
        val y = screen.webY(screen.cursor.curY) / density
        val isRight = button == RIGHT
        val b = if (isRight) 2 else 1
        val js = "(function(x,y,b){" +
            "var e=document.elementFromPoint(x,y);if(!e)return;" +
            "function o(bs){return {bubbles:true,cancelable:true,view:window," +
            "clientX:x,clientY:y,screenX:x,screenY:y,button:b,buttons:bs};}" +
            "e.dispatchEvent(new MouseEvent('mousedown',o(b==2?2:4)));" +
            "e.dispatchEvent(new MouseEvent('mouseup',o(0)));" +
            "e.dispatchEvent(new MouseEvent(b==2?'contextmenu':'auxclick',o(0)));" +
            "})(" + x + "," + y + "," + b + ")"
        try {
            wv.evaluateJavascript(js, null)
        } catch (e: Exception) {
            // abaikan
        }
    }

    // ---- Drag: event mouse ----

    /** Ubah tombol kiri yang tertunda menjadi "ditahan" lewat event mouse (untuk drag). */
    private fun beginMouseLeft() {
        if (!leftPending) return
        leftPending = false
        leftNative = true
        gestureDownTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_BUTTON_PRESS, actionButton = LEFT)
    }

    // ---- Pengiriman event mouse (hover, scroll, drag) ----

    private fun send(
        action: Int,
        actionButton: Int = 0,
        vscroll: Float = 0f,
        hscroll: Float = 0f
    ) {
        val wv = screen.currentWebView ?: return
        val now = SystemClock.uptimeMillis()

        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = screen.webX(screen.cursor.curX)
            y = screen.webY(screen.cursor.curY)
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
        if (actionButton != 0) {
            try {
                setActionButtonMethod?.invoke(ev, actionButton)
            } catch (e: Throwable) {
                // API tersembunyi tidak tersedia di perangkat ini; abaikan.
            }
        }
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
