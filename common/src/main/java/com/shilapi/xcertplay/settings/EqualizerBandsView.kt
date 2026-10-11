package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * A horizontal row of vertical equalizer bands; drag inside a column to set its level in dB.
 * The band is chosen where the finger lands and stays locked for the rest of the gesture, so a
 * drag that wanders sideways on a resistive panel never edits the neighbouring frequency.
 */
class EqualizerBandsView(context: Context) : View(context) {
    var centersHz: List<Int> = emptyList()
        set(value) { field = value; levelsDb = IntArray(value.size); invalidate() }
    var minDb = -12
    var maxDb = 12
    var levelsDb = IntArray(0)
        set(value) { field = value; invalidate() }
    var accent = 0xFFA6C8FF.toInt()
    var textColor = 0xFFF1F5FC.toInt()
    var mutedColor = 0xFFA8B6CA.toInt()
    var trackColor = 0x33FFFFFF
    var onLevelChanged: (band: Int, db: Int) -> Unit = { _, _ -> }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity
    private fun dp(v: Float) = v * density

    private val topPad get() = dp(26f)
    private val bottomPad get() = dp(28f)
    private var activeBand = -1
    private var activePointer = MotionEvent.INVALID_POINTER_ID

    override fun onDraw(canvas: Canvas) {
        val n = centersHz.size
        if (n == 0 || maxDb <= minDb || height <= topPad + bottomPad) return
        val colW = width.toFloat() / n
        val top = topPad
        val bottom = height - bottomPad
        val zeroY = top + (bottom - top) * (maxDb.toFloat() / (maxDb - minDb))
        text.textSize = 13f * scaledDensity   // follows the system font scale, unlike dp
        for (band in 0 until n) {
            val cx = colW * band + colW / 2
            val level = levelsDb.getOrElse(band) { 0 }.coerceIn(minDb, maxDb)
            val y = top + (bottom - top) * ((maxDb - level).toFloat() / (maxDb - minDb))
            val selected = band == activeBand
            paint.color = trackColor; paint.strokeWidth = dp(4f); paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx, top, cx, bottom, paint)
            paint.color = accent; paint.strokeWidth = dp(if (selected) 8f else 6f)
            canvas.drawLine(cx, zeroY, cx, y, paint)
            paint.color = mutedColor; paint.strokeWidth = dp(1f)
            canvas.drawLine(cx - colW * 0.3f, zeroY, cx + colW * 0.3f, zeroY, paint)
            paint.color = accent
            canvas.drawCircle(cx, y, dp(if (selected) 12f else 9f), paint)
            text.color = textColor
            canvas.drawText(if (level > 0) "+$level" else "$level", cx, top - dp(9f), text)
            text.color = mutedColor
            val hz = centersHz[band]
            canvas.drawText(if (hz >= 1000) "${if (hz % 1000 == 0) hz / 1000 else hz / 1000f}k" else "$hz", cx, height - dp(7f), text)
        }
    }

    private fun updateFromY(y: Float) {
        val span = height - bottomPad - topPad
        if (activeBand !in centersHz.indices || span <= 0f || maxDb <= minDb) return
        val fraction = ((y - topPad) / span).coerceIn(0f, 1f)
        val value = (maxDb - fraction * (maxDb - minDb)).roundToInt().coerceIn(minDb, maxDb)
        if (levelsDb.size != centersHz.size) levelsDb = levelsDb.copyOf(centersHz.size)
        if (levelsDb[activeBand] == value) return
        val next = levelsDb.copyOf(); next[activeBand] = value; levelsDb = next
        onLevelChanged(activeBand, value)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || centersHz.isEmpty() || width <= 0 || maxDb <= minDb) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                activeBand = (event.x / (width.toFloat() / centersHz.size)).toInt().coerceIn(centersHz.indices)
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromY(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) updateFromY(event.getY(index))
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) updateFromY(event.getY(index))
                performClick()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) != activePointer) return true
            }
            MotionEvent.ACTION_CANCEL -> Unit
            else -> return activeBand >= 0
        }
        activeBand = -1
        activePointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
        return true
    }
}
