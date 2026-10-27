package sh.zeron.android.core

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import uniffi.zeron_core.FaceData
import uniffi.zeron_core.FaceRole
import uniffi.zeron_core.PlatformMeasurer
import uniffi.zeron_core.StyleDesc
import uniffi.zeron_core.TextSystem
import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled faces. Rust measures the exact bytes Android draws with, so
 * measurement and rendering share one source of truth.
 */
object Fonts {
    private val files = listOf(
        FaceRole.SANS to "Geist",
        FaceRole.SANS_MEDIUM to "Geist-Medium",
        FaceRole.SANS_SEMIBOLD to "Geist-SemiBold",
        FaceRole.SANS_BOLD to "Geist-Bold",
        FaceRole.SANS_ITALIC to "Geist-Italic",
        FaceRole.SANS_MEDIUM_ITALIC to "Geist-MediumItalic",
        FaceRole.SANS_SEMIBOLD_ITALIC to "Geist-SemiBoldItalic",
        FaceRole.SANS_BOLD_ITALIC to "Geist-BoldItalic",
        FaceRole.MONO to "GeistMono",
        FaceRole.MONO_MEDIUM to "GeistMono-Medium",
        FaceRole.MONO_SEMIBOLD to "GeistMono-SemiBold",
        FaceRole.MONO_ITALIC to "GeistMono-Italic",
    )

    private val typefaces = ConcurrentHashMap<FaceRole, Typeface>()

    /** Load every face once; returns the bytes the Rust text engine measures. */
    fun load(context: Context): List<FaceData> {
        val out = ArrayList<FaceData>(files.size)
        for ((role, name) in files) {
            val path = "fonts/$name.ttf"
            val bytes = context.assets.open(path).use { it.readBytes() }
            typefaces[role] = Typeface.createFromAsset(context.assets, path)
            out += FaceData(role, bytes)
        }
        return out
    }

    fun typeface(role: FaceRole): Typeface = typefaces[role] ?: typefaces.getValue(FaceRole.SANS)

    /** A paint for `role` at `size` (points = dp; the canvas carries density). */
    fun paint(role: FaceRole, size: Float, ligatures: Boolean = true): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(role)
        textSize = size
        fontFeatureSettings = if (ligatures) "'liga' 1" else "'liga' 0"
        isLinearText = true
    }

    /** Human-facing UI text face, for Compose and plain views. */
    fun ui(role: FaceRole): Typeface = typeface(role)
}

/** Android's text engine as ground truth for glyphs the bundled faces miss. */
class AndroidMeasurer : PlatformMeasurer {
    private val paints = ThreadLocal<HashMap<Long, Paint>>()

    private fun paint(face: FaceRole, size: Float, ligatures: Boolean): Paint {
        val map = paints.get() ?: HashMap<Long, Paint>().also { paints.set(it) }
        val key = (face.ordinal.toLong() shl 40) or ((size * 100).toLong() shl 1) or (if (ligatures) 1L else 0L)
        return map.getOrPut(key) { Fonts.paint(face, size, ligatures) }
    }

    override fun measure(face: FaceRole, size: Float, ligatures: Boolean, text: String): Float =
        paint(face, size, ligatures).measureText(text)

    override fun measureRun(face: FaceRole, size: Float, ligatures: Boolean, text: String): List<Float> {
        if (text.isEmpty()) return emptyList()
        val p = paint(face, size, ligatures)
        val out = ArrayList<Float>(text.length)
        var unit = 0
        var previous = 0f
        while (unit < text.length) {
            val next = unit + Character.charCount(text.codePointAt(unit))
            val advance = p.getRunAdvance(text, 0, text.length, 0, text.length, false, next)
            out += advance - previous
            previous = advance
            unit = next
        }
        return out
    }
}

/** One process-wide text system shared by every transcript. */
object TextEngine {
    lateinit var shared: TextSystem
        private set

    fun init(context: Context) {
        if (!::shared.isInitialized) {
            shared = TextSystem(Fonts.load(context.applicationContext), AndroidMeasurer())
        }
    }
}

/** style id → Paint for one transcript (ids are per layout view). */
class StyleFonts {
    private val paints = ConcurrentHashMap<Int, Paint>()

    fun update(styles: List<StyleDesc>) {
        for (s in styles) {
            val id = s.id.toInt()
            if (!paints.containsKey(id)) paints[id] = Fonts.paint(s.face, s.size, s.ligatures)
        }
    }

    fun paint(id: UShort): Paint? = paints[id.toInt()]
}
