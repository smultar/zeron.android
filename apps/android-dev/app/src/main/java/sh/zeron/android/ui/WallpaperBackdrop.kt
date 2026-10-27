package sh.zeron.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import sh.zeron.android.design.WallpaperStore

/**
 * The wallpaper hero: aspect-filled artwork that fades out downward (alpha,
 * never a tinted overlay — the page shows through), at the opacity the
 * contrast guard allows so text over it stays readable.
 */
@Composable
fun WallpaperBackdrop(modifier: Modifier = Modifier) {
    val dark = Z.dark
    val version = WallpaperStore.version
    var render by remember { mutableStateOf<WallpaperStore.Render?>(null) }
    LaunchedEffect(dark, version) { render = WallpaperStore.render(dark) }
    val r = render
    // Artwork arriving late fades in (desktop: 120 ms).
    val alpha by animateFloatAsState(if (r != null) r.opacity else 0f, tween(120), label = "wallpaper")
    if (r == null) return

    // Smoothstep ease from opaque at the top to clear at the bottom, so the fade has no visible band.
    val steps = 24
    val stops = Array(steps + 1) { i ->
        val t = i / steps.toFloat()
        t to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
    }
    Image(
        bitmap = r.bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer(alpha = alpha, compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(colorStops = stops), blendMode = BlendMode.DstIn)
            },
    )
}
