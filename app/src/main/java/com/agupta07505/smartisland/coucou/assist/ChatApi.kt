/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.assist

import android.content.Context
import java.io.Closeable
import com.agupta07505.smartisland.coucou.phone.Ssh
import com.agupta07505.smartisland.coucou.phone.SshException
import com.agupta07505.smartisland.coucou.phone.SshProfile

/** What the laptop's `jinx-chat` can do for the phone. An interface, so the repository is tried without a network. */
interface ChatApi {
    fun send(text: String, cid: String, src: String): ChatEvent.Done
    fun merge(lines: List<ChatLine>, have: List<String>): ChatEvent.Merged
    fun core(): ChatEvent.Core
    fun provision(): ChatEvent.Secrets
}

/**
 * The real thing: one ssh command (`jinx-chat`) per request, with the phone's own key. The connection is
 * kept between requests (logging in costs more than the request itself) and replaced when it has gone stale.
 */
class LaptopChatApi(
    private val context: Context,
    private val profile: () -> SshProfile,
) : ChatApi, Closeable {
    private var conn: Ssh.Connection? = null

    @Synchronized
    private fun ask(request: String, timeoutSec: Long): String {
        var failure: String? = null
        // Twice: a kept connection may have died while the phone slept; the second try opens a fresh one.
        // Resending is safe, the gateway answers the same cid once.
        repeat(2) {
            val c = try {
                conn?.takeIf { it.connected } ?: Ssh.open(context, profile(), connectTimeoutMs = PROBE_MS).also { conn = it }
            } catch (e: SshException) {
                throw ChatException(e.message ?: "la laptop no responde")
            }
            try {
                return c.exec("jinx-chat", request + "\n", timeoutSec)
            } catch (e: Exception) {
                failure = e.message
                runCatching { c.close() }
                conn = null
            }
        }
        throw ChatException(failure ?: "la laptop no responde")
    }

    @Synchronized
    override fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    override fun send(text: String, cid: String, src: String) =
        ChatProtocol.expectDone(ask(ChatProtocol.send(text, cid, src), SEND_TIMEOUT_SEC))

    override fun merge(lines: List<ChatLine>, have: List<String>) =
        ChatProtocol.expect<ChatEvent.Merged>(ask(ChatProtocol.merge(lines, have), 30))

    override fun core() = ChatProtocol.expect<ChatEvent.Core>(ask(ChatProtocol.core(), 20))
    override fun provision() = ChatProtocol.expect<ChatEvent.Secrets>(ask(ChatProtocol.provision(), 20))

    private companion object {
        /** A laptop that is not there is known in 3 s, not in `Ssh`'s default 8. */
        const val PROBE_MS = 3_000
        const val SEND_TIMEOUT_SEC = 90L
    }
}
