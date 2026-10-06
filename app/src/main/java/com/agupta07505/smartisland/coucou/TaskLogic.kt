/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 * The logic is a port of Coucou's tasks.rs (MIT), for the same behaviour on the phone and the PC.
 */

package com.agupta07505.smartisland.coucou

/** One to-do, with an optional reminder; [jinx] means it is also one of Jinx's pendientes. */
data class CoucouTask(
    val id: String,
    val title: String,
    val done: Boolean = false,
    /** Unix seconds the reminder is due (null: no reminder). */
    val remindAt: Long? = null,
    /** The reminder was announced: it is not announced again after a restart. */
    val notified: Boolean = false,
    val created: Long,
    val completedAt: Long? = null,
    /** Shared with Jinx: it is (or will be) one of her pendientes too. */
    val jinx: Boolean = false,
    /** Her id for it, once she has it. */
    val jinxId: String? = null,
    /** Who made it when it was not made here (her source: "hook", "teams", "portal"…): the list marks it. */
    val origin: String? = null,
)

/** One row of Jinx's pendientes, as far as the phone needs it. */
data class JinxRow(
    val id: String,
    val titulo: String,
    val due: String? = null,
    val categoria: String? = null,
    val source: String? = null,
    val status: String = "pendiente",
) {
    val open: Boolean get() = status == "pendiente"
}

/** What a sync has to do on her side once the local list is in step. */
data class SyncPlan(
    /** Local tasks (ids) to hand to her. */
    val toAdd: List<String> = emptyList(),
    /** (her id, "hecho" | "descartado") to apply there. */
    val toResolve: List<Pair<String, String>> = emptyList(),
    /** The local list changed and must be saved. */
    val changed: Boolean = false,
)

object TaskLogic {
    /** A to-do list, not a project manager. */
    const val MAX_TASKS = 500
    const val MAX_TITLE = 200

    fun add(list: List<CoucouTask>, title: String, remindAt: Long?, now: Long, id: String, jinx: Boolean = false): List<CoucouTask> {
        val t = title.trim().take(MAX_TITLE)
        require(t.isNotEmpty()) { "A task needs a title" }
        require(list.size < MAX_TASKS) { "Too many tasks: finish or clear some first" }
        return list + CoucouTask(id = id, title = t, remindAt = remindAt, created = now, jinx = jinx)
    }

    private fun change(list: List<CoucouTask>, id: String, f: (CoucouTask) -> CoucouTask): List<CoucouTask> {
        require(list.any { it.id == id }) { "That task is gone" }
        return list.map { if (it.id == id) f(it) else it }
    }

    fun setDone(list: List<CoucouTask>, id: String, done: Boolean, now: Long) =
        change(list, id) { it.copy(done = done, completedAt = if (done) now else null) }

    /** Moves the reminder to [until] and makes it announce again (and reopens the task). */
    fun snooze(list: List<CoucouTask>, id: String, until: Long) =
        change(list, id) { it.copy(remindAt = until, notified = false, done = false, completedAt = null) }

    fun markNotified(list: List<CoucouTask>, id: String) = change(list, id) { it.copy(notified = true) }

    fun setJinx(list: List<CoucouTask>, id: String, on: Boolean) = change(list, id) { it.copy(jinx = on) }

    fun delete(list: List<CoucouTask>, id: String): List<CoucouTask> {
        require(list.any { it.id == id }) { "That task is gone" }
        return list.filter { it.id != id }
    }

    fun clearDone(list: List<CoucouTask>) = list.filter { !it.done }

    /** The next reminder still to announce (the earliest). */
    fun nextReminder(list: List<CoucouTask>): CoucouTask? =
        list.filter { !it.done && !it.notified && it.remindAt != null }.minByOrNull { it.remindAt!! }

    /**
     * Brings the local tasks in step with Jinx's list. She is the other half of a shared task:
     * finished there, it is finished here; finished here, it is closed there; no longer shared, it is
     * dismissed there. What cannot be done now (she is unreachable) is simply asked again at the next
     * sync, because the difference is still there.
     */
    fun reconcile(list: List<CoucouTask>, jinx: List<JinxRow>, now: Long): Pair<List<CoucouTask>, SyncPlan> {
        val toAdd = mutableListOf<String>()
        val toResolve = mutableListOf<Pair<String, String>>()
        var changed = false
        val out = list.map { t ->
            val jid = t.jinxId
            if (jid == null) {
                if (t.jinx && !t.done) toAdd += t.id
                return@map t
            }
            val row = jinx.firstOrNull { it.id == jid }
            when {
                // Not in her list any more: the link is dead.
                row == null -> { changed = true; t.copy(jinxId = null) }
                !t.jinx -> {
                    if (row.open) toResolve += jid to "descartado"
                    changed = true
                    t.copy(jinxId = null)
                }
                !row.open && !t.done -> { changed = true; t.copy(done = true, completedAt = now) }
                row.open && t.done -> { toResolve += jid to "hecho"; t }
                else -> t
            }
        }
        return out to SyncPlan(toAdd, toResolve, changed)
    }

    /** Her open pendientes that are not one of our shared tasks. */
    fun unlinkedOpen(list: List<CoucouTask>, jinx: List<JinxRow>): List<JinxRow> =
        jinx.filter { r -> r.open && list.none { it.jinxId == r.id } }

    /** The day number (from 1970-01-01) of a calendar date; the inverse of [civilDate]. */
    fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yoe = Math.floorMod(y, 400L)
        val mp = if (month > 2) month - 3 else month + 9
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private val DAY = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    /** When a pendiente with only a day gets its reminder: 09:00 that day, in this phone's time zone. */
    fun dueReminder(due: String, offset: (Long) -> Long): Long? {
        val m = DAY.matchEntire(due) ?: return null
        val (y, mo, d) = m.destructured
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31) return null
        val nine = daysFromCivil(y.toLong(), mo.toLong(), d.toLong()) * 86_400L + 9 * 3600
        return nine - offset(nine)
    }

    /**
     * Her open pendientes that are not any task of ours become tasks of ours: the same kind, with the same
     * actions, marked with who made them. One with a day gets a reminder at 09:00 that day, unless that time
     * has passed (it then shows as late, without ringing for something old).
     */
    fun importUnlinked(list: List<CoucouTask>, jinx: List<JinxRow>, now: Long, offset: (Long) -> Long): Pair<List<CoucouTask>, Int> {
        var out = list
        var taken = 0
        for (r in jinx.filter { it.open }) {
            if (out.size >= MAX_TASKS || out.any { it.jinxId == r.id }) continue
            val title = r.titulo.trim().take(MAX_TITLE)
            if (title.isEmpty()) continue
            val remindAt = r.due?.let { dueReminder(it, offset) }
            out = out + CoucouTask(
                id = "j-${r.id}", title = title, remindAt = remindAt,
                // Only a reminder still to come may ring.
                notified = remindAt != null && remindAt <= now,
                created = now, jinx = true, jinxId = r.id,
                origin = r.source?.takeIf { it != "coucou" },
            )
            taken++
        }
        return out to taken
    }

    /** "2026-10-06" for a day number counted from 1970-01-01 (proleptic Gregorian). */
    fun civilDate(days: Long): String {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = Math.floorMod(z, 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val year = if (m <= 2) y + 1 else y
        return "%04d-%02d-%02d".format(year, m, d)
    }
}
