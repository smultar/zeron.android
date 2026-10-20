package sh.zeron.android.transcript

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import sh.zeron.android.core.StyleFonts
import sh.zeron.android.design.Palette
import uniffi.zeron_core.ControlKind
import uniffi.zeron_core.FrameImage
import uniffi.zeron_core.PointerPhase
import uniffi.zeron_core.Rgba
import uniffi.zeron_core.SurfaceFrame
import uniffi.zeron_core.SurfaceGesture
import uniffi.zeron_core.VisualControl
import uniffi.zeron_core.VisualDisplay
import uniffi.zeron_core.VisualListener
import uniffi.zeron_core.VisualPaint
import uniffi.zeron_core.VisualSession
import uniffi.zeron_core.VisualShape
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An agent visual in the transcript. Its Rust worker ([VisualSession]) runs the
 * script, lays it out, draws canvases and charts, and renders scenes and
 * shaders with wgpu; this view shows what arrives and forwards touches.
 */
@SuppressLint("ViewConstructor")
class VisualView(
    context: Context,
    val session: VisualSession,
    private val openUrl: (String) -> Unit,
    private val isDark: () -> Boolean,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())
    private val fonts = StyleFonts()

    private var display: VisualDisplay? = null
    private class SurfaceState(var image: Bitmap? = null, var paint: VisualPaint? = null)
    private val surfaces = HashMap<ULong, SurfaceState>()

    private val sink = Sink()
    private var attached = false
    private var active = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        contentDescription = session.title()
    }

    /** Whether the row showing this view is on screen. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        updateAttachment()
    }

    private fun updateAttachment() {
        val wanted = isAttachedToWindow && active && windowVisibility == VISIBLE
        if (wanted == attached) return
        attached = wanted
        if (wanted) {
            val reduce = android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            session.attach(sink, isDark(), reduce)
        } else {
            session.detach()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Rows re-add their views on every configure: settle on the next turn
        // of the loop so a move between rows doesn't detach.
        main.post { updateAttachment() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.post { updateAttachment() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAttachment()
    }

    fun setDark(dark: Boolean) = session.setAppearance(dark)

    // ---- worker output --------------------------------------------------------

    private inner class Sink : VisualListener {
        private val lock = Any()
        private var pendingDisplay: VisualDisplay? = null
        private val pendingFrames = HashMap<ULong, Pair<Bitmap?, VisualPaint?>>()
        private var scheduled = false

        override fun display(display: VisualDisplay) {
            fonts.update(display.styles)
            synchronized(lock) {
                pendingDisplay = display
                schedule()
            }
        }

        override fun frame(frame: SurfaceFrame) {
            fonts.update(frame.styles)
            val bmp = frame.image?.let { toBitmap(it) }
            val paint = frame.paint.takeIf { it.shapes.isNotEmpty() || it.runs.isNotEmpty() }
            synchronized(lock) {
                pendingFrames[frame.surface] = bmp to paint
                schedule()
            }
        }

        private fun schedule() {
            if (scheduled) return
            scheduled = true
            main.post {
                val d: VisualDisplay?
                val frames: Map<ULong, Pair<Bitmap?, VisualPaint?>>
                synchronized(lock) {
                    d = pendingDisplay
                    frames = HashMap(pendingFrames)
                    pendingDisplay = null
                    pendingFrames.clear()
                    scheduled = false
                }
                if (d != null) applyDisplay(d)
                for ((id, f) in frames) {
                    val s = surfaces.getOrPut(id) { SurfaceState() }
                    s.image = f.first
                    s.paint = f.second
                }
                invalidate()
            }
        }
    }

    /** Premultiplied BGRA from the worker → an ARGB_8888 bitmap (swap R/B). */
    private fun toBitmap(img: FrameImage): Bitmap? {
        val w = img.width.toInt()
        val h = img.height.toInt()
        if (w <= 0 || h <= 0 || img.bgra.size < w * h * 4) return null
        val px = ByteArray(w * h * 4)
        val src = img.bgra
        var i = 0
        val n = w * h * 4
        while (i < n) {
            px[i] = src[i + 2]
            px[i + 1] = src[i + 1]
            px[i + 2] = src[i]
            px[i + 3] = src[i + 3]
            i += 4
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(px))
        return bmp
    }

    private fun applyDisplay(d: VisualDisplay) {
        val old = display
        display = d
        val live = d.surfaces.map { it.id }.toSet()
        surfaces.keys.retainAll(live)
        for (s in d.surfaces) surfaces.getOrPut(s.id) { SurfaceState() }
        if (old == null || old.width != d.width || old.height != d.height) requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    // ---- painting ---------------------------------------------------------------

    private fun argb(c: Rgba): Int =
        ((c.a * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24) or
            ((c.r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((c.g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (c.b * 255f + 0.5f).toInt().coerceIn(0, 255)

    override fun onDraw(canvas: Canvas) {
        val d = display ?: return
        canvas.save()
        canvas.scale(density, density)
        drawPaint(canvas, d.paint)
        for (s in d.surfaces) {
            val state = surfaces[s.id] ?: continue
            canvas.save()
            canvas.translate(s.x, s.y)
            state.image?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, s.w, s.h), bitmapPaint) }
            state.paint?.let { drawPaint(canvas, it) }
            canvas.restore()
        }
        // Pressed control wash.
        pressed?.let { c ->
            val r = when (c.kind) {
                is ControlKind.Tap -> 6f
                is ControlKind.Button, is ControlKind.Tab -> 8f
                else -> 0f
            }
            if (c.kind !is ControlKind.Slider && c.kind !is ControlKind.Toggle) {
                fill.color = Palette.controlFill.get(isDark())
                rect.set(c.x, c.y, c.x + c.w, c.y + c.h)
                canvas.drawRoundRect(rect, r, r, fill)
            }
        }
        canvas.restore()
    }

    private fun drawPaint(canvas: Canvas, p: VisualPaint) {
        for (shape in p.shapes) {
            when (shape) {
                is VisualShape.Rect -> {
                    rect.set(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)
                    fill.color = argb(shape.color)
                    val r = min(shape.radius, min(rect.width(), rect.height()) / 2)
                    if (r > 0) canvas.drawRoundRect(rect, r, r, fill) else canvas.drawRect(rect, fill)
                }
                is VisualShape.Path -> {
                    val pts = shape.points
                    if (pts.size < 4) continue
                    path.reset()
                    path.moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 1 < pts.size) {
                        path.lineTo(pts[i], pts[i + 1])
                        i += 2
                    }
                    if (shape.closed) path.close()
                    shape.fill?.let {
                        fill.color = argb(it)
                        canvas.drawPath(path, fill)
                    }
                    shape.stroke?.let {
                        stroke.color = argb(it)
                        stroke.strokeWidth = shape.lineWidth
                        stroke.pathEffect = shape.dash?.let { d -> DashPathEffect(floatArrayOf(d, d), 0f) }
                        canvas.drawPath(path, stroke)
                        stroke.pathEffect = null
                    }
                }
            }
        }
        for (run in p.runs) {
            val paint = fonts.paint(run.style) ?: continue
            val start = run.start.toInt()
            val end = start + run.len.toInt()
            if (end > p.text.length) continue
            paint.color = argb(run.color)
            canvas.drawText(p.text, start, end, run.x, run.baseline, paint)
        }
    }

    // ---- touch --------------------------------------------------------------------

    private var pressed: VisualControl? = null
    private var tracking: Tracking? = null
    private var readout: Runnable? = null

    private sealed class Tracking {
        class Control(val c: VisualControl) : Tracking()
        class Surface(val id: ULong, val x: Float, val y: Float, val gesture: SurfaceGesture, var started: Boolean) : Tracking()
    }

    private fun controlAt(x: Float, y: Float): VisualControl? {
        val d = display ?: return null
        for (c in d.controls.asReversed()) {
            val dy = max(0f, (44f - c.h) / 2f)
            if (x >= c.x - 4 && x <= c.x + c.w + 4 && y >= c.y - dy && y <= c.y + c.h + dy) return c
        }
        return null
    }

    private fun surfaceAt(x: Float, y: Float) =
        display?.surfaces?.firstOrNull { it.gesture != SurfaceGesture.NONE && x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }

    private fun slide(c: VisualControl, x: Float) {
        val k = c.kind as? ControlKind.Slider ?: return
        if (k.trackW <= 0) return
        session.slide(c.id, ((x - k.trackX) / k.trackW).coerceIn(0f, 1f))
    }

    private fun send(phase: PointerPhase, surface: ULong, sx: Float, sy: Float, x: Float, y: Float) =
        session.pointer(surface, phase, x - sx, y - sy)

    private var downRawX = 0f
    private var downRawY = 0f

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x / density
        val y = ev.y / density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.x; downRawY = ev.y
                val c = controlAt(x, y)
                if (c != null) {
                    tracking = Tracking.Control(c)
                    pressed = c
                    if (c.kind is ControlKind.Slider) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        slide(c, x)
                    }
                    invalidate()
                    return true
                }
                val s = surfaceAt(x, y) ?: return false
                val t = Tracking.Surface(s.id, s.x, s.y, s.gesture, false)
                tracking = t
                when (s.gesture) {
                    SurfaceGesture.DRAG -> {
                        // Tracks from touch-down: drags here never scroll the transcript.
                        parent?.requestDisallowInterceptTouchEvent(true)
                        t.started = true
                        send(PointerPhase.DOWN, s.id, s.x, s.y, x, y)
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (val t = tracking) {
                    is Tracking.Control -> if (t.c.kind is ControlKind.Slider) slide(t.c, x)
                    is Tracking.Surface -> {
                        if (t.gesture == SurfaceGesture.HORIZONTAL_DRAG && !t.started) {
                            val dx = abs(ev.x - downRawX)
                            val dy = abs(ev.y - downRawY)
                            if (dx > slop && dx > dy) {
                                t.started = true
                                parent?.requestDisallowInterceptTouchEvent(true)
                                readout?.let { main.removeCallbacks(it) }
                                send(PointerPhase.DOWN, t.id, t.x, t.y, x, y)
                            }
                        } else if (t.started) {
                            send(PointerPhase.MOVE, t.id, t.x, t.y, x, y)
                        }
                    }
                    null -> {}
                }
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(ev.x - downRawX) > slop || abs(ev.y - downRawY) > slop
                when (val t = tracking) {
                    is Tracking.Control -> {
                        val inside = controlAt(x, y)?.id == t.c.id
                        if (inside || t.c.kind is ControlKind.Slider) activate(t.c, inside)
                    }
                    is Tracking.Surface -> {
                        if (t.started) {
                            send(PointerPhase.UP, t.id, t.x, t.y, x, y)
                        } else if (!moved) tapSurface(t, x, y)
                    }
                    null -> {}
                }
                finish()
            }
            MotionEvent.ACTION_CANCEL -> {
                (tracking as? Tracking.Surface)?.let { if (it.started) send(PointerPhase.CANCEL, it.id, it.x, it.y, x, y) }
                finish()
            }
        }
        return true
    }

    private fun tapSurface(t: Tracking.Surface, x: Float, y: Float) {
        send(PointerPhase.DOWN, t.id, t.x, t.y, x, y)
        if (t.gesture != SurfaceGesture.HORIZONTAL_DRAG) {
            send(PointerPhase.UP, t.id, t.x, t.y, x, y)
            return
        }
        // A tap on a chart shows its readout for a moment.
        readout?.let { main.removeCallbacks(it) }
        val r = Runnable { send(PointerPhase.UP, t.id, t.x, t.y, x, y) }
        readout = r
        main.postDelayed(r, 2500)
    }

    private fun activate(c: VisualControl, inside: Boolean) {
        when (val k = c.kind) {
            is ControlKind.Button, is ControlKind.Tap -> if (inside) session.activate(c.id)
            is ControlKind.Tab, is ControlKind.Toggle -> if (inside) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                session.activate(c.id)
            }
            is ControlKind.Link -> if (inside) openUrl(k.url)
            is ControlKind.Slider -> {}
        }
    }

    private fun finish() {
        tracking = null
        pressed = null
        invalidate()
    }
}
