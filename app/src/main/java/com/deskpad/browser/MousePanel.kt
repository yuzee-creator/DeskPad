package com.deskpad.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Panel kontrol mouse bergaya Game Boy:
 *  - Trackpad: 1 jari = gerak kursor, ketuk = klik kiri, ketuk 2 jari = klik kanan,
 *    geser 2 jari = scroll, ketuk lalu tahan & geser = drag.
 *  - Strip scroll di sisi kanan trackpad.
 *  - D-pad: gerak kursor presisi (tahan = makin cepat).
 *  - A = klik kiri (tahan untuk drag), B = klik kanan.
 *  - SELECT = kunci drag, START = ganti kecepatan kursor.
 */
class MousePanel(
    context: Context,
    private val mouse: MouseController
) : LinearLayout(context) {

    private val dragPill: TextView
    private val speedPill: TextView

    init {
        orientation = VERTICAL
        setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(2f))

        // --- Trackpad + strip scroll ---
        val padRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = lp(MATCH, 0, 1f)
        }
        val trackpad = TrackpadView(context, mouse).apply {
            layoutParams = lp(0, MATCH, 1f).apply { setMargins(0, 0, dpi(6f), 0) }
        }
        val strip = ScrollStripView(context, mouse).apply {
            layoutParams = lp(dpi(38f), MATCH)
        }
        padRow.addView(trackpad)
        padRow.addView(strip)
        addView(padRow)

        // --- Baris bawah: D-pad | SELECT/START | B A ---
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, dpi(112f)).apply { topMargin = dpi(6f) }
        }

        val dpad = DPadView(context, mouse).apply {
            layoutParams = lp(dpi(108f), dpi(108f))
        }
        bottom.addView(dpad)

        // Tombol pil di tengah, miring seperti Game Boy.
        val center = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(0, MATCH, 1f)
            rotation = -22f
        }
        dragPill = pill("DRAG", "KUNCI DRAG")
        speedPill = pill("SPEED", "KECEPATAN")
        center.addView(pillColumn(dragPill, "SELECT"))
        center.addView(pillColumn(speedPill, "START"))
        bottom.addView(center)

        // A / B diagonal.
        val ab = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = lp(dpi(136f), MATCH)
        }
        val bBtn = circleButton("B", onDown = { mouse.press(MouseController.RIGHT) }, onUp = { mouse.release(MouseController.RIGHT) })
        val aBtn = circleButton("A", onDown = { mouse.press(MouseController.LEFT) }, onUp = { mouse.release(MouseController.LEFT) })
        val bCol = labeled(bBtn, "KLIK KANAN").apply { layoutParams = lp(0, WRAP, 1f).apply { topMargin = dpi(26f) } }
        val aCol = labeled(aBtn, "KLIK KIRI").apply { layoutParams = lp(0, WRAP, 1f).apply { bottomMargin = dpi(26f) } }
        ab.addView(bCol)
        ab.addView(aCol)
        bottom.addView(ab)
        addView(bottom)

        updateSpeedLabel()
        mouse.onDragLockChanged = { on -> dragPill.isActivated = on }
        dragPill.setOnTouchListener(pillTouch { mouse.setDragLock(!mouse.dragLock) })
        speedPill.setOnTouchListener(pillTouch {
            mouse.gainIndex = (mouse.gainIndex + 1) % MouseController.GAINS.size
            updateSpeedLabel()
        })
    }

    fun updateSpeedLabel() {
        speedPill.text = "SPD ${mouse.gainIndex + 1}"
    }

    private fun pill(text: String, @Suppress("UNUSED_PARAMETER") hint: String): TextView =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Pal.textLight)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.DEFAULT_BOLD
            isFocusable = false
            background = keySelector(
                rounded(Pal.bezel, dpf(12f)),
                rounded(Pal.keyPressed, dpf(12f)),
                rounded(Pal.keyActive, dpf(12f))
            )
            layoutParams = lp(dpi(58f), dpi(22f))
        }

    private fun pillColumn(pill: TextView, caption: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = lp(WRAP, WRAP).apply { setMargins(dpi(5f), 0, dpi(5f), 0) }
            addView(pill)
            addView(TextView(context).apply {
                text = caption
                setTextColor(Pal.label)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

    private fun labeled(btn: View, caption: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(btn)
            addView(TextView(context).apply {
                text = caption
                setTextColor(Pal.label)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun circleButton(label: String, onDown: () -> Unit, onUp: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            isFocusable = false
            background = keySelector(
                oval(Pal.magenta, Pal.magentaDark, dpi(2f)),
                oval(Pal.magentaDark, Pal.magentaDark, dpi(2f))
            )
            layoutParams = lp(dpi(54f), dpi(54f))
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onDown()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        onUp()
                    }
                }
                true
            }
        }

    private fun pillTouch(action: () -> Unit) = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                action()
            }
            MotionEvent.ACTION_CANCEL -> v.isPressed = false
        }
        true
    }
}

