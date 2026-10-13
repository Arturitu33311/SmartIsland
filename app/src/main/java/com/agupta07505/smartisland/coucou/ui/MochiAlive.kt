/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 *
 * Mochi with a life of his own: the Compose side of [MochiIdleScript]. [rememberCalmMood] decides
 * awake or asleep (night, or 20 quiet minutes) and [LivelyMochi] plays his little gestures, the
 * thought bubble and the charging bolt around a plain [MochiGlyph].
 */

package com.agupta07505.smartisland.coucou.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.agupta07505.smartisland.coucou.presence.DeviceActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Calendar
import kotlin.random.Random

/**
 * What he is when nothing else decides it. Re-evaluated on a slow clock while awake (the 20 minutes
 * and the "due soon" window drift with time) and quickly while asleep, so a touch wakes him at once.
 * [remindAts] are the open tasks' reminder times in unix seconds.
 */
@Composable
fun rememberCalmMood(remindAts: List<Long>): MochiMood {
    fun compute(): MochiMood {
        val now = System.currentTimeMillis()
        return MochiIdleScript.calmMood(
            night = MochiReactions.isNight(now),
            idleSeconds = DeviceActivity.idleSeconds(),
            soonTask = MochiIdleScript.isSoon(remindAts, now),
        )
    }
    var calm by remember { mutableStateOf(compute()) }
    LaunchedEffect(remindAts) {
        while (isActive) {
            calm = compute()
            delay(if (calm == MochiMood.Sleepy) 2_000L else 20_000L)
        }
    }
    return calm
}

/** Plugged in, watched live (sticky battery state for the start, plug events after). */
@Composable
fun rememberCharging(): Boolean {
    val app = LocalContext.current.applicationContext
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(app) {
        fun read(i: Intent?): Boolean {
            val plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            return plugged != 0
        }
        // A null receiver just returns the last sticky battery broadcast.
        charging = read(app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                charging = when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> true
                    Intent.ACTION_POWER_DISCONNECTED -> false
                    else -> charging
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { app.unregisterReceiver(receiver) } }
    }
    return charging
}

/**
 * [MochiGlyph] that does things by himself while [mood] is [MochiMood.Idle]: a gesture every
 * 6–15 s (yawn, stretch, looking up or around, a hop, a whistle, a scratch, a wink, a thought bubble),
 * a stretch when he wakes, a bolt while charging. [tickleSignal] bumps → he giggles.
 * Every other mood is drawn exactly as before.
 */
@Composable
fun LivelyMochi(
    size: Dp = 24.dp,
    tint: Color = MochiEmerald,
    mood: MochiMood = MochiMood.Idle,
    modifier: Modifier = Modifier,
    lookX: Float = 0f,
    outfit: Outfit = Outfit.None,
    tickleSignal: Int = 0,
) {
    var gesture by remember { mutableStateOf(IdleGesture.None) }
    var thought by remember { mutableStateOf("💭") }
    val progress = remember { Animatable(0f) }
    val charging = rememberCharging()
    var lastMood by remember { mutableStateOf(mood) }
    var lastTickle by remember { mutableStateOf(tickleSignal) }

    LaunchedEffect(mood, tickleSignal) {
        val woke = lastMood == MochiMood.Sleepy && mood == MochiMood.Idle
        val tickled = tickleSignal != lastTickle
        lastMood = mood
        lastTickle = tickleSignal
        if (mood != MochiMood.Idle) {
            gesture = IdleGesture.None
            return@LaunchedEffect
        }
        val rnd = Random(System.nanoTime())
        suspend fun play(g: IdleGesture) {
            gesture = g
            progress.snapTo(0f)
            progress.animateTo(1f, tween(MochiIdleScript.durationMs(g).toInt(), easing = LinearEasing))
            gesture = IdleGesture.None
        }
        var prev = IdleGesture.None
        if (tickled) {
            prev = IdleGesture.Tickle
            play(IdleGesture.Tickle)
        } else if (woke) {
            // Waking up: a proper stretch before anything else.
            prev = IdleGesture.Stretch
            play(IdleGesture.Stretch)
        }
        while (isActive) {
            delay(MochiIdleScript.gapMs(rnd))
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val g = MochiIdleScript.next(prev, hour, allowThought = true, rnd = rnd)
            if (g == IdleGesture.Think) thought = MochiIdleScript.thoughtFor(hour, rnd)
            play(g)
            prev = g
        }
    }

    MochiGlyph(
        size = size,
        tint = tint,
        mood = mood,
        modifier = modifier,
        lookX = lookX,
        outfit = outfit,
        gesture = gesture,
        gestureProgress = progress.value,
        charging = charging,
        thought = thought,
    )
}
