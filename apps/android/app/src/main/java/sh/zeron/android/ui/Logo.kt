package sh.zeron.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** The Zeron glyph: 34 100-unit cells on an 820 × 940 grid (from dist/zeron.svg). */
private val CELLS = listOf(0f to 600f, 0f to 720f, 240f to 840f, 240f to 720f, 120f to 840f, 120f to 600f, 240f to 600f, 0f to 480f, 0f to 360f, 480f to 840f, 480f to 720f, 120f to 360f, 120f to 240f, 240f to 360f, 600f to 720f, 480f to 600f, 360f to 360f, 240f to 240f, 600f to 600f, 720f to 600f, 720f to 480f, 240f to 120f, 600f to 380f, 720f to 240f, 720f to 0f, 480f to 240f, 480f to 0f, 120f to 480f, 240f to 480f, 360f to 840f, 360f to 720f, 360f to 600f, 360f to 480f, 120f to 720f)
private const val GLYPH_W = 820f
private const val GLYPH_H = 940f

/**
 * The original Zeron logo. With `tile`, the glyph sits white on the black
 * rounded square of the app icon; without, it is drawn in `color` alone.
 */
@Composable
fun ZeronLogo(modifier: Modifier = Modifier, tile: Boolean = false, color: Color = Color.Unspecified) {
    val ink = if (color == Color.Unspecified) Z.text else color
    Canvas(modifier.aspectRatio(if (tile) 1f else GLYPH_W / GLYPH_H)) {
        if (tile) {
            drawRoundRect(Color.Black, Offset.Zero, size, CornerRadius(size.width * 0.225f))
            val s = size.height * 0.62f / GLYPH_H
            val ox = (size.width - GLYPH_W * s) / 2f
            val oy = (size.height - GLYPH_H * s) / 2f
            for ((x, y) in CELLS) drawRoundRect(Color.White, Offset(ox + x * s, oy + y * s), Size(100f * s, 100f * s), CornerRadius(16f * s))
        } else {
            val s = size.height / GLYPH_H
            val ox = (size.width - GLYPH_W * s) / 2f
            for ((x, y) in CELLS) drawRoundRect(ink, Offset(ox + x * s, y * s), Size(100f * s, 100f * s), CornerRadius(16f * s))
        }
    }
}

/** The Zeron app icon (the desktop's statue artwork), rounded like the icon itself. */
@Composable
fun ZeronIcon(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(sh.zeron.android.R.drawable.zeron_icon),
        contentDescription = "Zeron",
        modifier = modifier,
    )
}
