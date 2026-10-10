/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 * Mochi is Coucou's character, drawn in code as on the Mac, the PC and the iPhone.
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** What Mochi feels. Alert/Happy/Listening/Sleepy are his desk states; Surprised, Glance and
 * Dancing are his reactions to the island (news, then looking at it, music); Tired is low
 * battery and Celebrating is an emptied task list. */
enum class MochiMood { Idle, Alert, Happy, Listening, Sleepy, Surprised, Glance, Dancing, Tired, Celebrating }

/** Mochi's emerald: what he wears whenever no other colour means something (overdue is amber,
 * Claude is orange, questions are blue — those stay). */
val MochiEmerald = Color(0xFF10B981)

/** Confetti for the celebration: coral, white, amber, sky, green and lilac, cycling. */
private val CONFETTI = listOf(
    Color(0xFFE08A6C),
    Color.White,
    Color(0xFFF5A524),
    Color(0xFF7DD3FC),
    Color(0xFF4ADE80),
    Color(0xFFC4B5FD),
)

/**
 * Mochi: a soft squircle with two eyes. He blinks now and then and breathes; [MochiMood.Alert]
 * opens his eyes wide and makes him bob, [MochiMood.Happy] turns them into smiles,
 * [MochiMood.Listening] makes them lean in, [MochiMood.Sleepy] closes them and floats a "z",
 * [MochiMood.Surprised] stretches him up with wide eyes and a "!", [MochiMood.Dancing]
 * sways him side to side with happy eyes and music notes, and [MochiMood.Tired] droops his lids
 * with a bead of sweat for low battery. [MochiMood.Happy] also bounces gently (unlock greeting).
 * [MochiMood.Glance] slides his pupils toward the main pill ([lookX]); [MochiMood.Celebrating]
 * jumps with confetti when the task list empties. Left alone in [MochiMood.Idle] he peeks sideways
 * about every 12 s, never often.
 */
