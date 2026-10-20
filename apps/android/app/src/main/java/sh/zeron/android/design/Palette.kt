package sh.zeron.android.design

import android.graphics.Color
import uniffi.zeron_core.ColorRole

/**
 * App palette (Zeron Light / Zeron Dark): cool neutrals, violet accent.
 * Colors are paint-only — nothing here affects layout.
 */
object Palette {
    class Pair(val light: Int, val dark: Int) {
        fun get(isDark: Boolean) = if (isDark) dark else light
    }

    private fun hex(rgb: Long, alpha: Float = 1f): Int =
        Color.argb((alpha * 255).toInt(), ((rgb shr 16) and 0xFF).toInt(), ((rgb shr 8) and 0xFF).toInt(), (rgb and 0xFF).toInt())

    private fun dyn(light: Long, dark: Long, alpha: Float = 1f) = Pair(hex(light, alpha), hex(dark, alpha))
    private fun dual(light: Int, dark: Int) = Pair(light, dark)
    private fun white(a: Float) = Color.argb((a * 255).toInt(), 255, 255, 255)
    private fun black(a: Float) = Color.argb((a * 255).toInt(), 0, 0, 0)

    val background = dyn(0xF3F3F5, 0x060606)
    val rowActive = dual(white(0.9f), white(0.06f))
    val elevated = dyn(0xFFFFFF, 0x111113)
    val text = dyn(0x27272C, 0xE8E8EA)
    val secondary = dyn(0x62626A, 0xA9A9AE)
    val tertiary = dyn(0x97979F, 0x6B6B72)
    val hairline = dyn(0xE2E2E6, 0x1E1E22)
    val accent = dyn(0x5B43E8, 0x8B7CF6)
    val controlFill = dyn(0x27272C, 0xE8E8EA, 0.075f)
    val accentSoft = dyn(0x5B43E8, 0x8B7CF6, 0.12f)
    val danger = dyn(0xDC2626, 0xF87171)
    val success = dyn(0x15803D, 0x34D399)
    val warning = dyn(0xA16207, 0xFACC15)
    val userBubble = dyn(0xFFFFFF, 0x19191C)
    val codeBackground = dyn(0xFAFAFB, 0x0B0B0D)
    val codeBorder = dyn(0xE4E4E8, 0x1F1F23)
    val chip = dyn(0xE7E7EB, 0x1C1C20)
    val subline = dyn(0x62626A, 0xA9A9AE, 0.5f)

    /** Project monogram tones (project_icon.rs): slate, blue, violet, rose, amber, emerald, teal, orange. */
    val projectDots = listOf(
        dyn(0x475569, 0x94A3B8), dyn(0x2563EB, 0x93C5FD), dyn(0x7C3AED, 0xC4B5FD),
        dyn(0xBE123C, 0xFDA4AF), dyn(0xA16207, 0xFCD34D), dyn(0x047857, 0x6EE7B7),
        dyn(0x0F766E, 0x5EEAD4), dyn(0xC2410C, 0xFDBA74),
    )

