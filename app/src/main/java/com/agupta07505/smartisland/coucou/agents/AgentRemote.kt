/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.agents

import android.content.Context
import com.agupta07505.smartisland.coucou.phone.PhoneSettings
import com.agupta07505.smartisland.coucou.phone.Ssh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue

/** What the island and the settings screen see of the link to the laptop. */
data class AgentUi(
    val status: Status = Status.Off,
    val detail: String = "",
    val sessions: List<SessionInfo> = emptyList(),
    val pending: List<Pending> = emptyList(),
) {
    enum class Status { Off, Connecting, Connected, Reconnecting, LaptopClosed }
}

/**
 * The link to Coucou on the laptop: `ssh laptop` runs the one command the phone's key is allowed
 * (coucou-hook --remote), and what it prints is one JSON line per thing Claude Code does. The
 * connection mends itself: it is watched with pings, and tried again with growing waits.
 */
class AgentRemote(private val context: Context, private val scope: CoroutineScope) {
    private val model = AgentModel()
    private val _ui = MutableStateFlow(AgentUi())
    val ui: StateFlow<AgentUi> = _ui.asStateFlow()

    private var job: Job? = null
    private val outbox = LinkedBlockingQueue<String>()

    @Volatile private var lastHeard = 0L

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        model.pending.clear()
        model.sessions.clear()
        _ui.value = AgentUi()
    }

    /** Sends an answer to a request the phone has on screen. Returns false if there is no link to send it on. */
    fun send(line: String): Boolean {
        if (_ui.value.status != AgentUi.Status.Connected) return false
        outbox.offer(line)
        return true
    }

    private fun publish(status: AgentUi.Status, detail: String = "") {
        val now = System.currentTimeMillis()
        _ui.value = AgentUi(status, detail, model.view(now), model.pending.values.sortedBy { it.receivedAt })
    }

    private suspend fun loop() {
        var wait = 2_000L
        while (currentCoroutineContext().isActive) {
            val profile = PhoneSettings.profile(context, PhoneSettings.LAPTOP)
            if (!profile.paired) {
                publish(AgentUi.Status.Off, "Pair the laptop in Coucou's phone settings")
                delay(5_000)
                continue
            }
            publish(if (_ui.value.status == AgentUi.Status.Connected) AgentUi.Status.Reconnecting else AgentUi.Status.Connecting)
            var ended = "connection closed"
            try {
                Ssh.open(context, profile).use { conn ->
                    conn.stream("coucou").use { stream ->
                        wait = 2_000L
                        lastHeard = System.currentTimeMillis()
                        ended = pump(stream)
                    }
                }
            } catch (e: Exception) {
                ended = e.message ?: "cannot reach the laptop"
            }
            // Nothing that was waiting can still be answered through a dead link.
            model.pending.clear()
            publish(if (ended == "laptop-closed") AgentUi.Status.LaptopClosed else AgentUi.Status.Reconnecting, if (ended == "laptop-closed") "Coucou is closed on the laptop" else ended)
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    /** Reads lines until the link ends or goes quiet; writes what the phone has to say. Returns why it ended. */
    private suspend fun pump(stream: Ssh.Stream): String {
        val reader = BufferedReader(InputStreamReader(stream.input, Charsets.UTF_8))
        // The end of the link is told by the thread ending (a queue cannot hold a null).
        val lines = LinkedBlockingQueue<String>()
        val readerThread = Thread {
            try {
                while (true) lines.put(reader.readLine() ?: break)
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }
        var lastPing = 0L
        try {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                // The laptop answers a ping at once: a long silence means the link is dead (a phone that
                // changed network does not always find out by itself).
                if (now - lastPing > 20_000) {
                    stream.output.write((RemoteProtocol.ping() + "\n").toByteArray()); stream.output.flush()
                    lastPing = now
                }
                if (now - lastHeard > 50_000) return "no answer from the laptop"
                while (true) {
                    val out = outbox.poll() ?: break
                    stream.output.write((out + "\n").toByteArray()); stream.output.flush()
                }
                val line = lines.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (line == null && !readerThread.isAlive && lines.isEmpty()) return "connection closed"
                if (line == null) {
                    publishIfChanged()
                    continue
                }
                lastHeard = System.currentTimeMillis()
                when (val msg = RemoteProtocol.parse(line, lastHeard)) {
                    null -> Unit
                    RemoteMsg.Offline -> return "laptop-closed"
                    RemoteMsg.Disabled -> return "The phone link is off on the laptop (Settings → Phone)"
                    else -> {
                        model.apply(msg, lastHeard)
                        publish(AgentUi.Status.Connected)
                    }
                }
            }
        } catch (e: Exception) {
            return e.message ?: "connection lost"
        } finally {
            readerThread.interrupt()
        }
        return "stopped"
    }

    /** A working session going quiet changes how it looks without any news: look again now and then. */
    private fun publishIfChanged() {
        val view = model.view(System.currentTimeMillis())
        if (view != _ui.value.sessions) publish(AgentUi.Status.Connected)
    }
}

