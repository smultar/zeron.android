package sh.zeron.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import sh.zeron.android.design.Palette

val LocalDark = compositionLocalOf { false }

/** Zeron's own color roles, resolved for the current appearance. */
object Z {
    val dark: Boolean @Composable @ReadOnlyComposable get() = LocalDark.current
    val background: Color @Composable @ReadOnlyComposable get() = c(Palette.background)
    val elevated: Color @Composable @ReadOnlyComposable get() = c(Palette.elevated)
    val text: Color @Composable @ReadOnlyComposable get() = c(Palette.text)
    val secondary: Color @Composable @ReadOnlyComposable get() = c(Palette.secondary)
    val tertiary: Color @Composable @ReadOnlyComposable get() = c(Palette.tertiary)
    val hairline: Color @Composable @ReadOnlyComposable get() = c(Palette.hairline)
    val accent: Color @Composable @ReadOnlyComposable get() = c(Palette.accent)
    val accentSoft: Color @Composable @ReadOnlyComposable get() = c(Palette.accentSoft)
    val controlFill: Color @Composable @ReadOnlyComposable get() = c(Palette.controlFill)
    val danger: Color @Composable @ReadOnlyComposable get() = c(Palette.danger)
    val success: Color @Composable @ReadOnlyComposable get() = c(Palette.success)
    val warning: Color @Composable @ReadOnlyComposable get() = c(Palette.warning)
    val chip: Color @Composable @ReadOnlyComposable get() = c(Palette.chip)
    val userBubble: Color @Composable @ReadOnlyComposable get() = c(Palette.userBubble)
    val subline: Color @Composable @ReadOnlyComposable get() = c(Palette.subline)
    val rowActive: Color @Composable @ReadOnlyComposable get() = c(Palette.rowActive)

    @Composable
    @ReadOnlyComposable
    fun c(p: Palette.Pair) = Color(p.get(LocalDark.current))

    @Composable
    @ReadOnlyComposable
    fun project(index: Int) = c(Palette.projectDots[index.mod(Palette.projectDots.size)])
}

@Composable
private fun geist(file: String, weight: FontWeight): Font {
    val assets = LocalContext.current.assets
    return Font("fonts/$file.ttf", assets, weight)
}

@Composable
fun rememberFonts(): Pair<FontFamily, FontFamily> {
    val sans = FontFamily(
        geist("Geist", FontWeight.Normal),
        geist("Geist-Medium", FontWeight.Medium),
        geist("Geist-SemiBold", FontWeight.SemiBold),
        geist("Geist-Bold", FontWeight.Bold),
    )
    val mono = FontFamily(
        geist("GeistMono", FontWeight.Normal),
        geist("GeistMono-Medium", FontWeight.Medium),
        geist("GeistMono-SemiBold", FontWeight.SemiBold),
    )
    return sans to mono
}

val LocalMono = compositionLocalOf<FontFamily> { FontFamily.Monospace }

@Composable
fun ZeronTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val (sans, mono) = rememberFonts()
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(
            primary = Color(Palette.accent.dark), onPrimary = Color(Palette.background.dark),
            background = Color(Palette.background.dark), onBackground = Color(Palette.text.dark),
            surface = Color(Palette.background.dark), onSurface = Color(Palette.text.dark),
            surfaceVariant = Color(Palette.elevated.dark), onSurfaceVariant = Color(Palette.secondary.dark),
            outline = Color(Palette.hairline.dark), error = Color(Palette.danger.dark),
            surfaceContainer = Color(Palette.elevated.dark), surfaceContainerHigh = Color(Palette.elevated.dark),
        )
    } else {
        lightColorScheme(
            primary = Color(Palette.accent.light), onPrimary = Color.White,
            background = Color(Palette.background.light), onBackground = Color(Palette.text.light),
            surface = Color(Palette.background.light), onSurface = Color(Palette.text.light),
            surfaceVariant = Color(Palette.elevated.light), onSurfaceVariant = Color(Palette.secondary.light),
            outline = Color(Palette.hairline.light), error = Color(Palette.danger.light),
            surfaceContainer = Color(Palette.elevated.light), surfaceContainerHigh = Color(Palette.elevated.light),
        )
    }
    fun t(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.35).toInt()) =
        TextStyle(fontFamily = sans, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)
    val typography = Typography(
        displayLarge = t(34, FontWeight.SemiBold), headlineLarge = t(30, FontWeight.SemiBold),
        headlineMedium = t(24, FontWeight.SemiBold), headlineSmall = t(20, FontWeight.SemiBold),
        titleLarge = t(20, FontWeight.SemiBold), titleMedium = t(16, FontWeight.Medium), titleSmall = t(14, FontWeight.Medium),
        bodyLarge = t(16), bodyMedium = t(14), bodySmall = t(12),
        labelLarge = t(14, FontWeight.Medium), labelMedium = t(12, FontWeight.Medium), labelSmall = t(11, FontWeight.Medium),
    )
    CompositionLocalProvider(LocalDark provides dark, LocalMono provides mono) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
