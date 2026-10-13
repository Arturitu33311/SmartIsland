/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.assist

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agupta07505.smartisland.coucou.CoucouRuntime
import com.agupta07505.smartisland.coucou.phone.PhoneSettings
import com.agupta07505.smartisland.coucou.ui.MochiEmerald
import com.agupta07505.smartisland.coucou.ui.MochiGlyph
import com.agupta07505.smartisland.coucou.ui.MochiMood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class AssistUi(
    val lines: List<ChatLine> = emptyList(),
    val busy: Boolean = false,
    /** Which brain answered last: "laptop", "este teléfono · nube"… shown as a badge. */
    val badge: String = "",
    val notice: String = "",
)

class AssistModel(app: Application) : AndroidViewModel(app) {
    private val store = ThreadStore.of(app)
    private val personas = PersonaStore(File(app.filesDir, "jinx-persona.json"))
    private val secrets = KeystoreSecrets(app)
    private val deviceId = PhoneSettings.deviceId(app)
    private val laptopApi = LaptopChatApi(app) { PhoneSettings.profile(app, PhoneSettings.LAPTOP) }
    private val repo = ChatRepository(
        store, deviceId, laptopApi,
        listOf(OpenRouterBrain(secrets, { personas.load() })),
        TelegramMirror(secrets),
    )

    private val _ui = MutableStateFlow(AssistUi(lines = store.all()))
    val ui: StateFlow<AssistUi> = _ui.asStateFlow()

    init {
        // Opening the panel: bring the laptop's lines and persona if it is there, then retry what was left.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { refreshPersona(laptopApi, personas) }
            repo.sync()
            repo.retryPending()
            repo.flushMirror()
            _ui.value = _ui.value.copy(lines = store.all())
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, notice = "")
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo.send(t)
            _ui.value = when (r) {
                is SendResult.Answered -> _ui.value.copy(
                    lines = store.all(), busy = false,
                    badge = when (r.via) {
                        Via.Laptop -> "laptop"
                        Via.Cloud -> "este teléfono · nube"
                        Via.Local -> "este teléfono · sin conexión"
                    },
                )
                is SendResult.Pending -> _ui.value.copy(
                    lines = store.all(), busy = false,
                    notice = "Sin respuesta: ${r.reason}. Queda pendiente y se reintenta al abrir.",
                )
            }
        }
    }

    /** Brings the laptop's lines and persona, retries what is pending and repeats unmirrored lines to Telegram. */
    fun syncNow() {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, notice = "")
        viewModelScope.launch(Dispatchers.IO) {
            val persona = runCatching { refreshPersona(laptopApi, personas) }
            val added = repo.sync()
            repo.retryPending()
            repo.flushMirror()
            _ui.value = _ui.value.copy(
                lines = store.all(),
                busy = false,
                notice = if (persona.isSuccess) "Sincronizado ($added mensajes nuevos)." else "La laptop no responde; sigo con lo que tengo.",
            )
        }
    }

    /** One time: the laptop gateway hands this phone the OpenRouter key and Telegram bot (needs ALLOW_PROVISION there). */
    fun provision() {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                provisionFrom(laptopApi, secrets, personas)
                "Teléfono provisionado desde la laptop."
            } catch (e: ChatException) {
                "No se pudo: ${e.message}"
            }
            _ui.value = _ui.value.copy(notice = msg)
        }
    }
}

/** The assistant's panel: the shared conversation, one input, which brain answered, and Claude Code at a glance. */
class JinxAssistActivity : ComponentActivity() {
    private val model: AssistModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0B10), surface = Color(0xFF16161C))) {
                AssistScreen(model)
            }
        }
    }
}

@Composable
private fun AssistScreen(model: AssistModel) {
    val ui by model.ui.collectAsState()
    val agents by CoucouRuntime.agentUi.collectAsState()
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.lines.size) { if (ui.lines.isNotEmpty()) list.animateScrollToItem(ui.lines.lastIndex) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MochiGlyph(size = 32.dp, tint = MochiEmerald, mood = if (ui.busy) MochiMood.Thinking else MochiMood.Listening)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Jinx", color = Color.White, fontSize = 18.sp)
                if (ui.badge.isNotEmpty()) Text(ui.badge, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
            TextButton(onClick = { model.syncNow() }) { Text("Sincronizar") }
            TextButton(onClick = { model.provision() }) { Text("Provisionar (1.ª vez)") }
        }
        if (agents.sessions.isNotEmpty()) {
            Text(
                agents.sessions.joinToString(" · ") { "${it.project}: ${it.state.name.lowercase()}" },
                color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (ui.notice.isNotEmpty()) {
            Text(ui.notice, color = Color(0xFFFFB74D), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 10.dp),
            state = list,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.lines, key = { it.id }) { l -> Bubble(l) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Escríbele a Jinx") },
            enabled = !ui.busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { model.send(draft); draft = "" }),
        )
    }
}

@Composable
private fun Bubble(l: ChatLine) {
    val mine = !l.fromJinx
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .background(if (mine) Color(0xFF1F3B2E) else Color(0xFF16161C), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(l.text, color = Color.White, fontSize = 15.sp)
            if (l.src == "tg") Text("Telegram", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}
