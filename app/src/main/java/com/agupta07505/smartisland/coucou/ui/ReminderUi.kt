/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.coucou.CoucouTasks
import com.agupta07505.smartisland.model.IslandNotification

/** The reminder's colour: amber, as in Coucou on the PC. */
val ReminderAmber = Color(0xFFF5A524)

/** Left of the collapsed island: Mochi, looking up. */
@Composable
fun ReminderCollapsedGlyph() {
    MochiGlyph(size = 24.dp, tint = ReminderAmber, mood = MochiMood.Alert)
}

/** Right of the collapsed island: a bell that rings, then rests. */
@Composable
fun ReminderCollapsedRight() {
    val transition = rememberInfiniteTransition(label = "bell")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 2400
                0f at 0
                16f at 200 using FastOutSlowInEasing
                -13f at 400
                9f at 600
                -6f at 800
                0f at 1000
                0f at 2400
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring",
    )
    Icon(
        Icons.Rounded.Notifications,
        contentDescription = "Reminder",
        tint = ReminderAmber,
        modifier = Modifier
            .size(16.dp)
            .graphicsLayer { rotationZ = angle },
    )
}

/** The open island: the task, and the three things to do with it. */
@Composable
fun ReminderExpanded(
    notification: IslandNotification?,
    bottomPadding: Dp,
    onCollapse: () -> Unit,
) {
    val context = LocalContext.current
    val id = notification?.key?.let(CoucouTasks::taskIdOf)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(size = 38.dp, tint = ReminderAmber, mood = MochiMood.Alert)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    notification?.title.orEmpty(),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Reminder", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.size(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill("Done", Modifier.weight(1f), Color(0xFF4ADE80), icon = true) {
                id?.let { CoucouTasks.setDone(context, it, true) }
                onCollapse()
            }
            ActionPill("+10 min", Modifier.weight(1f), ReminderAmber) {
                id?.let { CoucouTasks.snooze(context, it, 600) }
                onCollapse()
            }
            ActionPill("Dismiss", Modifier.weight(1f), Color.White.copy(alpha = 0.6f), cross = true) {
                id?.let { CoucouTasks.withdraw(context, it) }
                onCollapse()
            }
        }
    }
}

@Composable
private fun ActionPill(
    label: String,
    modifier: Modifier,
    color: Color,
    icon: Boolean = false,
    cross: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) Icon(Icons.Rounded.Check, null, tint = color, modifier = Modifier.size(15.dp))
        if (cross) Icon(Icons.Rounded.Close, null, tint = color, modifier = Modifier.size(15.dp))
        if (icon || cross) Spacer(Modifier.width(4.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** A small round marker used where a dot is enough. */
@Composable
fun AmberDot(size: Dp = 8.dp) {
    Spacer(Modifier.size(size).clip(CircleShape).background(ReminderAmber))
}
