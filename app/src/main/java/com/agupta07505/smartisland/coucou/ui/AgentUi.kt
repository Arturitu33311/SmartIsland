/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.coucou.CoucouRuntime
import com.agupta07505.smartisland.coucou.IslandAgents
import com.agupta07505.smartisland.coucou.agents.ConfirmActivity
import com.agupta07505.smartisland.coucou.agents.Pending
import com.agupta07505.smartisland.coucou.agents.RemoteProtocol
import com.agupta07505.smartisland.coucou.touchIsland
import com.agupta07505.smartisland.coucou.agents.SessionState
import com.agupta07505.smartisland.model.IslandNotification

/** Claude Code's colour, as on the PC. */
val ClaudeOrange = Color(0xFFD97757)
val QuestionBlue = Color(0xFF60A5FA)
private val Green = Color(0xFF4ADE80)
private val Red = Color(0xFFF4505E)

/** Left of the collapsed island: Mochi, in Claude's colour. */
@Composable
fun AgentCollapsedGlyph(notification: IslandNotification?) {
    val mode = notification?.mode
    when (mode) {
        com.agupta07505.smartisland.model.IslandMode.Approval -> MochiGlyph(24.dp, ReminderAmber, MochiMood.Alert)
        com.agupta07505.smartisland.model.IslandMode.Question -> MochiGlyph(24.dp, QuestionBlue, MochiMood.Alert)
        else -> MochiGlyph(24.dp, ClaudeOrange, MochiMood.Listening)
    }
}

/** Right of the collapsed island: three dots that take turns while it works, a lock or a question mark when it asks. */
@Composable
fun AgentCollapsedRight(notification: IslandNotification?) {
    when (notification?.mode) {
        com.agupta07505.smartisland.model.IslandMode.Approval ->
            Icon(Icons.Rounded.Lock, "Permission", tint = ReminderAmber, modifier = Modifier.size(15.dp))
        com.agupta07505.smartisland.model.IslandMode.Question ->
            Icon(Icons.Rounded.QuestionMark, "Question", tint = QuestionBlue, modifier = Modifier.size(16.dp))
        else -> WorkingDots(ClaudeOrange)
    }
}

@Composable
private fun WorkingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val a by t.animateFloat(
                0.25f, 0.25f,
                infiniteRepeatable(
                    keyframes {
                        durationMillis = 1000
                        0.25f at 0
                        1f at 200 + i * 150
                        0.25f at 500 + i * 150
                    },
                    RepeatMode.Restart,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(5.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(color))
        }
    }
}

