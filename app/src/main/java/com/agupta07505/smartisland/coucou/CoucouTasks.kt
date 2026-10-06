/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agupta07505.smartisland.di.SmartIslandRepositories
import com.agupta07505.smartisland.model.IslandMode
import com.agupta07505.smartisland.model.IslandNotification
import java.util.TimeZone
import java.util.UUID

/** What the screens and the island's buttons do with the tasks. Every change re-arms the alarm. */
object CoucouTasks {
    private const val KEY_PREFIX = "coucou_reminder_"

    fun islandKey(taskId: String) = KEY_PREFIX + taskId
    fun taskIdOf(islandKey: String) = islandKey.removePrefix(KEY_PREFIX)

    private fun nowSec() = System.currentTimeMillis() / 1000

    /** Something changed that Jinx should hear of (a no-op if the sync is off). */
    private fun changed(context: Context) {
        CoucouRuntime.apply(context)
        CoucouRuntime.kick()
    }
    private fun offset(t: Long) = TimeZone.getDefault().getOffset(t * 1000) / 1000L

    fun list(context: Context) = TaskStore.of(context).load()

    /** Adds a task; the sentence may carry its own time ("llamar a mamá a las 17:30"). */
    fun add(context: Context, text: String, jinx: Boolean = false): List<CoucouTask> {
        val parsed = TaskParser.parseWhen(text, nowSec(), ::offset)
        val list = TaskStore.of(context).update {
            TaskLogic.add(it, parsed.title, parsed.whenSec, nowSec(), UUID.randomUUID().toString(), jinx)
        }
        ReminderScheduler.rearm(context)
        changed(context)
        return list
    }

    fun setDone(context: Context, id: String, done: Boolean): List<CoucouTask> {
        val list = TaskStore.of(context).update { TaskLogic.setDone(it, id, done, nowSec()) }
        withdraw(context, id)
        ReminderScheduler.rearm(context)
        changed(context)
        return list
    }

    fun snooze(context: Context, id: String, seconds: Long): List<CoucouTask> {
        val list = TaskStore.of(context).update { TaskLogic.snooze(it, id, nowSec() + seconds) }
        withdraw(context, id)
        ReminderScheduler.rearm(context)
        return list
    }

    fun delete(context: Context, id: String): List<CoucouTask> {
        // Her copy, if she has one, is dismissed at the next sync (even if she cannot be reached now).
        TaskStore.of(context).load().firstOrNull { it.id == id }?.jinxId?.let { DismissQueue.of(context).add(it) }
        val list = TaskStore.of(context).update { TaskLogic.delete(it, id) }
        withdraw(context, id)
        ReminderScheduler.rearm(context)
        changed(context)
        return list
    }

    /** Takes the reminder off the island (the task stays in the list). */
    fun withdraw(context: Context, id: String) {
        SmartIslandRepositories.notificationRepository(context).removeNotification(islandKey(id))
    }
}

/** One alarm for the next reminder: exact, and it wakes the phone out of Doze. */
object ReminderScheduler {
    private const val ACTION = "com.agupta07505.smartisland.coucou.REMINDER"

    private fun pending(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun rearm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val next = TaskLogic.nextReminder(TaskStore.of(context).load())
        val pi = pending(context)
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(System.currentTimeMillis() + 300, next.remindAt!! * 1000)
        // An alarm-clock alarm needs no special permission and is exempt from Doze; the little
        // alarm icon it puts in the status bar is the price.
        val show = PendingIntent.getActivity(
            context, 1, Intent(context, CoucouTasksActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    /** The alarm went off (or the phone restarted, or the app was updated): announce what is due. */
    fun fire(context: Context) {
        val store = TaskStore.of(context)
        val now = System.currentTimeMillis() / 1000
        val due = store.load().filter { !it.done && !it.notified && it.remindAt != null && it.remindAt <= now }
        val repo = SmartIslandRepositories.notificationRepository(context)
        for (t in due) {
            store.update { TaskLogic.markNotified(it, t.id) }
            repo.postNotification(
                IslandNotification(
                    key = CoucouTasks.islandKey(t.id),
                    packageName = context.packageName,
                    appName = "Coucou",
                    title = t.title,
                    text = "Reminder",
                    timeMillis = System.currentTimeMillis(),
                    mode = IslandMode.Reminder,
                    contentIntent = PendingIntent.getActivity(
                        context, 2, Intent(context, CoucouTasksActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ),
                autoExpand = true,
            )
        }
        rearm(context)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.fire(context)
    }
}
