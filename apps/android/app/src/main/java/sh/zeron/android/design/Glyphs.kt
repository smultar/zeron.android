package sh.zeron.android.design

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor

/**
 * The desktop's status glyphs, drawn on a canvas in dp units:
 * - spinner: a 2×3 grid of violet circles whose brightness chases the ring (750 ms)
 * - trailer: the transcript's 3×3 gradient spinner (pastel rows)
 * - dot: a static circle
 * - check: completed-and-unseen
 */
object Glyphs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val ring = arrayOf(intArrayOf(0, 1), intArrayOf(5, 2), intArrayOf(4, 3))
    private val glyphTints = listOf(
        Palette.Pair(Color.rgb(0x79, 0x65, 0xEC), Color.rgb(0xAB, 0xA1, 0xF9)),
        Palette.Pair(Color.rgb(0x5B, 0x43, 0xE8), Color.rgb(0x8B, 0x7C, 0xF6)),
        Palette.Pair(Color.rgb(0x43, 0x32, 0xAC), Color.rgb(0x72, 0x66, 0xCA)),
    )
    private val trailerTints = intArrayOf(Color.rgb(0xB6, 0xD3, 0xEF), Color.rgb(0xED, 0xB1, 0x85), Color.rgb(0xF8, 0x88, 0xA0))

    const val PERIOD_MS = 750L

    /** `gspin_opacity`: hold bright, fall over 45 %, rest dim, snap back over the last 8 %. */
    fun opacity(t0: Double, dim: Double = 0.1): Double {
        val t = t0 - floor(t0)
        return when {
            t < 0.45 -> 1 + (dim - 1) * t / 0.45
            t < 0.92 -> dim
            else -> dim + (1 - dim) * (t - 0.92) / 0.08
        }
    }

    private fun cell(c: Canvas, x: Float, y: Float, d: Float, color: Int, alpha: Double) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        c.drawCircle(x + d / 2, y + d / 2, d / 2, paint)
    }

    /** Mini 2×3 spinner centered in `r`. */
    fun spinner(c: Canvas, r: RectF, dark: Boolean, nowMs: Long) {
        val d = 3.5f
        val gap = 1.5f
        val ox = r.centerX() - (d * 2 + gap) / 2
        val oy = r.centerY() - (d * 3 + gap * 2) / 2
        for (i in 0 until 6) {
            val row = i / 2
            val col = i % 2
            val phase = ring[row][col] / 6.0
            val t = (nowMs % PERIOD_MS) / PERIOD_MS.toDouble() + phase
            cell(c, ox + col * (d + gap), oy + row * (d + gap), d, glyphTints[row].get(dark), opacity(t))
        }
    }

    /** The transcript's 3×3 trailer spinner centered in `r`. */
    fun trailer(c: Canvas, r: RectF, nowMs: Long) {
        val d = 3.5f
        val gap = 1.75f
        val side = d * 3 + gap * 2
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            val phase = (2 - row + abs(col - 1)) / 4.0
            val t = (nowMs % PERIOD_MS) / PERIOD_MS.toDouble() + phase
            cell(c, r.centerX() - side / 2 + col * (d + gap), r.centerY() - side / 2 + row * (d + gap), d, trailerTints[row], opacity(t))
        }
    }

    fun dot(c: Canvas, r: RectF, color: Int, diameter: Float = 7f) {
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawCircle(r.centerX(), r.centerY(), diameter / 2, paint)
    }

    /** check.svg: M3.5 8.5 l3 3 6-7 on a 16 grid. */
    fun check(c: Canvas, r: RectF, color: Int, stroke: Float = 1.6f) {
        val s = minOf(r.width(), r.height()) / 16f * 1.05f
        val ox = r.centerX() - 8 * s
        val oy = r.centerY() - 8 * s
        path.reset()
        path.moveTo(ox + 3.5f * s, oy + 8.5f * s)
        path.lineTo(ox + 6.5f * s, oy + 11.5f * s)
        path.lineTo(ox + 12.5f * s, oy + 4.5f * s)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    fun cross(c: Canvas, r: RectF, color: Int, stroke: Float = 1.6f) {
        val s = minOf(r.width(), r.height()) * 0.28f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = color
        c.drawLine(r.centerX() - s, r.centerY() - s, r.centerX() + s, r.centerY() + s, paint)
        c.drawLine(r.centerX() + s, r.centerY() - s, r.centerX() - s, r.centerY() + s, paint)
        paint.style = Paint.Style.FILL
    }

    fun chevron(c: Canvas, r: RectF, color: Int, rotationDeg: Float, stroke: Float = 1.6f) {
        val s = minOf(r.width(), r.height()) * 0.22f
        c.save()
        c.rotate(rotationDeg, r.centerX(), r.centerY())
        path.reset()
        path.moveTo(r.centerX() - s * 1.3f, r.centerY() - s * 0.7f)
        path.lineTo(r.centerX(), r.centerY() + s * 0.7f)
        path.lineTo(r.centerX() + s * 1.3f, r.centerY() - s * 0.7f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        c.restore()
    }

    /** Two overlapping rounded squares (copy). */
    fun copy(c: Canvas, r: RectF, color: Int) {
        val s = minOf(r.width(), r.height()) * 0.5f
        val cx = r.centerX()
        val cy = r.centerY()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = color
        c.drawRoundRect(cx - s / 2 + 1.5f, cy - s / 2 + 1.5f, cx + s / 2 + 1.5f, cy + s / 2 + 1.5f, 2.5f, 2.5f, paint)
        c.drawRoundRect(cx - s / 2 - 1.5f, cy - s / 2 - 1.5f, cx + s / 2 - 1.5f, cy + s / 2 - 1.5f, 2.5f, 2.5f, paint)
        paint.style = Paint.Style.FILL
    }

    /** Named glyphs the display lists ask for by SF-symbol name. */
    fun named(c: Canvas, name: String, r: RectF, color: Int): Boolean {
        val s = minOf(r.width(), r.height())
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1.2f, s * 0.09f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        val cx = r.centerX()
        val cy = r.centerY()
        val u = s / 24f
        when (name) {
            "exclamationmark.triangle" -> {
                path.reset()
                path.moveTo(cx, cy - 9 * u); path.lineTo(cx + 9.5f * u, cy + 8 * u); path.lineTo(cx - 9.5f * u, cy + 8 * u); path.close()
                c.drawPath(path, paint)
                c.drawLine(cx, cy - 3 * u, cx, cy + 2.5f * u, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy + 5.2f * u, 1f * u + 0.3f, paint)
            }
            "questionmark.bubble" -> {
                c.drawRoundRect(cx - 9 * u, cy - 9 * u, cx + 9 * u, cy + 6 * u, 5 * u, 5 * u, paint)
                path.reset()
                path.moveTo(cx - 3 * u, cy + 6 * u); path.lineTo(cx - 5 * u, cy + 10 * u); path.lineTo(cx + 1 * u, cy + 6 * u)
                c.drawPath(path, paint)
                path.reset()
                path.moveTo(cx - 2.8f * u, cy - 4 * u)
                path.cubicTo(cx - 2.8f * u, cy - 7.5f * u, cx + 3 * u, cy - 7 * u, cx + 3 * u, cy - 3.5f * u)
                path.cubicTo(cx + 3 * u, cy - 1.5f * u, cx, cy - 1.5f * u, cx, cy + 0.5f * u)
                c.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy + 2.8f * u, 0.9f * u + 0.2f, paint)
            }
            "arrow.triangle.branch" -> {
                c.drawCircle(cx - 5 * u, cy - 6 * u, 2.3f * u, paint)
                c.drawCircle(cx - 5 * u, cy + 6 * u, 2.3f * u, paint)
                c.drawCircle(cx + 6 * u, cy - 3 * u, 2.3f * u, paint)
                c.drawLine(cx - 5 * u, cy - 3.7f * u, cx - 5 * u, cy + 3.7f * u, paint)
                path.reset()
                path.moveTo(cx + 6 * u, cy - 0.7f * u)
                path.cubicTo(cx + 6 * u, cy + 4 * u, cx - 5 * u, cy + 1 * u, cx - 5 * u, cy + 3.7f * u)
                c.drawPath(path, paint)
            }
            "wand.and.stars" -> {
                c.drawLine(cx - 8 * u, cy + 8 * u, cx + 3 * u, cy - 3 * u, paint)
                paint.style = Paint.Style.FILL
                star(c, cx + 6 * u, cy - 6 * u, 4 * u)
                star(c, cx - 4 * u, cy - 7 * u, 2.2f * u)
                star(c, cx + 7 * u, cy + 4 * u, 2.2f * u)
            }
            "checkmark" -> check(c, r, color)
            "xmark" -> cross(c, r, color)
            else -> {
                paint.style = Paint.Style.FILL
                return false
            }
        }
        paint.style = Paint.Style.FILL
        return true
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        path.moveTo(x, y - r); path.quadTo(x, y, x + r, y); path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y); path.quadTo(x, y, x, y - r)
        c.drawPath(path, paint)
    }
}
