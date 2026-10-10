/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.agents

import com.agupta07505.smartisland.coucou.presence.Arbiter
import com.agupta07505.smartisland.coucou.presence.Beat
import org.json.JSONArray
import org.json.JSONObject

/** One question of an AskUserQuestion. */
data class QuestionItem(val question: String, val header: String, val multi: Boolean, val options: List<Option>) {
    data class Option(val label: String, val description: String)
}

/** A request that waits for a human: a permission, or a question. */
data class Pending(
    val id: String,
    val isQuestion: Boolean,
    val session: String,
    val project: String,
    val tool: String,
    val detail: String,
    val questions: List<QuestionItem>,
    val timeoutS: Int,
    val receivedAt: Long,
)

enum class SessionState { Working, Idle, NeedsYou }

data class SessionInfo(
    val session: String,
    val project: String,
    val state: SessionState,
    val tool: String,
    val detail: String,
    val updatedAt: Long,
)

/** What the laptop's Coucou says (see remote.rs). */
sealed class RemoteMsg {
    data class Hello(val pending: List<Pending>) : RemoteMsg()
    data class Event(val event: String, val session: String, val project: String, val tool: String, val detail: String, val message: String) : RemoteMsg()
    data class Request(val pending: Pending) : RemoteMsg()
    data class Resolved(val id: String, val decision: String) : RemoteMsg()
    data class Ack(val ok: Boolean, val id: String?, val why: String?) : RemoteMsg()
    /** The laptop's own list of tasks, merged with the phone's (see remote.rs `tasks_sync`): a JSON array. */
    data class Tasks(val json: String) : RemoteMsg()
    /** The laptop's own beat: how recently it was used, whether it reaches the server, what it hears and sees. */
    data class Peer(val beat: Beat, val music: Boolean, val news: Boolean) : RemoteMsg()
    data object Pong : RemoteMsg()
    data object Disabled : RemoteMsg()
    data object Offline : RemoteMsg()
}

object RemoteProtocol {
    /** The phone's whole list, tombstones included, for the laptop to merge with its own. */
    fun tasksSync(tasksJson: String): String =
        JSONObject().put("op", "tasks_sync").put("tasks", JSONArray(tasksJson)).toString()

    fun parse(line: String, now: Long): RemoteMsg? {
        val o = runCatching { JSONObject(line) }.getOrNull() ?: return null
        fun s(k: String) = o.optString(k, "").takeIf { it != "null" } ?: ""
        return when (o.optString("t")) {
            "hello" -> {
                val arr = o.optJSONArray("pending")
                RemoteMsg.Hello((0 until (arr?.length() ?: 0)).mapNotNull { pendingOf(arr!!.getJSONObject(it), now) })
            }
            "event" -> RemoteMsg.Event(s("event"), s("session"), s("project"), s("tool"), s("detail"), s("message"))
            "approval", "question" -> pendingOf(o, now)?.let { RemoteMsg.Request(it) }
            "resolved" -> RemoteMsg.Resolved(s("request_id"), s("decision"))
            "ack" -> RemoteMsg.Ack(o.optBoolean("ok"), o.optString("request_id").takeIf { it.isNotEmpty() }, o.optString("why").takeIf { it.isNotEmpty() })
            "tasks" -> o.optJSONArray("tasks")?.let { RemoteMsg.Tasks(it.toString()) }
            "peer" -> {
                // Only the laptop speaks on this link, and only in terms of the two ids we know.
                val device = o.optString("device")
                if (device != Arbiter.LAPTOP) null
                else RemoteMsg.Peer(
                    Beat(
                        device = device,
                        idleS = o.optLong("idle", 0L).coerceIn(0L, MAX_IDLE_S),
                        screenOn = o.optBoolean("screen_on", true),
                        serverOk = o.optBoolean("server_ok", false),
                        serverActive = o.optString("server_active").takeIf { it in Arbiter.KNOWN },
                    ),
                    music = o.optBoolean("music", false),
                    news = o.optBoolean("news", false),
                )
            }
            "pong" -> RemoteMsg.Pong
            "disabled" -> RemoteMsg.Disabled
            "offline" -> RemoteMsg.Offline
            else -> null
        }
    }

    private fun pendingOf(o: JSONObject, now: Long): Pending? {
        val id = o.optString("request_id").takeIf { it.isNotEmpty() } ?: return null
        val isQuestion = o.optString("t") == "question"
        val qs = o.optJSONArray("questions")
        val questions = (0 until (qs?.length() ?: 0)).map { i ->
            val q = qs!!.getJSONObject(i)
            val os = q.optJSONArray("options")
            QuestionItem(
                q.optString("question"), q.optString("header"), q.optBoolean("multi"),
                (0 until (os?.length() ?: 0)).map { j -> os!!.getJSONObject(j).let { QuestionItem.Option(it.optString("label"), it.optString("description")) } },
            )
        }
        return Pending(
            id, isQuestion, o.optString("session"), o.optString("project"), o.optString("tool"), o.optString("detail"),
            questions, o.optInt("timeout_s", 108), now,
        )
    }

