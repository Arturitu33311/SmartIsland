/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou.phone

import android.content.Context

/** The two hosts the phone talks to, and the two switches. Plain preferences: no secret is kept here. */
object PhoneSettings {
    const val SERVER = "server"
    const val LAPTOP = "laptop"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("coucou_phone", Context.MODE_PRIVATE)

    fun profile(c: Context, name: String) = SshProfile(
        host = prefs(c).getString("$name.host", "") ?: "",
        port = prefs(c).getInt("$name.port", 22),
        user = prefs(c).getString("$name.user", "") ?: "",
        hostKey = prefs(c).getString("$name.hostKey", "") ?: "",
    )

    fun save(c: Context, name: String, p: SshProfile) {
        prefs(c).edit()
            .putString("$name.host", p.host.trim())
            .putInt("$name.port", p.port)
            .putString("$name.user", p.user.trim())
            .putString("$name.hostKey", p.hostKey)
            .apply()
    }

    /** Anything about the host changed: what was trusted is no longer known to be this host. */
    fun changedHost(old: SshProfile, new: SshProfile) = old.host != new.host.trim() || old.port != new.port

    /** Share the tasks with Jinx's pendientes (over the server profile). */
    var Context.jinxSync: Boolean
        get() = prefs(this).getBoolean("jinxSync", false)
        set(v) = prefs(this).edit().putBoolean("jinxSync", v).apply()

    /** Keep Mochi in the island, with the tasks (on by default). */
    var Context.showDesk: Boolean
        get() = prefs(this).getBoolean("showDesk", true)
        set(v) = prefs(this).edit().putBoolean("showDesk", v).apply()

    /** Follow Claude Code on the laptop and answer its requests (over the laptop profile). */
    var Context.followClaude: Boolean
        get() = prefs(this).getBoolean("followClaude", false)
        set(v) = prefs(this).edit().putBoolean("followClaude", v).apply()
}
