package com.agupta07505.smartisland.util

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.agupta07505.smartisland.data.SmartIslandSettings

/**
 * Keeps a dark island readable on a dark screen. A black pill on a black status bar has no edge (a
 * black shadow does nothing there), so only its icons show. When this is on the pill gets a faint
 * lighter fill, a hairline of light around it and a little sheen along its top edge.
 */
object EdgeContrast {
    /** What a pure-black pill is lifted to: the dark "elevated surface" grey. */
    private val LIFTED = Color(0xFF1C1C1E)

    /** Below this relative luminance the pill counts as dark (a chosen colour like navy still does). */
    const val DARK_LUMINANCE = 0.08f

    /** Below this it is the default black, which is the only fill that is nudged toward grey. */
    private const val NEAR_BLACK_LUMINANCE = 0.01f

    const val HAIRLINE_ALPHA = 0.14f
    const val SHEEN_ALPHA = 0.09f

    /** [mode] is one of [SmartIslandSettings.EDGE_CONTRAST_AUTO]/ALWAYS/NEVER (unknown reads as auto). */
    fun shouldApply(mode: String, systemDark: Boolean, pillLuminance: Float): Boolean = when (mode) {
        SmartIslandSettings.EDGE_CONTRAST_ALWAYS -> true
        SmartIslandSettings.EDGE_CONTRAST_NEVER -> false
        else -> systemDark && pillLuminance < DARK_LUMINANCE
    }

    /** The pill's fill: the default black becomes a soft grey when contrast is on; other colours stay. */
    fun fill(pill: Color, on: Boolean, luminance: Float): Color =
        if (on && luminance < NEAR_BLACK_LUMINANCE) lerp(pill, LIFTED, 1f) else pill
}

/** Hairline + top sheen around [shape] when [on]; a no-op otherwise. Apply after clip and background. */
fun Modifier.edgeContrast(on: Boolean, shape: Shape): Modifier =
    if (!on) this else this
        .background(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = EdgeContrast.SHEEN_ALPHA), Color.Transparent)),
            shape,
        )
        .border(1.dp, Color.White.copy(alpha = EdgeContrast.HAIRLINE_ALPHA), shape)
