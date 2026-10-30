package sh.zeron.android.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.LruCache
import com.caverock.androidsvg.SVG

/**
 * Tool (`tool-*`) and file (`fileicon-*`) icons: the SVGs the iOS asset
 * catalog ships, rasterized once per size. Tool icons are templates tinted at
 * draw time; file icons keep their own colors (with a `-dark` variant).
 */
object Icons {
    private lateinit var context: Context
    private val svgs = HashMap<String, SVG?>()
    private val bitmaps = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun has(name: String) = name.startsWith("tool-") || name.startsWith("fileicon-") || name.startsWith("brand-")

    private fun svg(name: String): SVG? = synchronized(svgs) {
        svgs.getOrPut(name) {
            try {
                context.assets.open("icons/$name.svg").use { SVG.getFromInputStream(it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun bitmap(name: String, px: Int): Bitmap? {
        if (px <= 0) return null
        val key = "$name@$px"
        bitmaps.get(key)?.let { return it }
        val svg = svg(name) ?: return null
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Fit the artwork's own aspect inside the square.
        val w = svg.documentWidth.takeIf { it > 0 } ?: svg.documentViewBox?.width() ?: px.toFloat()
        val h = svg.documentHeight.takeIf { it > 0 } ?: svg.documentViewBox?.height() ?: px.toFloat()
        val scale = minOf(px / w, px / h)
        svg.documentWidth = w * scale
        svg.documentHeight = h * scale
        c.translate((px - w * scale) / 2f, (px - h * scale) / 2f)
        svg.renderToCanvas(c)
        bitmaps.put(key, bmp)
        return bmp
    }

    /**
     * A square raster of `name`, inset and tinted. Used where Compose isn't
     * available (the car conversation avatars).
     */
    fun tintedBitmap(name: String, px: Int, tint: Int): Bitmap? {
        if (px <= 0) return null
        val key = "$name@$px#$tint"
        bitmaps.get(key)?.let { return it }
        val art = (px * 0.72f).toInt().coerceAtLeast(1)
        val src = bitmap(name, art) ?: return null
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        val pad = (px - src.width) / 2f
        c.drawBitmap(src, pad, pad, paint)
        paint.colorFilter = null
        bitmaps.put(key, out)
        return out
    }

    /**
     * Draw `name` in `rect` (canvas units; `density` converts to pixels for
     * a crisp raster). `tint` applies to template icons only.
     */
    fun draw(canvas: Canvas, name: String, rect: RectF, density: Float, tint: Int, dark: Boolean) {
        val resolved = if (name.startsWith("fileicon-") && dark && svg("$name-dark") != null) "$name-dark" else name
        val bmp = bitmap(resolved, (minOf(rect.width(), rect.height()) * density).toInt()) ?: return
        paint.colorFilter = if (name.startsWith("tool-") || name.startsWith("brand-")) PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) else null
        val side = minOf(rect.width(), rect.height())
        val left = rect.left + (rect.width() - side) / 2f
        val top = rect.top + (rect.height() - side) / 2f
        canvas.drawBitmap(bmp, null, RectF(left, top, left + side, top + side), paint)
    }
}
