package com.agupta07505.smartisland.util

import androidx.compose.ui.graphics.Color
import com.agupta07505.smartisland.data.SmartIslandSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeContrastTest {
    private val auto = SmartIslandSettings.EDGE_CONTRAST_AUTO
    private val always = SmartIslandSettings.EDGE_CONTRAST_ALWAYS
    private val never = SmartIslandSettings.EDGE_CONTRAST_NEVER

    @Test
    fun auto_helps_only_a_dark_pill_in_a_dark_system() {
        assertTrue(EdgeContrast.shouldApply(auto, systemDark = true, pillLuminance = 0f))
        assertFalse(EdgeContrast.shouldApply(auto, systemDark = false, pillLuminance = 0f))
        // A light pill is already visible on a dark screen.
        assertFalse(EdgeContrast.shouldApply(auto, systemDark = true, pillLuminance = 0.8f))
    }

    @Test
    fun always_and_never_ignore_the_surroundings() {
        assertTrue(EdgeContrast.shouldApply(always, systemDark = false, pillLuminance = 0.9f))
        assertFalse(EdgeContrast.shouldApply(never, systemDark = true, pillLuminance = 0f))
    }

    @Test
    fun unknown_mode_reads_as_auto() {
        assertTrue(EdgeContrast.shouldApply("???", systemDark = true, pillLuminance = 0f))
        assertFalse(EdgeContrast.shouldApply("???", systemDark = false, pillLuminance = 0f))
    }

    @Test
    fun only_pure_black_is_lifted() {
        val black = Color(0xFF000000)
        val navy = Color(0xFF0A1A3A)
        assertEquals(Color(0xFF1C1C1E), EdgeContrast.fill(black, on = true, luminance = 0f))
        assertEquals(black, EdgeContrast.fill(black, on = false, luminance = 0f))
        assertEquals(navy, EdgeContrast.fill(navy, on = true, luminance = 0.012f))
    }

    @Test
    fun the_setting_round_trips_and_defaults_to_auto() {
        assertEquals(auto, SmartIslandSettings.Default.edgeContrast)
        assertTrue(always in SmartIslandSettings.VALID_EDGE_CONTRAST)
        assertFalse("bright" in SmartIslandSettings.VALID_EDGE_CONTRAST)
    }
}
