/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.coucou.CoucouTasks
import com.agupta07505.smartisland.model.IslandNotification

/** The desk's state, carried by the notification itself: `progress` is how many tasks are open, `category` "overdue" or "clear". */
object DeskState {
    const val OVERDUE = "overdue"
    const val CLEAR = "clear"
}

/** Left of the collapsed island: Mochi, napping when there is nothing to do, looking up when something is overdue. */
@Composable
fun DeskCollapsedGlyph(notification: IslandNotification?) {
    when (notification?.category) {
        DeskState.OVERDUE -> MochiGlyph(24.dp, ReminderAmber, MochiMood.Alert)
        DeskState.CLEAR -> MochiGlyph(24.dp, MochiEmerald, MochiMood.Sleepy)
        else -> MochiGlyph(24.dp, MochiEmerald, MochiMood.Idle)
    }
}

/** Right of the collapsed island: how many things are open. */
@Composable
fun DeskCollapsedRight(notification: IslandNotification?) {
    val n = notification?.progress ?: 0
    if (n > 0) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background((if (notification?.category == DeskState.OVERDUE) ReminderAmber else MochiEmerald).copy(alpha = 0.22f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("$n", color = if (notification?.category == DeskState.OVERDUE) ReminderAmber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * What Mochi feels like in his bubble. Priority is attention-first: a finished list celebrates
 * over everything (it only lasts 3 s), then something overdue, a tired battery, a hello, news
 * (pop first, stare after), music; at night he naps through calm tasks; emptiness naps by day.
 */
private data class BubbleMood(val mood: MochiMood, val tint: Color, val open: Int)

@Composable
private fun bubbleMood(lookDir: Float): BubbleMood {
    val tasks by CoucouTasks.state.collectAsState()
    val scene = rememberMochiScene()
    val now = System.currentTimeMillis()
    val open = tasks.filter { !it.done }
    val overdue = open.any { it.remindAt != null && it.remindAt * 1000 <= now }
    val celebrating = rememberCelebration(open.size)
    val mood = when {
        celebrating -> MochiMood.Celebrating
        overdue -> MochiMood.Alert
        scene.batteryLow -> MochiMood.Tired
        scene.greeted -> MochiMood.Happy
        scene.surprised -> MochiMood.Surprised
        scene.glancing -> MochiMood.Glance
        scene.musicPlaying -> MochiMood.Dancing
        MochiReactions.isNight(now) -> MochiMood.Sleepy
        open.isEmpty() -> MochiMood.Sleepy
        else -> MochiMood.Idle
    }
    return BubbleMood(mood, if (overdue) ReminderAmber else MochiEmerald, open.size)
}

/**
 * Mochi's secondary bubble: him, always, feeling whatever is going on (celebration, overdue,
 * news and its stare, music, night, nothing). He reads the tasks and the island directly, so
 * every place that shows him stays in sync with no parameters to thread through — except
 * [lookDir], which only the island knows (which side the main pill sits on).
 */
@Composable
fun MochiBubble(size: Dp = 22.dp, lookDir: Float = 0f) {
    val b = bubbleMood(lookDir)
    MochiGlyph(size, b.tint, b.mood, lookX = lookDir)
}

/** His bubble grown into a mini-pill (expanded island): him, with how many things are open. */
@Composable
fun MochiBubblePill(lookDir: Float = 0f) {
    val b = bubbleMood(lookDir)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MochiGlyph(20.dp, b.tint, b.mood, lookX = lookDir)
        if (b.open > 0) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(b.tint.copy(alpha = 0.22f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("${b.open}", color = if (b.mood == MochiMood.Alert) ReminderAmber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
