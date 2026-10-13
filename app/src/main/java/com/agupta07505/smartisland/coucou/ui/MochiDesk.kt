/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    var picking by remember { mutableStateOf(false) }
    val whenState = rememberWhenState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tickles by remember { mutableIntStateOf(0) }
    // The line under his name changes every so often when there is no timed task to show.
    var phraseStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            phraseStep++
        }
    }
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
    val scene = rememberMochiScene()
    val celebrating = rememberCelebration(open.size)
    val remindAts = open.mapNotNull { it.remindAt }
    val calm = rememberCalmMood(remindAts)
    val ring = rememberSoonProgress(remindAts)
    // Same attention order as his bubble (no look direction here: the desk fills the island, so
    // the stare stays centered). Music is ambience: it only gets him dancing when nothing needs
    // him; at night he naps through calm tasks.
    val mood = when {
        celebrating -> MochiMood.Celebrating
        // Finishing a task: a short burst of confetti.
        smileUntil > now -> MochiMood.Celebrating
        overdue > 0 -> MochiMood.Alert
        scene.batteryLow -> MochiMood.Tired
        scene.greeted -> MochiMood.Happy
        scene.jinxMood != null -> scene.jinxMood
        scene.surprised -> MochiMood.Surprised
        scene.glancing -> MochiMood.Glance
        scene.musicPlaying -> MochiMood.Dancing
        else -> calm
    }
    val tint = if (overdue > 0) ReminderAmber else MochiEmerald

    // The calendar choice rides along with the sentence: the day picked wins, the sentence's
    // time of day is kept ("viernes a las 17:30"). Both are cleared after adding, so the next
    // task starts clean instead of inheriting yesterday's day.
    fun submit() {
        touchIsland(context)
        val text = input.trim()
        if (text.isNotEmpty()) {
            runCatching { CoucouTasks.add(context, text, pickedDay = whenState.dayStart, pickedHour = whenState.hour) }
        }
        input = ""
        adding = false
        picking = false
        whenState.reset()
        onReplyStateChanged(false)
    }

    fun cancel() {
        input = ""
        adding = false
        picking = false
        whenState.reset()
        onReplyStateChanged(false)
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A tap on him tickles him (the island stays open: he is a pet, not a button).
            LivelyMochi(
                size = 38.dp, tint = tint, mood = mood, outfit = rememberMochiOutfit(mood),
                tickleSignal = tickles,
                countdown = ring,
                // (No clip: it would cut his hat and the confetti. No ripple either.)
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) {
                    touchIsland(context)
                    Haptics.tap(context)
                    tickles++
                },
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val next = open.firstOrNull { it.remindAt != null && it.remindAt * 1000 >= now }
                val hour = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)
                // What he says is the headline; the count and the next timed task ride underneath.
                val headline = when {
                    smileUntil > now -> "Nice!"
                    mood == MochiMood.Dancing -> "♪ dancing"
                    mood == MochiMood.Sleepy -> "Mochi está durmiendo 💤"
                    else -> MochiIdleScript.phraseAt(MochiIdleScript.phrases(open.size, overdue, hour), phraseStep)
                }
                val status = MochiIdleScript.deskStatus(open.size, overdue) +
                    (next?.let { " · ${time(it.remindAt!!)}  ${it.title}" } ?: "")
                Crossfade(targetState = headline, label = "desk-headline") { line ->
                    Text(
                        line,
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    status,
                    color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        for (t in open.take(4)) key(t.id) {
            TaskRow(
                t,
                onCheck = {
                    touchIsland(context)
                    Haptics.tap(context)
                },
                onDone = {
                    CoucouTasks.setDone(context, t.id, true)
                    smileUntil = System.currentTimeMillis() + 1600
                    now = System.currentTimeMillis()
                },
            )
        }
        if (!adding) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                    .clickable { touchIsland(context); adding = true; onReplyStateChanged(true) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("＋", color = MochiEmerald, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Add a task", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).border(1.dp, MochiEmerald.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (input.isEmpty()) Text("llamar a mamá a las 17:30…", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp, maxLines = 1)
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                            cursorBrush = SolidColor(MochiEmerald),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                    }
                    WhenCalendarButton(picked = whenState.dayStart != null) {
                        touchIsland(context)
                        picking = !picking
                    }
                    Icon(
                        Icons.Rounded.Close, "Cancel", tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp).clip(CircleShape).clickable { touchIsland(context); cancel() },
                    )
                }
                if (picking) WhenPickerPanel(whenState)
            }
        }
    }
}

private fun time(unixSec: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unixSec * 1000))

@Composable
private fun TaskRow(t: CoucouTask, onCheck: () -> Unit, onDone: () -> Unit) {
    val late = t.remindAt != null && t.remindAt * 1000 < System.currentTimeMillis()
    val reduced = rememberReducedMotion()
    // Checked first (the circle fills with a tick), then the row folds away, then the task is really done:
    // what you did is acknowledged, instead of the line vanishing.
    var checked by remember(t.id) { mutableStateOf(false) }
    var gone by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (checked) {
            delay(if (reduced) 0L else 150L)
            gone = true
            delay(if (reduced) 0L else 200L)
            onDone()
        }
    }
    val fill by animateColorAsState(if (checked) Done else Color.Transparent, tween(120), label = "check-fill")
    AnimatedVisibility(
        visible = !gone,
        exit = shrinkVertically(tween(if (reduced) 0 else 180)) + fadeOut(tween(if (reduced) 0 else 150)),
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(fill)
                    .border(1.6.dp, if (checked) Done else if (late) ReminderAmber else Color.White.copy(alpha = 0.4f), CircleShape)
                    .clickable(enabled = !checked) {
                        onCheck()
                        checked = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Icon(Icons.Rounded.Check, null, tint = Color(0xFF0B1F12), modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                t.title,
                color = Color.White.copy(alpha = if (checked) 0.5f else 1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            t.remindAt?.let {
                Text("⏰ ${time(it)}", color = if (late) ReminderAmber else Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}
