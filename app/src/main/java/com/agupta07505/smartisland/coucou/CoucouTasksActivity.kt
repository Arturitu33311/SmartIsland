/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agupta07505.smartisland.coucou.phone.PhoneSetupActivity
import com.agupta07505.smartisland.coucou.phone.PhoneSettings.jinxSync
import com.agupta07505.smartisland.coucou.ui.MochiGlyph
import com.agupta07505.smartisland.coucou.ui.MochiMood
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The task list: add one in a sentence (the time is read out of it), tick it, delete it. */
class CoucouTasksActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0B10), surface = Color(0xFF16161C))) {
                TasksScreen()
            }
        }
    }
}

@Composable
private fun TasksScreen() {
    val context = LocalContext.current
    var tasks by remember { mutableStateOf(CoucouTasks.list(context)) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val jinxOn = context.jinxSync
    val outcome by CoucouRuntime.jinx.collectAsState()
    // The sync may have changed the list (she closed one, a task was handed over): show it as it is.
    LaunchedEffect(outcome) { tasks = CoucouTasks.list(context) }
    LaunchedEffect(Unit) {
        CoucouRuntime.apply(context)
        CoucouRuntime.kick()
    }

    fun add() {
        if (input.isBlank()) return
        runCatching { CoucouTasks.add(context, input) }
            .onSuccess { tasks = it; input = ""; error = null }
            .onFailure { error = it.message }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(size = 34.dp, mood = MochiMood.Idle)
            Spacer(Modifier.width(12.dp))
            Text("Tasks", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(android.content.Intent(context, PhoneSetupActivity::class.java)) }) {
                Icon(Icons.Rounded.Settings, "Phone link", tint = Color.White.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.padding(6.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("llamar a mamá a las 17:30 · stretch in 20 min · mañana 9:00") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
        )
        error?.let { Text(it, color = Color(0xFFF5A524), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        Spacer(Modifier.padding(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val open = tasks.filter { !it.done }.sortedWith(compareBy({ it.remindAt ?: Long.MAX_VALUE }, { it.created }))
            val done = tasks.filter { it.done }.sortedByDescending { it.completedAt ?: 0 }
            items(open + done, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.done, onCheckedChange = { tasks = CoucouTasks.setDone(context, t.id, it) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title,
                            color = Color.White.copy(alpha = if (t.done) 0.4f else 1f),
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            fontSize = 15.sp,
                        )
                        t.remindAt?.takeIf { !t.done }?.let {
                            val late = it < System.currentTimeMillis() / 1000
                            Text(
                                "⏰ " + SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(it * 1000)),
                                color = if (late) Color(0xFFF5A524) else Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (jinxOn && !t.done) {
                        TextButton(onClick = {
                            tasks = TaskStore.of(context).update { TaskLogic.setJinx(it, t.id, !t.jinx) }
                            CoucouRuntime.kick()
                        }) {
                            Text(
                                if (t.jinx) (if (t.jinxId != null) "Jinx ✓" else "Jinx …") else "Jinx",
                                color = if (t.jinx) Color(0xFF39FF14) else Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                            )
                        }
                    }
                    IconButton(onClick = { tasks = CoucouTasks.delete(context, t.id) }) {
                        Icon(Icons.Rounded.Close, "Delete", tint = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
            if (jinxOn && outcome.fromJinx.isNotEmpty()) {
                item {
                    Text("From Jinx", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                }
                items(outcome.fromJinx, key = { "jinx-" + it.id }) { r ->
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.titulo, color = Color.White, fontSize = 15.sp)
                            r.due?.let { Text(it, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) }
                        }
                        // The school's assignments are mirrored by Jinx's own job: closing them here would be undone.
                        if (r.source == "teams") {
                            Text("Teams", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
                        } else {
                            IconButton(onClick = { CoucouRuntime.resolveJinx(context, r.id, "hecho") }) {
                                Icon(Icons.Rounded.Check, "Done", tint = Color(0xFF4ADE80))
                            }
                            IconButton(onClick = { CoucouRuntime.resolveJinx(context, r.id, "descartado") }) {
                                Icon(Icons.Rounded.Close, "Dismiss", tint = Color.White.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
            outcome.error?.takeIf { jinxOn }?.let { e -> item { Text("Jinx: $e", color = Color(0xFFF5A524), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) } }
        }
    }
}
