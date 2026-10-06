/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.coucou.CoucouRuntime
import com.agupta07505.smartisland.coucou.CoucouTask
import com.agupta07505.smartisland.coucou.CoucouTasks
import com.agupta07505.smartisland.coucou.touchIsland
import com.agupta07505.smartisland.coucou.phone.PhoneSettings.jinxSync
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val JinxGreen = Color(0xFF39FF14)
private val Done = Color(0xFF4ADE80)

/**
 * Mochi's desk: what you open the island to find when nothing else is going on. Mochi, your open
 * tasks (a tap finishes one and makes him smile), what Jinx holds that you have not told her about,
 * and a place to add one without leaving the island. He is asleep when there is nothing to do, and
 * looks up when something is overdue.
 */
@Composable
fun MochiDesk(onReplyStateChanged: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tasks by CoucouTasks.state.collectAsState()
    val jinx by CoucouRuntime.jinx.collectAsState()
    var smileUntil by remember { mutableLongStateOf(0L) }
    var adding by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }

    // The desk is opened to be read: bring the list up to date, and ask Jinx for hers.
    LaunchedEffect(Unit) {
        CoucouTasks.refresh(context)
        CoucouRuntime.kick()
    }
    // Mochi stops smiling after a moment.
    LaunchedEffect(smileUntil) {
        if (smileUntil > 0) {
            delay((smileUntil - System.currentTimeMillis()).coerceAtLeast(0))
            now = System.currentTimeMillis()
        }
    }
    // The keyboard needs the island's window to take focus first (what a quick reply does).
    LaunchedEffect(adding) {
        if (adding) {
            delay(180)
            runCatching { focus.requestFocus() }
        }
    }

    val open = tasks.filter { !it.done }.sortedWith(compareBy({ it.remindAt ?: Long.MAX_VALUE }, { it.created }))
    val overdue = open.count { it.remindAt != null && it.remindAt * 1000 < now }
    val mood = when {
        smileUntil > now -> MochiMood.Happy
        overdue > 0 -> MochiMood.Alert
        open.isEmpty() -> MochiMood.Sleepy
        else -> MochiMood.Idle
    }
    val tint = if (overdue > 0) ReminderAmber else MochiCoral

    fun submit() {
        touchIsland(context)
        val text = input.trim()
        if (text.isNotEmpty()) runCatching { CoucouTasks.add(context, text) }
        input = ""
        adding = false
        onReplyStateChanged(false)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(size = 38.dp, tint = tint, mood = mood)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        smileUntil > now -> "Nice!"
                        overdue > 0 -> "$overdue overdue"
                        open.isEmpty() -> "All clear"
                        else -> "${open.size} to do"
                    },
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
                val next = open.firstOrNull { it.remindAt != null && it.remindAt * 1000 >= now }
                Text(
                    next?.let { "next · ${time(it.remindAt!!)}  ${it.title}" } ?: if (open.isEmpty()) "Mochi is napping" else "nothing timed",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        for (t in open.take(4)) TaskRow(t) {
            touchIsland(context)
            CoucouTasks.setDone(context, t.id, true)
            smileUntil = System.currentTimeMillis() + 1600
            now = System.currentTimeMillis()
        }
        if (!adding) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                    .clickable { touchIsland(context); adding = true; onReplyStateChanged(true) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", color = MochiCoral, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Add a task", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(1.dp, MochiCoral.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("llamar a mamá a las 17:30…", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp, maxLines = 1)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                        cursorBrush = SolidColor(MochiCoral),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Icon(
                    Icons.Rounded.Close, "Cancel", tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { input = ""; adding = false; onReplyStateChanged(false) },
                )
            }
        }
    }
}

private fun time(unixSec: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSec * 1000))

@Composable
private fun TaskRow(t: CoucouTask, onDone: () -> Unit) {
    val late = t.remindAt != null && t.remindAt * 1000 < System.currentTimeMillis()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).border(1.6.dp, if (late) ReminderAmber else Color.White.copy(alpha = 0.4f), CircleShape).clickable(onClick = onDone),
        )
        Spacer(Modifier.width(10.dp))
        Text(t.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        t.remindAt?.let {
            Text("⏰ ${time(it)}", color = if (late) ReminderAmber else Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
        }
        if (t.jinx) Box(Modifier.size(6.dp).clip(CircleShape).background(if (t.jinxId != null) JinxGreen else JinxGreen.copy(alpha = 0.35f)))
    }
}
