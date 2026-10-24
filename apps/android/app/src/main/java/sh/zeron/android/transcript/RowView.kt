package sh.zeron.android.transcript

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import sh.zeron.android.core.StyleFonts
import sh.zeron.android.design.Glyphs
import sh.zeron.android.design.Icons
import sh.zeron.android.design.Palette
import uniffi.zeron_core.BoxStyle
import uniffi.zeron_core.ColorRole
import uniffi.zeron_core.Decoration
import uniffi.zeron_core.FadeEdge
import uniffi.zeron_core.RowDisplay
import uniffi.zeron_core.RowKind
import uniffi.zeron_core.Widget
import uniffi.zeron_core.WidgetKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a row needs from the transcript that hosts it. */
interface RowHost {
    val fonts: StyleFonts
    val isDark: Boolean
    fun toggle(key: ULong)
    fun toggleDetail(row: ULong, detail: ULong, open: Boolean)
    fun openUrl(url: String)
    fun showDetail(title: String, text: String)
    fun copy(text: String)
    /** Agent visual view for `id` (one live view each, moved between rows). */
    fun visualView(id: String): View?
    /** A picture for an image widget; null while it loads (`onReady` fires then). */
    fun image(reference: String, onReady: () -> Unit): Bitmap?
    fun openImage(reference: String, bitmap: Bitmap)
    /** A transcript button (an error card's "Log in") was tapped. */
    fun perform(action: String)
}

/**
 * A display list plus everything derived from it once per (row, version,
 * width): runs/boxes/fades grouped by scroller layer (0 = the row canvas).
 */
class RowModel(val display: RowDisplay) {
    private val layers = display.scrollers.size + 1
    val runsByLayer = Array(layers) { ArrayList<Int>() }
    val boxesByLayer = Array(layers) { ArrayList<Int>() }
    val fadesByLayer = Array(layers) { ArrayList<Pair<Int, List<Int>>>() }
    val fadedRuns = HashSet<Int>()
    val animated: Boolean = display.widgets.any {
        when (val k = it.kind) {
            is WidgetKind.Spinner, is WidgetKind.Working -> true
            is WidgetKind.ToolStatus -> k.running
            else -> false
        }
    }

    init {
        display.runs.forEachIndexed { i, r -> runsByLayer[r.scroller?.let { it.toInt() + 1 } ?: 0] += i }
        display.boxes.forEachIndexed { i, b -> boxesByLayer[b.scroller?.let { it.toInt() + 1 } ?: 0] += i }
        display.fades.forEachIndexed { fi, f ->
            val layer = f.scroller?.let { it.toInt() + 1 } ?: 0
            val masked = runsByLayer[layer].filter { i ->
                val r = display.runs[i]
                i !in fadedRuns && r.baseline > f.y && r.baseline <= f.y + f.h + 0.5f &&
                    (f.edge == FadeEdge.BOTTOM || r.x < f.x + f.w)
            }
            fadedRuns += masked
            fadesByLayer[layer] += fi to masked
        }
    }
}

/**
 * A reusable transcript row. Everything is painted straight from the Rust
 * display list at the Rust-computed positions (measurement and rendering can't
 * disagree); only agent visuals are child views.
 */
@SuppressLint("ViewConstructor")
class RowView(context: Context, private val host: RowHost) : ViewGroup(context) {
    var model: RowModel? = null
        private set
    var kind: RowKind = RowKind.MARKDOWN
        private set
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    /// Horizontal offsets of the row's scrollers (code blocks, tables).
    private var offsets = FloatArray(0)

    /// Streaming veil: text from `veilFrom` fades in over 220 ms.
    private var veilFrom = 0
    private var veilAlpha = 1f
    private var veil: ValueAnimator? = null

    private var visualWidgets = ArrayList<Pair<Widget, View>>()
    private var pending: Widget? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    val key: ULong get() = model?.display?.key ?: 0uL
    val version: ULong get() = model?.display?.version ?: 0uL

