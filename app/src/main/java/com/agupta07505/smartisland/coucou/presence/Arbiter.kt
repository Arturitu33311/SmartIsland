/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 *
 * Which device shows Mochi and Coucou's cards. Pure: same inputs, same answer, on the phone and on
 * the laptop (windows/src-tauri/src/presence.rs is the twin, and both read presence-vectors.json).
 *
 * The rules, in order:
 *  1. The server decides only when everyone we can see reaches it and agrees on the answer, and the
 *     answer is one of our devices. (The server only knows who sends it a heartbeat: if one of us
 *     does not, its "active" would hide the other for no reason.)
 *  2. Otherwise the devices that can hear each other decide among themselves: the one with a screen
 *     on that was touched most recently. A device that was already the active one keeps it unless
 *     another leads by a clear margin, so a stray touch does not flip the island back and forth.
 *  3. A device that hears nobody and is not told by the server shows everything.
 */

package com.agupta07505.smartisland.coucou.presence

/** What one device says about itself. [idleS] is seconds since its last input. */
data class Beat(
    val device: String,
    val idleS: Long,
    val screenOn: Boolean,
    val serverOk: Boolean,
    val serverActive: String?,
)

/** A peer's last beat and how long ago it arrived (its idle has kept running since). */
data class Seen(val beat: Beat, val ageMs: Long)

object Arbiter {
    const val PHONE = "s21"
    const val LAPTOP = "laptop"
    val KNOWN = setOf(PHONE, LAPTOP)

    /** A peer silent for longer than this is not there. */
    const val FRESH_MS = 15_000L

    /** How much more recently another device must have been touched to take the island from the one that has it. */
    const val HYSTERESIS_S = 8L

    /** Who should show Mochi and Coucou: one of the ids in play. */
    fun decide(me: Beat, peers: List<Seen>, previous: String?): String {
        val fresh = peers.filter { it.ageMs <= FRESH_MS }

        // 1. The server, only when all of us can see it and agree.
        val named = me.serverActive
        if (me.serverOk && named != null && named in KNOWN &&
            fresh.all { it.beat.serverOk && it.beat.serverActive == named }
        ) return named

        // 2. The ones that hear each other.
        data class Cand(val id: String, val idle: Long, val screen: Boolean)
        val group = listOf(Cand(me.device, me.idleS, me.screenOn)) +
            fresh.map { Cand(it.beat.device, it.beat.idleS + it.ageMs / 1000, it.beat.screenOn) }
        val awake = group.filter { it.screen }.ifEmpty { group }
        val best = awake.minWith(compareBy<Cand> { it.idle }.thenBy { it.id })
        val held = awake.firstOrNull { it.id == previous }
        return if (held != null && held.idle <= best.idle + HYSTERESIS_S) held.id else best.id
    }
}
