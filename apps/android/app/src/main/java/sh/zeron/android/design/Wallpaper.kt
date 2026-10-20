package sh.zeron.android.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.zeron_core.WallpaperEffect
import uniffi.zeron_core.wallpaperRender
import uniffi.zeron_core.wallpaperSafeOpacity
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The chat wallpaper (the desktop's "new thread background"): one image plus an
 * effect, stored locally. Effects and the contrast guard run in the Rust core
 * (`wallpaper.rs`) off the main thread; renders are cached per appearance.
 */
object WallpaperStore {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("zeron", Context.MODE_PRIVATE)
    private val file get() = File(context.filesDir, "wallpaper/wallpaper.jpg")

    /** Bumps whenever the image or effect changes; composables key on it. */
    var version by mutableIntStateOf(0)
        private set

    private val cache = HashMap<String, Render>()

    class Render(val bitmap: Bitmap, val opacity: Float)

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    val isSet: Boolean get() = file.exists()
    val name: String? get() = prefs.getString("wallpaperName", null)

    var effect: WallpaperEffect
        get() = when (prefs.getString("wallpaperEffect", null)) {
            "dither" -> WallpaperEffect.DITHER
            "ascii" -> WallpaperEffect.ASCII
            "halftone" -> WallpaperEffect.HALFTONE
            "scanlines" -> WallpaperEffect.SCANLINES
            else -> WallpaperEffect.NONE
        }
        set(value) {
            prefs.edit().putString("wallpaperEffect", value.name.lowercase()).apply()
            changed()
        }

    val allEffects = listOf(WallpaperEffect.NONE, WallpaperEffect.DITHER, WallpaperEffect.ASCII, WallpaperEffect.HALFTONE, WallpaperEffect.SCANLINES)

    /** Desktop labels and descriptions. */
    fun label(e: WallpaperEffect) = when (e) {
        WallpaperEffect.NONE -> "None"
        WallpaperEffect.DITHER -> "Dither"
        WallpaperEffect.ASCII -> "ASCII"
        WallpaperEffect.HALFTONE -> "Halftone"
        WallpaperEffect.SCANLINES -> "Scanlines"
    }

    fun detail(e: WallpaperEffect) = when (e) {
        WallpaperEffect.NONE -> "Shows the original artwork."
        WallpaperEffect.DITHER -> "Rebuilds the artwork with a dithered color palette."
        WallpaperEffect.ASCII -> "Recreates the artwork with colored characters."
        WallpaperEffect.HALFTONE -> "Recreates the artwork with colored print dots."
        WallpaperEffect.SCANLINES -> "Adds a pronounced horizontal display-line texture."
    }

    /** Store a picked photo, downsampled as it is decoded (long side ≤ 1600 px). */
    suspend fun set(uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return@withContext
        val scale = min(1f, 1600f / max(decoded.width, decoded.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true) else decoded
        file.parentFile?.mkdirs()
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        prefs.edit().putString("wallpaperName", displayName).apply()
        withContext(Dispatchers.Main) { changed() }
    }

    fun remove() {
        file.delete()
        prefs.edit().remove("wallpaperName").apply()
        changed()
    }

    private fun changed() {
        synchronized(cache) { cache.clear() }
        version++
    }

    /** Render for an appearance (cached until the image or effect changes); null when none is set. */
    suspend fun render(dark: Boolean): Render? = withContext(Dispatchers.Default) {
        if (!isSet) return@withContext null
        val e = effect
        val key = "${e.name}-$dark"
        synchronized(cache) { cache[key] }?.let { return@withContext it }

        val source = BitmapFactory.decodeFile(file.path) ?: return@withContext null
        val scale = min(1f, 1200f / max(source.width, source.height))
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
        val w = bmp.width
        val h = bmp.height
        val buf = ByteBuffer.allocate(w * h * 4)
        bmp.copyPixelsToBuffer(buf)
        val out = wallpaperRender(buf.array(), w.toUInt(), h.toUInt(), e, !dark)

        // Text sits over the top of the artwork: primary text keeps 4.5:1, secondary 3:1
        // against the artwork's worst pixels there.
        fun rgb(p: Palette.Pair) = (p.get(dark) and 0xFFFFFF).toUInt()
        val bg = rgb(Palette.background)
        val primary = wallpaperSafeOpacity(out, w.toUInt(), h.toUInt(), rgb(Palette.text), bg, 0.6f, 4.5f, 1f)
        val muted = wallpaperSafeOpacity(out, w.toUInt(), h.toUInt(), rgb(Palette.secondary), bg, 0.6f, 3f, 1f)

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.copyPixelsFromBuffer(ByteBuffer.wrap(out))
        Render(result, min(primary, muted)).also { synchronized(cache) { cache[key] = it } }
    }
}