// ----------------------------------------------------------------------
// Trackpad
// ----------------------------------------------------------------------

class TrackpadView(context: Context, private val mouse: MouseController) : View(context) {

    private enum class Mode { IDLE, MOVE, SCROLL, DEAD }

    private var mode = Mode.IDLE
    private val slop = dpf(8f)

    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var dragging = false
    private var lastTapUp = 0L

    private var twoDownTime = 0L
    private var twoMoved = false
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var lastCX = 0f
    private var lastCY = 0f

    private val bg = rounded(Pal.pad, dpf(12f), Pal.bezelDark, dpi(2f))
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.padGrid }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.textDim
        textAlign = Paint.Align.CENTER
        textSize = dpf(10f)
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        bg.setBounds(0, 0, width, height)
        bg.draw(canvas)
        val gap = dpf(18f)
        var y = gap
        while (y < height - gap / 2) {
            var x = gap
            while (x < width - gap / 2) {
                canvas.drawCircle(x, y, dpf(1.2f), dot)
                x += gap
            }
            y += gap
        }
        canvas.drawText("TRACKPAD", width / 2f, height / 2f - dpf(6f), hint)
        canvas.drawText("1 jari: gerak • ketuk: klik", width / 2f, height / 2f + dpf(8f), hint)
        canvas.drawText("2 jari: scroll • ketuk 2 jari: klik kanan", width / 2f, height / 2f + dpf(22f), hint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.MOVE
                downTime = e.eventTime
                startX = e.x; startY = e.y
                lastX = e.x; lastY = e.y
                moved = false
                dragging = false
                if (lastTapUp != 0L && e.eventTime - lastTapUp < 300 && !mouse.dragLock) {
                    // ketuk lalu langsung sentuh lagi: tombol kiri ditekan (klik ganda / drag)
                    dragging = true
                    mouse.press(MouseController.LEFT)
                }
                lastTapUp = 0L
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && mode != Mode.DEAD) {
                    if (dragging) {
                        mouse.release(MouseController.LEFT)
                        dragging = false
                    }
                    mode = Mode.SCROLL
                    twoDownTime = e.eventTime
                    twoMoved = false
                    val cx = (e.getX(0) + e.getX(1)) / 2f
                    val cy = (e.getY(0) + e.getY(1)) / 2f
                    twoStartX = cx; twoStartY = cy
                    lastCX = cx; lastCY = cy
                } else {
                    mode = Mode.DEAD
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    val s = if (dragging) slop / 3f else slop
                    if (!moved && hypot(e.x - startX, e.y - startY) > s) moved = true
                    if (moved) {
                        val (mx, my) = mouse.accelerate(dx, dy)
                        mouse.moveBy(mx, my)
                    }
                }
                Mode.SCROLL -> {
                    if (e.pointerCount >= 2) {
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val cy = (e.getY(0) + e.getY(1)) / 2f
                        if (!twoMoved && hypot(cx - twoStartX, cy - twoStartY) > slop) twoMoved = true
                        if (twoMoved) mouse.scrollByPx(cx - lastCX, cy - lastCY)
                        lastCX = cx; lastCY = cy
                    }
                }
                else -> Unit
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.SCROLL && e.pointerCount == 2) {
                    if (!twoMoved && e.eventTime - twoDownTime < 300) {
                        mouse.click(MouseController.RIGHT)
                    }
                }
                mode = Mode.DEAD
            }

            MotionEvent.ACTION_UP -> {
                if (mode == Mode.MOVE) {
                    if (dragging) {
                        mouse.release(MouseController.LEFT)
                        dragging = false
                        lastTapUp = 0L
                    } else if (!moved && e.eventTime - downTime < 260) {
                        if (mouse.dragLock) {
                            mouse.setDragLock(false)
                        } else {
                            mouse.click(MouseController.LEFT)
                            lastTapUp = e.eventTime
                        }
                    }
                }
                mode = Mode.IDLE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    mouse.release(MouseController.LEFT)
                    dragging = false
                }
                mode = Mode.IDLE
            }
        }
        return true
    }
}

// ----------------------------------------------------------------------
// Strip scroll
// ----------------------------------------------------------------------

class ScrollStripView(context: Context, private val mouse: MouseController) : View(context) {

    private var lastY = 0f
    private var offset = 0f

