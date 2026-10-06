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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
            Text("Tasks", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
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
                    IconButton(onClick = { tasks = CoucouTasks.delete(context, t.id) }) {
                        Icon(Icons.Rounded.Close, "Delete", tint = Color.White.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
