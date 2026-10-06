/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 * Mochi is Coucou's character, drawn in code as on the Mac, the PC and the iPhone.
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class MochiMood { Idle, Alert, Happy, Listening, Sleepy }

/** Coucou's coral: what Mochi wears when he has nothing in particular to say. */
val MochiCoral = Color(0xFFE08A6C)

/**
 * Mochi: a soft squircle with two eyes. He blinks now and then; [MochiMood.Alert] opens his eyes
 * wide and makes him bob, [MochiMood.Happy] turns them into smiles, [MochiMood.Listening] makes
 * them lean in.
 */
@Composable
fun MochiGlyph(
    size: Dp = 24.dp,
    tint: Color = MochiCoral,
    mood: MochiMood = MochiMood.Idle,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "mochi")
    // Eyes open for ~3.6 s, shut for a blink of 0.15 s.
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3800
                1f at 0
                1f at 3600
                0.08f at 3700 using LinearEasing
                1f at 3800
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink",
    )
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 700
                0f at 0
                1f at 350
                0f at 700
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "bob",
    )
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val bodyH = h * 0.78f
        val lift = if (mood == MochiMood.Alert) -bob * h * 0.07f else 0f
        val top = (h - bodyH) / 2f + lift
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, top),
            size = Size(w, bodyH),
            cornerRadius = CornerRadius(bodyH * 0.42f, bodyH * 0.42f),
        )
        val ink = Color(0xFF1B1B20)
        val eyeY = top + bodyH * 0.46f
        val lean = if (mood == MochiMood.Listening) w * 0.04f else 0f
        for (cx in listOf(w * 0.32f + lean, w * 0.68f + lean)) {
            when (mood) {
                MochiMood.Sleepy -> {
                    // closed eyes: a short line each
                    val r = w * 0.07f
                    drawLine(ink, Offset(cx - r, eyeY + r * 0.2f), Offset(cx + r, eyeY + r * 0.2f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
                }
                MochiMood.Happy -> {
                    // ^ ^ : a small arc for each eye
                    val r = w * 0.085f
                    val p = Path().apply {
                        moveTo(cx - r, eyeY + r * 0.6f)
                        quadraticTo(cx, eyeY - r * 1.4f, cx + r, eyeY + r * 0.6f)
                    }
                    drawPath(p, ink, style = Stroke(width = w * 0.055f, cap = StrokeCap.Round))
                }
                else -> {
                    val rx = w * (if (mood == MochiMood.Alert) 0.075f else 0.058f)
                    val ry = rx * 1.55f * blink
                    drawOval(ink, topLeft = Offset(cx - rx, eyeY - ry), size = Size(rx * 2, ry * 2))
                }
            }
        }
    }
}
