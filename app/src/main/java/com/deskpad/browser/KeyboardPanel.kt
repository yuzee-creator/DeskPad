package com.deskpad.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Keyboard PC (QWERTY ANSI) yang mengirim KeyEvent ASLI ke WebView.
 * Mendukung Shift/Ctrl/Alt/Win (tap = sekali pakai, tahan lama = kunci),
 * Caps Lock, lapisan Fn (F-keys <-> navigasi), dan auto-repeat saat tombol ditahan.
 */
class KeyboardPanel(
    context: Context,
    private val target: () -> WebView?
) : LinearLayout(context) {

    private enum class Mod { OFF, ONCE, LOCK }

    private enum class Type { NORMAL, SHIFT, CTRL, ALT, META, CAPS, FN, MACRO }

    private class KeyDef(
        val label: String,
        val code: Int,
        val shifted: String? = null,
        val weight: Float = 1f,
        val type: Type = Type.NORMAL,
        val textSp: Float = 13f,
        val letter: Boolean = false,
        val macroCtrlCode: Int = 0
    )

    private val handler = Handler(Looper.getMainLooper())

    private var shift = Mod.OFF
    private var ctrl = Mod.OFF
    private var alt = Mod.OFF
    private var meta = Mod.OFF
    private var caps = false
    private var fnLayer = false

    private val keyViews = mutableListOf<Pair<KeyDef, TextView>>()
    private val downTimes = HashMap<Int, Long>()
    private val repeaters = HashMap<Int, Runnable>()
    private val longPress = HashMap<KeyDef, Runnable>()
    private val longPressed = HashSet<KeyDef>()

    private lateinit var topRow: LinearLayout

    init {
        orientation = VERTICAL
        setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f))
        build()
    }

    // ------------------------------------------------------------------
    // Meta state
    // ------------------------------------------------------------------

    fun currentMeta(): Int {
        var m = 0
        if (shift != Mod.OFF) m = m or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (ctrl != Mod.OFF) m = m or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt != Mod.OFF) m = m or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (meta != Mod.OFF) m = m or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (caps) m = m or KeyEvent.META_CAPS_LOCK_ON
        return m
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun letters(s: String): List<KeyDef> = s.map {
        KeyDef(it.toString(), KeyEvent.KEYCODE_A + (it - 'a'), it.uppercaseChar().toString(), letter = true)
    }

    private fun build() {
        removeAllViews()
        keyViews.clear()

        // Baris atas: F-keys atau navigasi (bergantung layer Fn).
        topRow = newRow()
        fillTopRow()
        addView(topRow)

        // Baris angka
        val shiftedDigits = ")!@#$%^&*("
        val r1 = mutableListOf<KeyDef>()
        r1 += KeyDef("`", KeyEvent.KEYCODE_GRAVE, "~")
        for (d in 1..9) r1 += KeyDef(d.toString(), KeyEvent.KEYCODE_0 + d, shiftedDigits[d].toString())
        r1 += KeyDef("0", KeyEvent.KEYCODE_0, shiftedDigits[0].toString())
        r1 += KeyDef("-", KeyEvent.KEYCODE_MINUS, "_")
        r1 += KeyDef("=", KeyEvent.KEYCODE_EQUALS, "+")
        r1 += KeyDef("⌫", KeyEvent.KEYCODE_DEL, weight = 2f, textSp = 15f)
        addView(rowOf(r1))

        // Baris QWERTY
        val r2 = mutableListOf<KeyDef>()
        r2 += KeyDef("Tab", KeyEvent.KEYCODE_TAB, weight = 1.5f, textSp = 10f)
        r2 += letters("qwertyuiop")
        r2 += KeyDef("[", KeyEvent.KEYCODE_LEFT_BRACKET, "{")
        r2 += KeyDef("]", KeyEvent.KEYCODE_RIGHT_BRACKET, "}")
        r2 += KeyDef("\\", KeyEvent.KEYCODE_BACKSLASH, "|", weight = 1.5f)
        addView(rowOf(r2))

        // Baris ASDF
        val r3 = mutableListOf<KeyDef>()
        r3 += KeyDef("Caps", KeyEvent.KEYCODE_CAPS_LOCK, weight = 1.75f, type = Type.CAPS, textSp = 10f)
        r3 += letters("asdfghjkl")
        r3 += KeyDef(";", KeyEvent.KEYCODE_SEMICOLON, ":")
        r3 += KeyDef("'", KeyEvent.KEYCODE_APOSTROPHE, "\"")
        r3 += KeyDef("Enter", KeyEvent.KEYCODE_ENTER, weight = 2.25f, textSp = 11f)
        addView(rowOf(r3))

        // Baris ZXCV
        val r4 = mutableListOf<KeyDef>()
        r4 += KeyDef("Shift", KeyEvent.KEYCODE_SHIFT_LEFT, weight = 2.5f, type = Type.SHIFT, textSp = 11f)
        r4 += letters("zxcvbnm")
        r4 += KeyDef(",", KeyEvent.KEYCODE_COMMA, "<")
        r4 += KeyDef(".", KeyEvent.KEYCODE_PERIOD, ">")
        r4 += KeyDef("/", KeyEvent.KEYCODE_SLASH, "?")
        r4 += KeyDef("Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, weight = 2.5f, type = Type.SHIFT, textSp = 11f)
        addView(rowOf(r4))

        // Baris bawah
        val r5 = mutableListOf<KeyDef>()
        r5 += KeyDef("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, weight = 1.5f, type = Type.CTRL, textSp = 10f)
        r5 += KeyDef("Win", KeyEvent.KEYCODE_META_LEFT, weight = 1.25f, type = Type.META, textSp = 10f)
        r5 += KeyDef("Alt", KeyEvent.KEYCODE_ALT_LEFT, weight = 1.25f, type = Type.ALT, textSp = 10f)
        r5 += KeyDef("Space", KeyEvent.KEYCODE_SPACE, weight = 4.75f, textSp = 11f)
        r5 += KeyDef("Fn", 0, weight = 1.25f, type = Type.FN, textSp = 11f)
        r5 += KeyDef("◀", KeyEvent.KEYCODE_DPAD_LEFT, textSp = 12f)
        r5 += KeyDef("▲", KeyEvent.KEYCODE_DPAD_UP, textSp = 12f)
        r5 += KeyDef("▼", KeyEvent.KEYCODE_DPAD_DOWN, textSp = 12f)
        r5 += KeyDef("▶", KeyEvent.KEYCODE_DPAD_RIGHT, textSp = 12f)
        addView(rowOf(r5))

        refreshVisuals()
    }

    private fun fillTopRow() {
        topRow.removeAllViews()
        // buang view lama dari daftar (baris atas selalu ditambahkan pertama)
        keyViews.removeAll { it.second.parent == null }
        val defs = mutableListOf<KeyDef>()
        defs += KeyDef("Esc", KeyEvent.KEYCODE_ESCAPE, textSp = 9f)
        if (!fnLayer) {
            for (i in 1..12) defs += KeyDef("F$i", KeyEvent.KEYCODE_F1 + (i - 1), textSp = 9f)
            defs += KeyDef("Del", KeyEvent.KEYCODE_FORWARD_DEL, textSp = 9f)
        } else {
            defs += KeyDef("Home", KeyEvent.KEYCODE_MOVE_HOME, textSp = 9f)
            defs += KeyDef("End", KeyEvent.KEYCODE_MOVE_END, textSp = 9f)
            defs += KeyDef("PgUp", KeyEvent.KEYCODE_PAGE_UP, textSp = 9f)
            defs += KeyDef("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, textSp = 9f)
            defs += KeyDef("Ins", KeyEvent.KEYCODE_INSERT, textSp = 9f)
            defs += KeyDef("Del", KeyEvent.KEYCODE_FORWARD_DEL, textSp = 9f)
            defs += KeyDef("Copy", 0, type = Type.MACRO, textSp = 9f, macroCtrlCode = KeyEvent.KEYCODE_C)
            defs += KeyDef("Cut", 0, type = Type.MACRO, textSp = 9f, macroCtrlCode = KeyEvent.KEYCODE_X)
            defs += KeyDef("Paste", 0, type = Type.MACRO, textSp = 9f, macroCtrlCode = KeyEvent.KEYCODE_V)
            defs += KeyDef("All", 0, type = Type.MACRO, textSp = 9f, macroCtrlCode = KeyEvent.KEYCODE_A)
        }
        for (d in defs) topRow.addView(makeKey(d))
    }

    private fun newRow(): LinearLayout = LinearLayout(context).apply {
        orientation = HORIZONTAL
        layoutParams = lp(MATCH, 0, 1f)
    }

    private fun rowOf(keys: List<KeyDef>): LinearLayout {
        val row = newRow()
        for (k in keys) row.addView(makeKey(k))
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeKey(def: KeyDef): TextView {
        val isMod = def.type == Type.SHIFT || def.type == Type.CTRL || def.type == Type.ALT ||
            def.type == Type.META || def.type == Type.CAPS || def.type == Type.FN
        val normalBg = rounded(if (isMod || def.type == Type.MACRO) Pal.keyMod else Pal.key, dpf(5f))
        val pressedBg = rounded(Pal.keyPressed, dpf(5f))
        val activeBg = rounded(Pal.keyActive, dpf(5f))

        val tv = TextView(context).apply {
            text = def.label
            gravity = Gravity.CENTER
            setTextColor(Pal.textLight)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, def.textSp)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            isFocusable = false
            isFocusableInTouchMode = false
            isHapticFeedbackEnabled = true
            background = keySelector(normalBg, pressedBg, activeBg)
            layoutParams = lp(0, MATCH, def.weight).apply {
                setMargins(dpi(1.5f), dpi(1.5f), dpi(1.5f), dpi(1.5f))
            }
        }
        tv.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKeyDown(def)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    onKeyUp(def)
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    onKeyCancel(def)
                }
            }
            true
        }
        keyViews += Pair(def, tv)
        return tv
    }

    // ------------------------------------------------------------------
    // Input handling
    // ------------------------------------------------------------------

    private fun onKeyDown(def: KeyDef) {
        when (def.type) {
            Type.NORMAL -> {
                dispatch(KeyEvent.ACTION_DOWN, def.code, 0)
                scheduleRepeat(def.code)
            }
            Type.SHIFT, Type.CTRL, Type.ALT, Type.META -> {
                longPressed.remove(def)
                val r = Runnable {
                    longPressed.add(def)
                    setMod(def, Mod.LOCK)
                }
                longPress[def] = r
                handler.postDelayed(r, 480)
            }
            else -> Unit
        }
    }

    private fun onKeyUp(def: KeyDef) {
        when (def.type) {
            Type.NORMAL -> {
                cancelRepeat(def.code)
                dispatch(KeyEvent.ACTION_UP, def.code, 0)
                releaseOneShotModifiers()
            }
            Type.SHIFT, Type.CTRL, Type.ALT, Type.META -> {
                longPress.remove(def)?.let { handler.removeCallbacks(it) }
                if (!longPressed.remove(def)) {
                    // tap: OFF -> ONCE, selain itu -> OFF
                    setMod(def, if (getMod(def) == Mod.OFF) Mod.ONCE else Mod.OFF)
                }
            }
            Type.CAPS -> {
                caps = !caps
                dispatch(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CAPS_LOCK, 0)
                dispatch(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CAPS_LOCK, 0)
                refreshVisuals()
            }
            Type.FN -> {
                fnLayer = !fnLayer
                fillTopRow()
                refreshVisuals()
            }
            Type.MACRO -> sendCombo(KeyEvent.KEYCODE_CTRL_LEFT, def.macroCtrlCode)
        }
    }

    private fun onKeyCancel(def: KeyDef) {
        when (def.type) {
            Type.NORMAL -> {
                cancelRepeat(def.code)
                dispatch(KeyEvent.ACTION_UP, def.code, 0)
            }
            Type.SHIFT, Type.CTRL, Type.ALT, Type.META -> {
                longPress.remove(def)?.let { handler.removeCallbacks(it) }
                longPressed.remove(def)
            }
            else -> Unit
        }
    }

    private fun getMod(def: KeyDef): Mod = when (def.type) {
        Type.SHIFT -> shift
        Type.CTRL -> ctrl
        Type.ALT -> alt
        Type.META -> meta
        else -> Mod.OFF
    }

    /** Ubah status modifier dan kirim KeyEvent DOWN/UP modifier sesuai perubahannya. */
    private fun setMod(def: KeyDef, newState: Mod) {
        val old = getMod(def)
        if (old == newState) return
        val wasOn = old != Mod.OFF
        val willBeOn = newState != Mod.OFF
        // Update state dulu agar meta pada event ikut benar.
        when (def.type) {
            Type.SHIFT -> shift = newState
            Type.CTRL -> ctrl = newState
            Type.ALT -> alt = newState
            Type.META -> meta = newState
            else -> return
        }
        if (!wasOn && willBeOn) dispatch(KeyEvent.ACTION_DOWN, def.code, 0)
        if (wasOn && !willBeOn) dispatch(KeyEvent.ACTION_UP, def.code, 0)
        refreshVisuals()
    }

    private fun releaseOneShotModifiers() {
        var changed = false
        fun rel(state: Mod, code: Int, clear: () -> Unit) {
            if (state == Mod.ONCE) {
                clear()
                dispatch(KeyEvent.ACTION_UP, code, 0)
                changed = true
            }
        }
        rel(shift, KeyEvent.KEYCODE_SHIFT_LEFT) { shift = Mod.OFF }
        rel(ctrl, KeyEvent.KEYCODE_CTRL_LEFT) { ctrl = Mod.OFF }
        rel(alt, KeyEvent.KEYCODE_ALT_LEFT) { alt = Mod.OFF }
        rel(meta, KeyEvent.KEYCODE_META_LEFT) { meta = Mod.OFF }
        if (changed) refreshVisuals()
    }

    /** Kombinasi tombol: modifier DOWN, tombol DOWN, tombol UP, modifier UP. */
    private fun sendCombo(modCode: Int, keyCode: Int) {
        val metaFlags = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        dispatch(KeyEvent.ACTION_DOWN, modCode, 0, metaFlags)
        dispatch(KeyEvent.ACTION_DOWN, keyCode, 0, metaFlags)
        dispatch(KeyEvent.ACTION_UP, keyCode, 0, metaFlags)
        dispatch(KeyEvent.ACTION_UP, modCode, 0, 0)
    }

    private fun scheduleRepeat(code: Int) {
        cancelRepeat(code)
        var count = 0
        val r = object : Runnable {
            override fun run() {
                count++
                dispatch(KeyEvent.ACTION_DOWN, code, count)
                handler.postDelayed(this, 45)
            }
        }
        repeaters[code] = r
        handler.postDelayed(r, 480)
    }

    private fun cancelRepeat(code: Int) {
        repeaters.remove(code)?.let { handler.removeCallbacks(it) }
    }

    private fun dispatch(action: Int, code: Int, repeat: Int, metaOverride: Int? = null) {
        val wv = target() ?: return
        if (!wv.hasFocus()) wv.requestFocus()
        val now = SystemClock.uptimeMillis()
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) downTimes[code] = now
        val downTime = downTimes[code] ?: now
        val ev = KeyEvent(
            downTime, now, action, code, repeat,
            metaOverride ?: currentMeta(),
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD
        )
        wv.dispatchKeyEvent(ev)
    }

    // ------------------------------------------------------------------
    // Tampilan
    // ------------------------------------------------------------------

    private fun refreshVisuals() {
        val shiftOn = shift != Mod.OFF
        for ((def, tv) in keyViews) {
            when (def.type) {
                Type.NORMAL -> {
                    val upper = if (def.letter) (shiftOn != caps) else shiftOn
                    tv.text = if (upper && def.shifted != null) def.shifted else def.label
                }
                Type.SHIFT, Type.CTRL, Type.ALT, Type.META -> {
                    val m = getMod(def)
                    tv.isActivated = m != Mod.OFF
                    tv.paintFlags = if (m == Mod.LOCK) {
                        tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    } else {
                        tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                    }
                }
                Type.CAPS -> tv.isActivated = caps
                Type.FN -> tv.isActivated = fnLayer
                else -> Unit
            }
        }
    }

    /** Lepas semua tombol/modifier yang tertahan (dipanggil saat panel disembunyikan). */
    fun releaseAll() {
        for (code in repeaters.keys.toList()) cancelRepeat(code)
        for (r in longPress.values) handler.removeCallbacks(r)
        longPress.clear()
        longPressed.clear()
        for (def in keyViews.map { it.first }) {
            if (def.type == Type.SHIFT || def.type == Type.CTRL || def.type == Type.ALT || def.type == Type.META) {
                if (getMod(def) != Mod.OFF) setMod(def, Mod.OFF)
            }
        }
    }
}