    fun answer(id: String, decision: String) = JSONObject().put("op", "answer").put("request_id", id).put("decision", decision).toString()
    fun decline(id: String) = JSONObject().put("op", "decline").put("request_id", id).toString()
    fun ping() = JSONObject().put("op", "ping").toString()

    /** A week: past that an idle value means nothing, and an absurd one must not outlast a real one. */
    const val MAX_IDLE_S = 7L * 24 * 3600

    /** This phone's beat for the laptop: state only, nothing it could act on. */
    fun peer(beat: Beat, music: Boolean, news: Boolean): String =
        JSONObject()
            .put("op", "peer")
            .put("device", beat.device)
            .put("idle", beat.idleS.coerceIn(0L, MAX_IDLE_S))
            .put("screen_on", beat.screenOn)
            .put("server_ok", beat.serverOk)
            .put("server_active", beat.serverActive ?: JSONObject.NULL)
            .put("music", music)
            .put("news", news)
            .toString()

    /** `answers` maps each question's text to the chosen label, or to a list of labels for a multi-select one. */
    fun answerQuestion(id: String, answers: Map<String, List<String>>, multi: Set<String>): String {
        val a = JSONObject()
        for ((q, labels) in answers) {
            if (q in multi) a.put(q, org.json.JSONArray(labels)) else a.put(q, labels.firstOrNull() ?: "")
        }
        return JSONObject().put("op", "answer_question").put("request_id", id).put("answers", a).toString()
    }
}

/** What the phone knows of Claude Code, built from what the laptop says. */
class AgentModel {
    val sessions = LinkedHashMap<String, SessionInfo>()
    val pending = LinkedHashMap<String, Pending>()

    /** When a tool of Claude Code last failed (0: never): Mochi winces for a moment, as on the laptop. */
    @Volatile var lastFailureAt = 0L

    /** How long without news before a session that was working is shown as idle. */
    companion object {
        const val STALE_MS = 90_000L
        const val FORGET_MS = 30 * 60_000L
    }

    fun apply(msg: RemoteMsg, now: Long) {
        when (msg) {
            is RemoteMsg.Hello -> {
                pending.clear()
                msg.pending.forEach { pending[it.id] = it }
            }
            is RemoteMsg.Request -> pending[msg.pending.id] = msg.pending
            is RemoteMsg.Resolved -> pending.remove(msg.id)
            is RemoteMsg.Event -> event(msg, now)
            else -> Unit
        }
        // A request still waiting means that session needs the human.
        for (p in pending.values) {
            val s = sessions[p.session]
            sessions[p.session] = (s ?: SessionInfo(p.session, p.project, SessionState.NeedsYou, p.tool, p.detail, now))
                .copy(state = SessionState.NeedsYou, tool = p.tool, detail = p.detail, updatedAt = now)
        }
    }

    private fun event(e: RemoteMsg.Event, now: Long) {
        if (e.session.isEmpty()) return
        if (e.event == "PostToolUseFailure") lastFailureAt = now
        val old = sessions[e.session]
        val project = e.project.ifEmpty { old?.project ?: "" }
        when (e.event) {
            "SessionEnd" -> sessions.remove(e.session)
            "Stop", "SubagentStop", "SessionStart" ->
                sessions[e.session] = SessionInfo(e.session, project, SessionState.Idle, old?.tool ?: "", old?.detail ?: "", now)
            "Notification" ->
                sessions[e.session] = SessionInfo(e.session, project, SessionState.NeedsYou, "", e.message.ifEmpty { "needs you" }, now)
            "PreToolUse", "PostToolUse", "PostToolUseFailure", "UserPromptSubmit", "SubagentStart" ->
                sessions[e.session] = SessionInfo(
                    e.session, project, SessionState.Working,
                    e.tool.ifEmpty { old?.tool ?: "" }, if (e.tool.isNotEmpty()) e.detail else (old?.detail ?: ""), now,
                )
            else -> sessions[e.session] = (old ?: SessionInfo(e.session, project, SessionState.Idle, "", "", now)).copy(updatedAt = now)
        }
    }

    /** Sessions as they should look now: a "working" one that has been quiet for long is idle; old ones go. */
    fun view(now: Long): List<SessionInfo> {
        sessions.entries.removeAll { now - it.value.updatedAt > FORGET_MS && it.value.state != SessionState.NeedsYou }
        return sessions.values
            .map { if (it.state == SessionState.Working && now - it.updatedAt > STALE_MS) it.copy(state = SessionState.Idle) else it }
            .sortedWith(compareBy({ it.state.ordinal.let { o -> if (it.state == SessionState.NeedsYou) -1 else o } }, { -it.updatedAt }))
    }

    /** The oldest request first: it is the one whose time runs out first. */
    fun nextPending(): Pending? = pending.values.minByOrNull { it.receivedAt }
}