/** The open island while Claude Code works: each session, what it is doing, and whether it needs you. */
@Composable
fun AgentExpanded(bottomPadding: Dp) {
    val ui by CoucouRuntime.agentUi.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 6.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(30.dp, ClaudeOrange, MochiMood.Listening)
            Spacer(Modifier.width(10.dp))
            Text("Claude Code", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.size(8.dp))
        val sessions = ui.sessions.filter { it.state != SessionState.Idle }.ifEmpty { ui.sessions }.take(3)
        if (sessions.isEmpty()) Text("Nothing running", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        for (s in sessions) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                val dot = when (s.state) {
                    SessionState.Working -> ClaudeOrange
                    SessionState.NeedsYou -> ReminderAmber
                    SessionState.Idle -> Color.White.copy(alpha = 0.3f)
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
                Text(s.project.ifEmpty { "session" }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(
                    listOf(s.tool, s.detail).filter { it.isNotEmpty() }.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun pendingOf(notification: IslandNotification?, ui: com.agupta07505.smartisland.coucou.agents.AgentUi): Pending? {
    val id = notification?.key?.let(IslandAgents::idOf) ?: return null
    return ui.pending.firstOrNull { it.id == id }
}

/** A permission request: what Claude Code wants to do, and Allow (with the fingerprint) or Deny. */
@Composable
fun ApprovalExpanded(notification: IslandNotification?, bottomPadding: Dp, onCollapse: () -> Unit) {
    val context = LocalContext.current
    val ui by CoucouRuntime.agentUi.collectAsState()
    val p = pendingOf(notification, ui)
    // Without a fingerprint or a screen lock to ask, a second tap is the confirmation.
    var armed by remember(notification?.key) { mutableStateOf(false) }
    val secure = remember { ConfirmActivity.available(context) }
    var message by remember(notification?.key) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp).padding(top = 6.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(30.dp, ReminderAmber, MochiMood.Alert)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${p?.project.orEmpty().ifEmpty { "Claude Code" }} wants to use ${p?.tool ?: notification?.title.orEmpty()}",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(
            p?.detail ?: notification?.text.orEmpty(),
            color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.07f)).padding(8.dp),
        )
        message?.let { Text(it, color = Red, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
        Spacer(Modifier.size(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Deny", Modifier.weight(1f), Red) {
                p?.let { if (!CoucouRuntime.sendToLaptop(RemoteProtocol.answer(it.id, "deny"))) message = "No link to the laptop" else onCollapse() }
            }
            Pill("Terminal", Modifier.weight(1f), Color.White.copy(alpha = 0.6f)) {
                p?.let { if (!CoucouRuntime.sendToLaptop(RemoteProtocol.decline(it.id))) message = "No link to the laptop" else onCollapse() }
            }
            Pill(if (armed) "Tap to allow" else "Allow", Modifier.weight(1f), Green) {
                val req = p ?: return@Pill
                val send = {
                    if (!CoucouRuntime.sendToLaptop(RemoteProtocol.answer(req.id, "allow"))) message = "No link to the laptop" else onCollapse()
                }
                when {
                    secure -> ConfirmActivity.ask(context, "Allow on the laptop?", "${req.tool}: ${req.detail.take(80)}") { ok -> if (ok) send() else message = "Not confirmed" }
                    armed -> send()
                    else -> armed = true
                }
            }
        }
    }
}

/** An AskUserQuestion: the options as buttons; a multiple-choice question is ticked and then sent. */
@Composable
fun QuestionExpanded(notification: IslandNotification?, bottomPadding: Dp, onCollapse: () -> Unit) {
    val ui by CoucouRuntime.agentUi.collectAsState()
    val p = pendingOf(notification, ui)
    val questions = p?.questions.orEmpty()
    // One question at a time, as on the PC.
    var index by remember(notification?.key) { mutableStateOf(0) }
    val answers = remember(notification?.key) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var picked by remember(notification?.key, index) { mutableStateOf<List<String>>(emptyList()) }
    var message by remember(notification?.key) { mutableStateOf<String?>(null) }
    val q = questions.getOrNull(index)
    val context = LocalContext.current

    fun finish(all: Map<String, List<String>>) {
        val req = p ?: return
        val multi = questions.filter { it.multi }.map { it.question }.toSet()
        if (!CoucouRuntime.sendToLaptop(RemoteProtocol.answerQuestion(req.id, all, multi))) message = "No link to the laptop" else onCollapse()
    }

    fun next(chosen: List<String>) {
        val cur = q ?: return
        touchIsland(context)
        val all = answers.value + (cur.question to chosen)
        answers.value = all
        if (index + 1 < questions.size) index += 1 else finish(all)
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 8.dp, bottom = bottomPadding.coerceAtLeast(10.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(28.dp, QuestionBlue, MochiMood.Alert)
            Spacer(Modifier.width(10.dp))
            Text(
                q?.question ?: notification?.title.orEmpty(),
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (questions.size > 1) Text("${index + 1}/${questions.size}", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
        }
        Spacer(Modifier.size(6.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (o in q?.options.orEmpty().take(6)) {
                val on = o.label in picked
                Pill(
                    o.label + if (o.description.isNotEmpty()) "  ·  ${o.description}" else "",
                    Modifier.fillMaxWidth(),
                    if (on) Green else QuestionBlue,
                ) {
                    touchIsland(context)
                    if (q?.multi == true) picked = if (on) picked - o.label else picked + o.label else next(listOf(o.label))
                }
            }
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Terminal", Modifier.weight(1f), Color.White.copy(alpha = 0.6f)) {
                p?.let { if (!CoucouRuntime.sendToLaptop(RemoteProtocol.decline(it.id))) message = "No link to the laptop" else onCollapse() }
            }
            if (q?.multi == true) Pill("Send", Modifier.weight(1f), Green) { if (picked.isNotEmpty()) next(picked) else message = "Pick at least one" }
        }
        message?.let { Text(it, color = Red, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun Pill(label: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
