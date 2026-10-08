package com.shilapi.xcertplay.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** A horizontal row of vertical equalizer bands; drag inside a column to set its level in dB. */
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
    private fun dp(v: Float) = v * density

    private val topPad get() = dp(22f)
    private val bottomPad get() = dp(24f)

    override fun onDraw(canvas: Canvas) {
        val n = centersHz.size
        if (n == 0) return
        val colW = width.toFloat() / n
        val top = topPad
        val bottom = height - bottomPad
        val zeroY = top + (bottom - top) * (maxDb.toFloat() / (maxDb - minDb))
        text.textSize = dp(11f)
        for (band in 0 until n) {
            val cx = colW * band + colW / 2
            val level = levelsDb.getOrElse(band) { 0 }.coerceIn(minDb, maxDb)
            val y = top + (bottom - top) * ((maxDb - level).toFloat() / (maxDb - minDb))
            paint.color = trackColor; paint.strokeWidth = dp(4f); paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(cx, top, cx, bottom, paint)
            paint.color = accent; paint.strokeWidth = dp(6f)
            canvas.drawLine(cx, zeroY, cx, y, paint)
            paint.color = mutedColor; paint.strokeWidth = dp(1f)
            canvas.drawLine(cx - colW * 0.3f, zeroY, cx + colW * 0.3f, zeroY, paint)
            paint.color = accent
            canvas.drawCircle(cx, y, dp(9f), paint)
            text.color = textColor
            canvas.drawText(if (level > 0) "+$level" else "$level", cx, top - dp(8f), text)
            text.color = mutedColor
            val hz = centersHz[band]
            canvas.drawText(if (hz >= 1000) "${if (hz % 1000 == 0) hz / 1000 else hz / 1000f}k" else "$hz", cx, height - dp(6f), text)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val n = centersHz.size
        if (n == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val band = (event.x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                val top = topPad
                val bottom = height - bottomPad
                val fraction = ((event.y - top) / (bottom - top)).coerceIn(0f, 1f)
                val db = (maxDb - fraction * (maxDb - minDb)).roundToInt().coerceIn(minDb, maxDb)
                if (levelsDb.getOrElse(band) { 0 } != db) {
                    val next = levelsDb.copyOf(n); next[band] = db; levelsDb = next
                    onLevelChanged(band, db)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }
}