@Composable
fun MochiGlyph(
    size: Dp = 24.dp,
    tint: Color = MochiEmerald,
    mood: MochiMood = MochiMood.Idle,
    modifier: Modifier = Modifier,
    // Where he looks when glancing (bubble passes toward-main: +1 main is right, -1 left).
    lookX: Float = 0f,
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
    // Slow bounce/breathing (1.1 s): shared by the alert bob, the happy bounce, the surprise
    // tremble and the calm breathing — one tempo for the whole body keeps him coherent.
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1100
                0f at 0
                1f at 550
                0f at 1100
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "bob",
    )
    // The dance beat: one sway every 1.4 s. It runs on its own phase (not on `bob`, which is
    // the alert bounce) so dancing and bobbing never interfere with each other. Kept slow on
    // purpose: at phone-island size anything faster reads as nervous.
    val beat by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "beat",
    )
    // A fresh surprise pops in once and settles back: a soft one-shot spring (not a loop), so
    // every new arrival replays the pop instead of throbbing forever.
    val pop by animateFloatAsState(
        targetValue = if (mood == MochiMood.Surprised) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 220f),
        label = "pop",
    )
    val dancing = mood == MochiMood.Dancing
    val surprised = mood == MochiMood.Surprised
    val tired = mood == MochiMood.Tired
    val celebrating = mood == MochiMood.Celebrating
    val glancing = mood == MochiMood.Glance
    // Text decorations ("!", notes, "z") go through Compose's own text measurer: no platform-canvas
    // interop, so they work on every canvas that draws him (island overlay included).
    val textMeasurer = rememberTextMeasurer()
    // Idle curiosity: about every 12 s he peeks once each way (never often — the island must
    // stay calm). Mood-level reactions (glance, surprise) override this entirely.
    val peek by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 12_000
                0f at 0
                0f at 8000
                1f at 9000 using LinearEasing
                0f at 10_100 using LinearEasing
                -1f at 11_100 using LinearEasing
                0f at 12_000 using LinearEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "peek",
    )
    val wave = (beat * 2 * PI).toFloat()
    // Whole-body language, done as GPU transforms (no redraw): tilt/sway/hop for dancing, a
    // tremble for surprise, a weary lean for low battery, and quiet breathing only while calm
    // (dance, surprise and tiredness already move him).
    // NOTE: the pop scale applies only while surprised — every other mood draws at full size.
    val tilt = when {
        dancing -> sin(wave) * 13f
        celebrating -> sin(wave) * 8f
        tired -> 7f
        glancing -> lookX * 6f
        else -> 0f
    }
    val shiftX = if (dancing) sin(wave) * 0.07f else if (surprised) (bob - 0.5f) * 0.05f else 0f
    val hop = when {
        dancing -> -abs(sin(wave)) * 0.09f
        celebrating -> -abs(sin(wave)) * 0.13f
        else -> 0f
    }
    val breathe = if (!dancing && !surprised && !tired) 1f + (bob - 0.5f) * 0.03f else 1f
    val scale = if (surprised) 0.6f + pop * 0.4f else 1f
    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                rotationZ = tilt
                translationX = shiftX * size.toPx()
                translationY = hop * size.toPx()
                scaleX = scale * breathe
                scaleY = scale * (if (dancing) 2f - breathe else 1f)
            },
    ) {
        val w = this.size.width
        val h = this.size.height
        val stretch = if (surprised) 1.06f else 1f
        val bodyH = h * 0.78f * stretch
        // Each mood lifts differently: Alert bobs hard, Happy bounces gently (task done, hello on
        // unlock), Surprised jumps once with the pop, Dancing hops on the beat. Tired slumps: no lift.
        val lift = when {
            mood == MochiMood.Alert -> -bob * h * 0.07f
            mood == MochiMood.Happy -> -bob * h * 0.035f
            surprised -> -pop * h * 0.08f
            dancing -> -abs(sin(wave)) * h * 0.05f
            else -> 0f
        }
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
        // Looking aside: on purpose toward the main pill when glancing, idly both ways in Idle.
        val glanceShift = if (glancing) lookX * w * 0.10f else 0f
        val idleShift = if (mood == MochiMood.Idle) peek * w * 0.07f else 0f
        val happyEyes = mood == MochiMood.Happy || dancing || celebrating
        for (cx in listOf(w * 0.32f + lean + glanceShift + idleShift, w * 0.68f + lean + glanceShift + idleShift)) {
            when {
                mood == MochiMood.Sleepy -> {
                    // closed eyes: a short line each
                    val r = w * 0.07f
                    drawLine(ink, Offset(cx - r, eyeY + r * 0.2f), Offset(cx + r, eyeY + r * 0.2f), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
                }
                happyEyes -> {
                    // ^ ^ : a small arc for each eye
                    val r = w * 0.085f
                    val p = Path().apply {
                        moveTo(cx - r, eyeY + r * 0.6f)
                        quadraticTo(cx, eyeY - r * 1.4f, cx + r, eyeY + r * 0.6f)
                    }
                    drawPath(p, ink, style = Stroke(width = w * 0.055f, cap = StrokeCap.Round))
                }
                surprised -> {
                    // Wide open and frozen: a surprise never blinks, or the moment is lost.
                    val rx = w * 0.08f
                    val ry = rx * 2.05f
                    drawOval(ink, topLeft = Offset(cx - rx, eyeY - ry), size = Size(rx * 2, ry * 2))
                }
                tired -> {
                    // Heavy lids at low battery: half-mast ovals that still blink (slowly, with `blink`).
                    val rx = w * 0.062f
                    val ry = (rx * 0.55f).coerceAtLeast(0.5f) * (0.35f + 0.65f * blink)
                    drawOval(ink, topLeft = Offset(cx - rx, eyeY - ry), size = Size(rx * 2, ry * 2))
                }
                glancing -> {
                    // Eyes slid toward the news, wide and blinking: he is watching the main pill.
                    val rx = w * 0.062f
                    val ry = rx * 1.6f * blink
                    drawOval(ink, topLeft = Offset(cx - rx, eyeY - ry), size = Size(rx * 2, ry * 2))
                }
                else -> {
                    val rx = w * (if (mood == MochiMood.Alert) 0.075f else 0.058f)
                    val ry = rx * 1.55f * blink
                    drawOval(ink, topLeft = Offset(cx - rx, eyeY - ry), size = Size(rx * 2, ry * 2))
                }
            }
        }
        if (surprised && pop > 0.4f) {
            // "!" over his head, fading in with the pop.
            glyph(textMeasurer, "!", w * 0.5f, top - h * 0.10f, w * 0.34f, Color.White, pop.coerceIn(0f, 1f))
        }
        if (dancing) {
            // Two notes taking turns in the air.
            val up = (sin(wave) + 1f) / 2f
            glyph(textMeasurer, "♪", w * 0.06f, top - h * (0.02f + up * 0.16f), w * 0.30f, Color.White, 0.35f + up * 0.65f)
            glyph(textMeasurer, "♫", w * 0.94f, top - h * (0.02f + (1f - up) * 0.16f), w * 0.26f, Color.White, 0.35f + (1f - up) * 0.65f)
        }
        if (mood == MochiMood.Sleepy) {
            // A "z" drifts up as his eyes close.
            val shut = 1f - blink
            glyph(textMeasurer, "z", w * 0.88f, top - h * (0.06f + shut * 0.14f), w * (0.22f + shut * 0.10f), Color.White, 0.30f + shut * 0.60f)
        }
        if (tired) {
            // One bead of sweat: low battery is exhausting. It slides down and back with `bob`.
            val dropX = w * 0.94f
            val dropY = top + bodyH * (0.18f + bob * 0.10f)
            val r = w * 0.045f
            val sweat = Color(0xFF7DD3FC)
            drawCircle(sweat, radius = r, center = Offset(dropX, dropY))
            val tip = Path().apply {
                moveTo(dropX - r * 0.8f, dropY - r * 0.2f)
                quadraticTo(dropX, dropY - r * 1.8f, dropX + r * 0.8f, dropY - r * 0.2f)
                close()
            }
            drawPath(tip, sweat)
        }
        if (celebrating) {
            // Confetti for an emptied list: six dots orbiting him, each halfway around from the
            // next, rising and falling with the beat. Fixed palette, no allocation per frame.
            val cx0 = w / 2f
            val cy0 = top + bodyH * 0.4f
            for (i in 0 until 6) {
                val a = wave + i * (2 * PI / 6).toFloat()
                val orbit = w * (0.52f + 0.10f * sin(wave * 2f + i))
                drawCircle(
                    color = CONFETTI[i % CONFETTI.size],
                    radius = w * 0.032f,
                    center = Offset(cx0 + cos(a) * orbit, cy0 + sin(a) * orbit * 0.7f),
                )
            }
        }
    }
}

/** A single character over the glyph ("!", a note, a "z"), centered on (cx, y): drawn with
 * Compose's own text pipeline, so decorations need no platform canvas and cost nothing when
 * their mood is off. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.glyph(
    measurer: androidx.compose.ui.text.TextMeasurer,
    char: String,
    cx: Float,
    y: Float,
    textSize: Float,
    color: Color,
    alpha: Float,
) {
    val layout = measurer.measure(
        AnnotatedString(char),
        style = TextStyle(
            color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
            fontSize = textSize.toSp(),
            fontWeight = FontWeight.Bold,
        ),
    )
    drawText(
        layout,
        topLeft = Offset(cx - layout.size.width / 2f, y - layout.size.height / 2f),
    )
}