    private val inlineCodeText = dyn(0x3F3F46, 0xDCDCE0)
    private val inlineCodeBackground = dyn(0xE9E9ED, 0x1A1A1E)
    private val codeText = dyn(0x303035, 0xE8E8EA)
    private val quoteBar = dyn(0x5B43E8, 0x8B7CF6, 0.45f)
    private val tableBorder = dyn(0xE2E2E6, 0x232327)
    private val tableHeader = dyn(0xF3F3F5, 0x121215)
    private val synKeyword = dyn(0x5B43E8, 0x8B7CF6)
    private val synString = dyn(0x15803D, 0x34D399)
    private val synComment = dyn(0x6B7280, 0x92929A)
    private val synNumber = dyn(0xA16207, 0xFACC15)
    private val synFunction = dyn(0x2563EB, 0x60A5FA)
    private val synType = dyn(0x7E22CE, 0xC084FC)
    private val synVariable = dyn(0x303035, 0xE8E8EA)
    private val synProperty = dyn(0x0E7490, 0x22D3EE)
    private val synOperator = dyn(0x52525B, 0xA1A1AA)
    private val synTag = dyn(0xBE185D, 0xF472B6)
    private val synAttribute = dyn(0xB91C1C, 0xF87171)
    private val textFaint = dyn(0x797981, 0x85858A)
    private val textSoft = dyn(0x303035, 0xE8E8EA, 0.85f)
    private val toolRail = dual(black(0.162f), white(0.12f))
    private val toolBadge = dual(black(0.06f), white(0.06f))
    private val toolWell = dual(white(0.16f), black(0.16f))
    private val agentCard = dual(black(0.03f), white(0.03f))
    private val agentCardBorder = dual(black(0.0945f), white(0.07f))
    private val agentTile = dual(black(0.08f), white(0.08f))
    private val diffAddWash = dyn(0x15803D, 0x34D399, 0.055f)
    private val diffDelWash = dyn(0xDC2626, 0xF87171, 0.055f)
    private val diffAddBar = dyn(0x15803D, 0x34D399, 0.55f)
    private val diffDelBar = dyn(0xDC2626, 0xF87171, 0.55f)
    private val diffHunk = dual(hex(0x5B43E8, 0.07f), hex(0x8B7CF6, 0.08f))

    fun color(role: ColorRole, isDark: Boolean): Int = pair(role).get(isDark)

    private fun pair(role: ColorRole): Pair = when (role) {
        ColorRole.TEXT -> text
        ColorRole.TEXT_SECONDARY -> secondary
        ColorRole.TEXT_TERTIARY -> tertiary
        ColorRole.LINK, ColorRole.ACCENT -> accent
        ColorRole.DANGER -> danger
        ColorRole.SUCCESS -> success
        ColorRole.WARNING -> warning
        ColorRole.INLINE_CODE_TEXT -> inlineCodeText
        ColorRole.INLINE_CODE_BACKGROUND -> inlineCodeBackground
        ColorRole.CODE_TEXT -> codeText
        ColorRole.CODE_BACKGROUND -> codeBackground
        ColorRole.CODE_BORDER -> codeBorder
        ColorRole.QUOTE_BAR -> quoteBar
        ColorRole.RULE -> hairline
        ColorRole.TABLE_BORDER -> tableBorder
        ColorRole.TABLE_HEADER_BACKGROUND -> tableHeader
        ColorRole.USER_BUBBLE -> userBubble
        ColorRole.CHIP_BACKGROUND -> chip
        ColorRole.SYNTAX_KEYWORD -> synKeyword
        ColorRole.SYNTAX_STRING -> synString
        ColorRole.SYNTAX_COMMENT -> synComment
        ColorRole.SYNTAX_NUMBER, ColorRole.SYNTAX_CONSTANT -> synNumber
        ColorRole.SYNTAX_FUNCTION -> synFunction
        ColorRole.SYNTAX_TYPE -> synType
        ColorRole.SYNTAX_VARIABLE -> synVariable
        ColorRole.SYNTAX_PROPERTY, ColorRole.SYNTAX_ESCAPE -> synProperty
        ColorRole.SYNTAX_OPERATOR, ColorRole.SYNTAX_PUNCTUATION -> synOperator
        ColorRole.SYNTAX_TAG -> synTag
        ColorRole.SYNTAX_ATTRIBUTE -> synAttribute
        ColorRole.TEXT_FAINT -> textFaint
        ColorRole.TEXT_SOFT -> textSoft
        ColorRole.TOOL_RAIL -> toolRail
        ColorRole.TOOL_BADGE -> toolBadge
        ColorRole.TOOL_WELL -> toolWell
        ColorRole.AGENT_CARD -> agentCard
        ColorRole.AGENT_CARD_BORDER -> agentCardBorder
        ColorRole.AGENT_TILE -> agentTile
        ColorRole.DIFF_ADD_WASH -> diffAddWash
        ColorRole.DIFF_DEL_WASH -> diffDelWash
        ColorRole.DIFF_ADD_BAR -> diffAddBar
        ColorRole.DIFF_DEL_BAR -> diffDelBar
        ColorRole.DIFF_HUNK -> diffHunk
    }
}
