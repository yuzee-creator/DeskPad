package com.deskpad.browser

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** Palet warna bergaya Game Boy. */
object Pal {
    val body = Color.parseColor("#CFCDC4")
    val bodyShade = Color.parseColor("#B8B6AC")
    val bezel = Color.parseColor("#4B4F63")
    val bezelDark = Color.parseColor("#34374A")
    val label = Color.parseColor("#2B2F8F")
    val magenta = Color.parseColor("#A3174F")
    val magentaDark = Color.parseColor("#7A0F3A")
    val key = Color.parseColor("#464A5F")
    val keyPressed = Color.parseColor("#2A2D3C")
    val keyActive = Color.parseColor("#A3174F")
    val keyMod = Color.parseColor("#363A4D")
    val pad = Color.parseColor("#2E3142")
    val padGrid = Color.parseColor("#3C4058")
    val led = Color.parseColor("#E5322D")
    val urlBg = Color.parseColor("#2A2D3D")
    val textLight = Color.parseColor("#EDEDF2")
    val textDim = Color.parseColor("#9A9DB2")
}

fun Context.dpf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Float): Int = dpf(v).toInt()

fun View.dpf(v: Float): Float = context.dpf(v)

fun View.dpi(v: Float): Int = context.dpi(v)

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun oval(color: Int, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

/** Selector untuk tombol: normal / ditekan / aktif (activated). */
fun keySelector(normal: Drawable, pressed: Drawable, active: Drawable? = null): StateListDrawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        if (active != null) addState(intArrayOf(android.R.attr.state_activated), active)
        addState(intArrayOf(), normal)
    }

fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
