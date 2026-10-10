/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 *
 * Is this the device Mochi and Coucou's cards belong on? Fed by three things that can each be
 * missing: the laptop's beats over the ssh link, the presence server's answer, and this phone's own
 * screen and touches. Nothing depends on the others being there: with nobody to hear and no server,
 * the answer is "yes, here". Only rendering follows [active]; timeouts, answers and sync do not.
 */

package com.agupta07505.smartisland.coucou.presence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object DeviceActivity {
    private val _active = MutableStateFlow(true)

    /** True while this phone is the one that shows Mochi and Coucou. Starts true: nobody has said otherwise. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _peerMusic = MutableStateFlow(false)

    /** The laptop is playing something: Mochi dances here too when he is the one on screen. */
    val peerMusic: StateFlow<Boolean> = _peerMusic.asStateFlow()

    private val _peerNews = MutableStateFlow(false)

    /** Something just arrived on the laptop: a moment of surprise for Mochi here. */
    val peerNews: StateFlow<Boolean> = _peerNews.asStateFlow()

    /** How long the server's answer counts: the heartbeat is every 30 s, with one missed. */
    private const val SERVER_TTL_MS = 75_000L
    private const val NEWS_MS = 3_000L

    private val _peerLimit = MutableStateFlow(false)

    /** Claude Code is rate-limited on the laptop: Mochi is tired here as well. */
    val peerLimit: StateFlow<Boolean> = _peerLimit.asStateFlow()

    private data class PeerState(val beat: Beat, val music: Boolean, val news: Boolean, val limit: Boolean, val at: Long)

    @Volatile private var peer: PeerState? = null
    @Volatile private var serverActive: String? = null
    @Volatile private var serverAt = 0L
    @Volatile private var previous: String? = null
    @Volatile private var screenOnTs = 0L
    @Volatile private var lastTouchTs = 0L

    private var receiver: BroadcastReceiver? = null
    private var ticker: Job? = null

    @Synchronized
    fun start(app: Context, scope: CoroutineScope) {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (screenOnTs == 0L && pm.isInteractive) screenOnTs = System.currentTimeMillis()
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_SCREEN_ON) screenOnTs = System.currentTimeMillis()
                    recompute(c)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
                else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    app.registerReceiver(r, filter)
                }
                receiver = r
            }
        }
        if (ticker?.isActive != true) {
            // Expiry runs on a clock, not on a connection closing: an ssh link to a phone that left
            // the wifi can look alive for minutes.
            ticker = scope.launch {
                while (isActive) {
                    recompute(app)
                    delay(2_000)
                }
            }
        }
    }

    /** The overlay calls this when the person touches the island. */
    fun noteInteraction() {
        lastTouchTs = System.currentTimeMillis()
    }

    fun idleSeconds(): Long {
        val ref = maxOf(screenOnTs, lastTouchTs)
        if (ref <= 0L) return 0L
        return maxOf(0L, (System.currentTimeMillis() - ref) / 1000)
    }

    fun screenOn(app: Context): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    /** The presence server answered (or did not) for this phone's heartbeat. */
    fun onServer(app: Context, ok: Boolean, activeDevice: String?) {
        serverActive = if (ok) activeDevice else null
        serverAt = if (ok) System.currentTimeMillis() else 0L
        recompute(app)
    }

    /** The laptop's beat arrived over the link. */
    fun onPeer(app: Context, beat: Beat, music: Boolean, news: Boolean, limit: Boolean = false) {
        peer = PeerState(beat, music, news, limit, System.currentTimeMillis())
        recompute(app)
    }

    /** The link to the laptop ended: it is not there until it says so again. */
    fun onLinkDown(app: Context) {
        peer = null
        recompute(app)
    }

    /** What this phone says about itself right now. */
    fun myBeat(app: Context): Beat {
        val fresh = serverAt > 0 && System.currentTimeMillis() - serverAt <= SERVER_TTL_MS
        return Beat(
            device = Arbiter.PHONE,
            idleS = idleSeconds(),
            screenOn = screenOn(app),
            serverOk = fresh,
            serverActive = if (fresh) serverActive else null,
        )
    }

    @Synchronized
    private fun recompute(app: Context) {
        val now = System.currentTimeMillis()
        val p = peer
        val seen = if (p != null) listOf(Seen(p.beat, now - p.at)) else emptyList()
        val winner = Arbiter.decide(myBeat(app), seen, previous)
        previous = winner
        _active.value = winner == Arbiter.PHONE
        val alive = p != null && now - p.at <= Arbiter.FRESH_MS
        _peerMusic.value = alive && p != null && p.music
        _peerLimit.value = alive && p != null && p.limit
        // News is an edge, not a state: only the beat that carries it counts, not the ones after.
        _peerNews.value = alive && p != null && p.news && now - p.at <= NEWS_MS
    }
}