    private val bg = rounded(Pal.pad, dpf(12f), Pal.bezelDark, dpi(2f))
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.padGrid
        strokeWidth = dpf(2f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.textDim
        textAlign = Paint.Align.CENTER
        textSize = dpf(8f)
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        bg.setBounds(0, 0, width, height)
        bg.draw(canvas)
        val gap = dpf(12f)
        var y = (offset % gap + gap) % gap
        while (y < height) {
            canvas.drawLine(width * 0.25f, y, width * 0.75f, y, line)
            y += gap
        }
        canvas.save()
        canvas.rotate(-90f, width / 2f, height / 2f)
        canvas.drawText("SCROLL", width / 2f, height / 2f + dpf(3f), label)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - lastY
                lastY = e.y
                offset += dy
                // seperti roda mouse: geser ke bawah = scroll ke bawah
                mouse.scrollByPx(0f, -dy)
                invalidate()
            }
        }
        return true
    }
}

// ----------------------------------------------------------------------
// D-pad
// ----------------------------------------------------------------------

class DPadView(context: Context, private val mouse: MouseController) : View(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var dirX = 0
    private var dirY = 0
    private var t0 = 0L
    private val density = resources.displayMetrics.density

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.pad }
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.keyActive }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Pal.textDim
        style = Paint.Style.FILL
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.bezelDark }

    private val tick = object : Runnable {
        override fun run() {
            if (dirX == 0 && dirY == 0) return
            val held = SystemClock.uptimeMillis() - t0
            val step = (1.5f + held / 90f).coerceAtMost(13f) * density
            val n = if (dirX != 0 && dirY != 0) 0.7071f else 1f
            mouse.moveBy(dirX * step * n, dirY * step * n)
            handler.postDelayed(this, 16)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val arm = w / 3f
        val r = dpf(8f)
        val horiz = RectF(0f, arm, w, 2 * arm)
        val vert = RectF(arm, 0f, 2 * arm, h)
        canvas.drawRoundRect(horiz, r, r, body)
        canvas.drawRoundRect(vert, r, r, body)

        // sorot arah aktif
        if (dirX != 0 || dirY != 0) {
            val pad = dpf(3f)
            if (dirX < 0) canvas.drawRoundRect(RectF(pad, arm + pad, arm, 2 * arm - pad), r, r, hot)
            if (dirX > 0) canvas.drawRoundRect(RectF(2 * arm, arm + pad, w - pad, 2 * arm - pad), r, r, hot)
            if (dirY < 0) canvas.drawRoundRect(RectF(arm + pad, pad, 2 * arm - pad, arm), r, r, hot)
            if (dirY > 0) canvas.drawRoundRect(RectF(arm + pad, 2 * arm, 2 * arm - pad, h - pad), r, r, hot)
        }

        canvas.drawCircle(w / 2f, h / 2f, dpf(9f), hole)
        val a = dpf(7f)
        triangle(canvas, w / 2f, arm / 2f, 0, -1, a)
        triangle(canvas, w / 2f, h - arm / 2f, 0, 1, a)
        triangle(canvas, arm / 2f, h / 2f, -1, 0, a)
        triangle(canvas, w - arm / 2f, h / 2f, 1, 0, a)
    }

    private fun triangle(c: Canvas, cx: Float, cy: Float, dx: Int, dy: Int, s: Float) {
        val p = android.graphics.Path()
        if (dy != 0) {
            p.moveTo(cx, cy + dy * s * 0.6f)
            p.lineTo(cx - s, cy - dy * s * 0.5f)
            p.lineTo(cx + s, cy - dy * s * 0.5f)
        } else {
            p.moveTo(cx + dx * s * 0.6f, cy)
            p.lineTo(cx - dx * s * 0.5f, cy - s)
            p.lineTo(cx - dx * s * 0.5f, cy + s)
        }
        p.close()
        c.drawPath(p, arrow)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val cx = width / 2f
                val cy = height / 2f
                val dx = e.x - cx
                val dy = e.y - cy
                var nx = 0
                var ny = 0
                if (hypot(dx, dy) > dpf(10f)) {
                    val ax = abs(dx)
                    val ay = abs(dy)
                    if (ax > ay * 0.45f) nx = if (dx > 0) 1 else -1
                    if (ay > ax * 0.45f) ny = if (dy > 0) 1 else -1
                }
                if (nx != dirX || ny != dirY) {
                    val wasIdle = dirX == 0 && dirY == 0
                    dirX = nx
                    dirY = ny
                    if (dirX != 0 || dirY != 0) {
                        if (wasIdle) {
                            t0 = SystemClock.uptimeMillis()
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            handler.removeCallbacks(tick)
                            handler.post(tick)
                        }
                    } else {
                        handler.removeCallbacks(tick)
                    }
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dirX = 0
                dirY = 0
                handler.removeCallbacks(tick)
                invalidate()
            }
        }
        return true
    }
}
