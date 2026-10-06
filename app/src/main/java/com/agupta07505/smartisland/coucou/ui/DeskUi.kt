/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        DeskState.CLEAR -> MochiGlyph(24.dp, MochiCoral, MochiMood.Sleepy)
        else -> MochiGlyph(24.dp, MochiCoral, MochiMood.Idle)
    }
}

/** Right of the collapsed island: how many things are open. */
@Composable
fun DeskCollapsedRight(notification: IslandNotification?) {
    val n = notification?.progress ?: 0
    if (n > 0) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background((if (notification?.category == DeskState.OVERDUE) ReminderAmber else MochiCoral).copy(alpha = 0.22f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text("$n", color = if (notification?.category == DeskState.OVERDUE) ReminderAmber else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