    fun configure(next: RowModel, kind: RowKind) {
        val previous = model?.display
        val d = next.display
        val grew = previous != null && previous.key == d.key && d.text.startsWith(previous.text) && d.text.length > previous.text.length
        model = next
        this.kind = kind
        if (offsets.size != d.scrollers.size) offsets = FloatArray(d.scrollers.size)
        else if (previous == null || previous.key != d.key) offsets.fill(0f)
        veil?.cancel()
        if (grew) {
            veilFrom = previous!!.text.length
            veilAlpha = 0f
            veil = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { veilAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            veilFrom = -1
            veilAlpha = 1f
        }
        contentDescription = (if (kind == RowKind.USER) "You: " else "") + d.copyText.ifEmpty { d.text }
        layoutVisuals(d)
        requestLayout()
        invalidate()
    }

    private fun layoutVisuals(d: RowDisplay) {
        for ((_, v) in visualWidgets) if (v.parent === this) removeView(v)
        visualWidgets.clear()
        for (w in d.widgets) {
            val k = w.kind
            if (k is WidgetKind.Visual) {
                val v = host.visualView(k.id) ?: continue
                (v.parent as? ViewGroup)?.removeView(v)
                addView(v)
                visualWidgets += w to v
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = model?.display
        val w = ((d?.width ?: 0f) * density).toInt()
        val h = ((d?.height ?: 0f) * density).toInt()
        for ((widget, v) in visualWidgets) {
            v.measure(
                MeasureSpec.makeMeasureSpec((widget.w * density).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((widget.h * density).toInt(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((w, v) in visualWidgets) {
            val x = (w.x * density).toInt()
            val y = (w.y * density).toInt()
            v.layout(x, y, x + (w.w * density).toInt(), y + (w.h * density).toInt())
        }
    }

    private fun color(role: ColorRole) = Palette.color(role, host.isDark)

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val d = m.display
        val now = SystemClock.uptimeMillis()
        canvas.save()
        canvas.scale(density, density)
        paintLayer(canvas, m, 0, now)
        d.scrollers.forEachIndexed { i, s ->
            canvas.save()
            canvas.clipRect(s.x, s.y, s.x + s.w, s.y + s.h)
            canvas.translate(s.x - offsets[i], s.y)
            paintLayer(canvas, m, i + 1, now)
            canvas.restore()
        }
        // Widgets outside scrollers, then inside (translated like their layer).
        for (w in d.widgets) {
            if (w.scroller == null) paintWidget(canvas, m, w, now)
        }
        d.scrollers.forEachIndexed { i, s ->
            val inside = d.widgets.filter { it.scroller?.toInt() == i }
            if (inside.isNotEmpty()) {
                canvas.save()
                canvas.clipRect(s.x, s.y, s.x + s.w, s.y + s.h)
                canvas.translate(s.x - offsets[i], s.y)
                for (w in inside) paintWidget(canvas, m, w, now)
                canvas.restore()
            }
        }
        canvas.restore()
        if (m.animated && isShown) postInvalidateOnAnimation()
    }

    private fun paintLayer(canvas: Canvas, m: RowModel, layer: Int, now: Long) {
        val d = m.display
        val hair = 1f / density
        for (i in m.boxesByLayer[layer]) {
            val b = d.boxes[i]
            rect.set(b.x, b.y, b.x + b.w, b.y + b.h)
            when (b.style) {
                BoxStyle.FILL -> {
                    fill.color = color(b.color)
                    canvas.drawRoundRect(rect, b.radius, b.radius, fill)
                }
                BoxStyle.HAIRLINE -> {
                    stroke.color = color(b.color)
                    stroke.strokeWidth = hair
                    rect.inset(hair / 2, hair / 2)
                    val r = max(0f, b.radius - hair / 2)
                    canvas.drawRoundRect(rect, r, r, stroke)
                }
            }
        }
        for (i in m.runsByLayer[layer]) {
            if (i in m.fadedRuns) continue
            drawRun(canvas, m, i)
        }
        for ((fi, runs) in m.fadesByLayer[layer]) {
            if (runs.isEmpty()) continue
            val f = d.fades[fi]
            canvas.save()
            if (f.edge == FadeEdge.TRAILING) canvas.clipRect(-100_000f, f.y - 40, f.x + f.w, f.y + f.h + 40)
            val saved = canvas.saveLayer(null, null)
            for (i in runs) drawRun(canvas, m, i)
            val shader = if (f.edge == FadeEdge.TRAILING)
                LinearGradient(f.x, 0f, f.x + f.w, 0f, 0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            else
                LinearGradient(0f, f.y, 0f, f.y + f.h, 0x00000000, 0xEB000000.toInt(), Shader.TileMode.CLAMP)
            clearPaint.shader = shader
            if (f.edge == FadeEdge.TRAILING) canvas.drawRect(f.x, f.y - 40, f.x + f.w, f.y + f.h + 40, clearPaint)
            else canvas.drawRect(f.x - 40, f.y, f.x + f.w + 40, f.y + f.h + 40, clearPaint)
            clearPaint.shader = null
            canvas.restoreToCount(saved)
            canvas.restore()
        }
    }

    private fun drawRun(canvas: Canvas, m: RowModel, i: Int) {
        val d = m.display
        val run = d.runs[i]
        val paint = host.fonts.paint(run.style) ?: return
        val start = run.start.toInt()
        val end = start + run.len.toInt()
        if (end > d.text.length) return
        paint.color = color(run.color)
        val color = paint.color
        // The veil: newly streamed text fades in; a straddling run splits at the glyph edge.
        if (veilFrom >= 0 && veilAlpha < 1f && end > veilFrom) {
            if (start < veilFrom) {
                val dx = paint.getRunAdvance(d.text, start, end, start, end, false, veilFrom)
                canvas.save()
                canvas.clipRect(run.x - 2, run.baseline - 200, run.x + dx, run.baseline + 200)
                canvas.drawText(d.text, start, end, run.x, run.baseline, paint)
                canvas.restore()
                paint.alpha = (Color_alpha(color) * veilAlpha).toInt()
                canvas.save()
                canvas.clipRect(run.x + dx, run.baseline - 200, run.x + run.width + 4, run.baseline + 200)
                canvas.drawText(d.text, start, end, run.x, run.baseline, paint)
                canvas.restore()
            } else {
                paint.alpha = (Color_alpha(color) * veilAlpha).toInt()
                canvas.drawText(d.text, start, end, run.x, run.baseline, paint)
            }
            paint.color = color
            return
        }
        canvas.drawText(d.text, start, end, run.x, run.baseline, paint)
        when (run.decoration) {
            Decoration.NONE -> {}
            Decoration.UNDERLINE, Decoration.STRIKETHROUGH -> {
                val y = if (run.decoration == Decoration.UNDERLINE) run.baseline + 2 else run.baseline - 5
                fill.color = color
                canvas.drawRect(run.x, y, run.x + run.width, y + 1f, fill)
            }
        }
    }

    @Suppress("FunctionName")
    private fun Color_alpha(c: Int) = (c ushr 24)

    private fun paintWidget(canvas: Canvas, m: RowModel, w: Widget, now: Long) {
        rect.set(w.x, w.y, w.x + w.w, w.y + w.h)
        val dark = host.isDark
        when (val k = w.kind) {
            is WidgetKind.CopyCode -> Glyphs.copy(canvas, rect, if (copied == w.id) color(ColorRole.ACCENT) else color(ColorRole.TEXT_TERTIARY))
            is WidgetKind.Disclosure -> {}
            is WidgetKind.ToolStatus -> {
                if (k.running) Glyphs.spinner(canvas, rect, dark, now)
                else if (k.failed) Glyphs.cross(canvas, rect, color(ColorRole.DANGER))
                else Glyphs.check(canvas, rect, color(ColorRole.TEXT_TERTIARY), 1.4f)
            }
            is WidgetKind.Image -> paintImage(canvas, w, k.reference)
            is WidgetKind.Working -> paintWorking(canvas, w, k, now)
            is WidgetKind.Spinner -> Glyphs.spinner(canvas, rect, dark, now)
            is WidgetKind.Detail -> {}
            is WidgetKind.Icon -> {
                val tint = color(k.color)
                if (Icons.has(k.name)) Icons.draw(canvas, k.name, rect, density, tint, dark)
                else Glyphs.named(canvas, k.name, rect, tint)
            }
            is WidgetKind.Chevron -> Glyphs.chevron(canvas, rect, color(ColorRole.TEXT_SECONDARY), if (k.expanded) 0f else -90f)
            is WidgetKind.ToolRail -> paintRail(canvas, w, k)
            is WidgetKind.ToolToggle -> {}
            is WidgetKind.Shimmer -> {}
            is WidgetKind.Visual -> {}
            is WidgetKind.Action -> paintAction(canvas, k)
        }
    }

    private val actionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textSize = 14f
        textAlign = Paint.Align.CENTER
    }

    /** A capsule in the card's soft red with the label knocked out in the page colour. */
    private fun paintAction(canvas: Canvas, k: WidgetKind.Action) {
        fill.color = color(ColorRole.DANGER_SOFT)
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        actionText.color = if (host.isDark) 0xFF060606.toInt() else 0xFFF3F3F5.toInt()
        canvas.drawText(k.label, rect.centerX(), rect.centerY() - (actionText.ascent() + actionText.descent()) / 2, actionText)
    }

    private var copied: UInt = UInt.MAX_VALUE
    private val rail = Path()

    private fun paintRail(canvas: Canvas, w: Widget, k: WidgetKind.ToolRail) {
        stroke.color = color(ColorRole.TOOL_RAIL)
        stroke.strokeWidth = 1f
        rail.reset()
        for (i in k.tops.indices) {
            val top = k.tops[i] + w.y
            val mid = top + k.rowMid
            rail.moveTo(w.x + k.trunkX, top)
            rail.lineTo(w.x + k.trunkX, mid - k.bend)
            rail.quadTo(w.x + k.trunkX, mid, w.x + k.trunkX + k.bend, mid)
            rail.lineTo(w.x + k.branchEnd, mid)
            if (i + 1 < k.tops.size) {
                rail.moveTo(w.x + k.trunkX, mid - k.bend)
                rail.lineTo(w.x + k.trunkX, top + k.heights[i])
            }
        }
        canvas.drawPath(rail, stroke)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun paintWorking(canvas: Canvas, w: Widget, k: WidgetKind.Working, now: Long) {
        rect.set(w.x, w.y + (w.h - 14) / 2, w.x + 14, w.y + (w.h - 14) / 2 + 14)
        Glyphs.trailer(canvas, rect, now)
        labelPaint.typeface = sh.zeron.android.core.Fonts.typeface(uniffi.zeron_core.FaceRole.SANS_MEDIUM)
        labelPaint.textSize = 13.5f
        val word = if (k.streaming) "Writing" else "Working"
        val base = w.y + w.h / 2 + 5f
        labelPaint.color = color(ColorRole.TEXT_SECONDARY)
        val label = "$word…"
        canvas.drawText(label, w.x + 22, base, labelPaint)
        val since = k.sinceMs
        if (since != null) {
            val secs = max(0L, (System.currentTimeMillis() - since) / 1000)
            if (secs > 0) {
                labelPaint.color = color(ColorRole.TEXT_TERTIARY)
                canvas.drawText(elapsed(secs), w.x + 22 + labelPaint.measureText(label) + 8, base, labelPaint)
            }
        }
    }

    private fun paintImage(canvas: Canvas, w: Widget, reference: String) {
        val bmp = host.image(reference) { postInvalidateOnAnimation() }
        val r = min(w.w, w.h).let { if (it > 120) 14f else 12f }
        if (bmp == null) {
            fill.color = color(ColorRole.CHIP_BACKGROUND)
            canvas.drawRoundRect(rect, r, r, fill)
            return
        }
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)
        // Aspect-fill.
        val scale = max(rect.width() / bmp.width, rect.height() / bmp.height)
        val sw = rect.width() / scale
        val sh = rect.height() / scale
        val sx = (bmp.width - sw) / 2f
        val sy = (bmp.height - sh) / 2f
        canvas.drawBitmap(bmp, android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), rect, null)
        canvas.restore()
    }

    // ---- touch ----------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var dragScroller = -1
    private var dragStartOffset = 0f
    private var dragging = false
    private var downTime = 0L

    private fun scrollerAt(x: Float, y: Float): Int {
        val d = model?.display ?: return -1
        d.scrollers.forEachIndexed { i, s ->
            if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h && s.contentWidth > s.w + 0.5f) return i
        }
        return -1
    }

    private fun canvasScrollerAt(x: Float, y: Float): Int {
        val d = model?.display ?: return -1
        d.scrollers.forEachIndexed { i, s -> if (x >= s.x && x <= s.x + s.w && y >= s.y && y <= s.y + s.h) return i }
        return -1
    }

    /** Is this row interactive at (x, y), in dp row coordinates? */
    private fun hit(x: Float, y: Float): Boolean = target(x, y) != null

    private sealed class Target {
        data class Link(val url: String) : Target()
        data class Widget(val w: uniffi.zeron_core.Widget) : Target()
    }

    private fun toRow(x: Float, y: Float, scroller: UInt?): Pair<Float, Float>? {
        val d = model?.display ?: return null
        if (scroller == null) return x to y
        val i = scroller.toInt()
        if (i >= d.scrollers.size) return null
        val s = d.scrollers[i]
        // Point in the scroller's content space; also must be within its viewport.
        if (x < s.x || x > s.x + s.w || y < s.y || y > s.y + s.h) return null
        return (x - s.x + offsets[i]) to (y - s.y)
    }

    private fun target(x: Float, y: Float): Target? {
        val d = model?.display ?: return null
        for (l in d.links) {
            val p = toRow(x, y, l.scroller) ?: continue
            if (p.first >= l.x - 4 && p.first <= l.x + l.w + 4 && p.second >= l.y - 2 && p.second <= l.y + l.h + 2) return Target.Link(l.url)
        }
        for (w in d.widgets.asReversed()) {
            val interactive = when (w.kind) {
                is WidgetKind.CopyCode, is WidgetKind.Disclosure, is WidgetKind.ToolToggle,
                is WidgetKind.Detail, is WidgetKind.Image, is WidgetKind.Action -> true
                else -> false
            }
            if (!interactive) continue
            val p = toRow(x, y, w.scroller) ?: continue
            val pad = 8f
            if (p.first >= w.x - pad && p.first <= w.x + w.w + pad && p.second >= w.y - pad && p.second <= w.y + w.h + pad) return Target.Widget(w)
        }
        return null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x / density
        val y = ev.y / density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                downTime = ev.eventTime
                dragging = false
                dragScroller = scrollerAt(x, y)
                dragStartOffset = if (dragScroller >= 0) offsets[dragScroller] else 0f
                return dragScroller >= 0 || hit(x, y) || model?.display?.links?.isNotEmpty() == true || true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragScroller >= 0) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val s = model!!.display.scrollers[dragScroller]
                        offsets[dragScroller] = (dragStartOffset - dx / density).coerceIn(0f, max(0f, s.contentWidth - s.w))
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && abs(ev.x - downX) < slop && abs(ev.y - downY) < slop) tap(x, y)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        val d = model?.display ?: return
        when (val t = target(x, y)) {
            null -> {}
            is Target.Link -> host.openUrl(t.url)
            is Target.Widget -> {
                val w = t.w
                when (val k = w.kind) {
                    is WidgetKind.CopyCode -> {
                        host.copy(w.payload.orEmpty())
                        copied = w.id
                        invalidate()
                        postDelayed({ copied = UInt.MAX_VALUE; invalidate() }, 1400)
                    }
                    is WidgetKind.Disclosure -> host.toggle(d.key)
                    is WidgetKind.ToolToggle -> host.toggleDetail(d.key, k.detail, k.open)
                    is WidgetKind.Detail -> host.showDetail(k.title, w.payload.orEmpty())
                    is WidgetKind.Action -> host.perform(k.action)
                    is WidgetKind.Image -> host.image(k.reference) {}?.let { host.openImage(k.reference, it) }
                    else -> {}
                }
            }
        }
    }

    private fun elapsed(secs: Long): String = when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m ${secs % 60}s"
        else -> "${secs / 3600}h ${(secs % 3600) / 60}m"
    }
}
