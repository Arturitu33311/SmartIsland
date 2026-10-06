/*
 * Coucou for Android — part of this SmartIsland fork (GNU GPL v3).
 */

package com.agupta07505.smartisland.coucou

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The tasks, kept in one small JSON file in the app's private folder and written through a
 * temporary file, so a crash never leaves half of it. One change at a time.
 */
class TaskStore(private val file: File) {

    @Synchronized
    fun load(): List<CoucouTask> {
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    /** Reads the list, lets [change] produce the new one, saves it and returns it. */
    @Synchronized
    fun update(change: (List<CoucouTask>) -> List<CoucouTask>): List<CoucouTask> {
        val next = change(load())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(next))
        check(tmp.renameTo(file)) { "Could not save the tasks" }
        return next
    }

    companion object {
        fun of(context: Context) = TaskStore(File(context.applicationContext.filesDir, "coucou-tasks.json"))

        fun encode(list: List<CoucouTask>): String = JSONArray().also { arr ->
            for (t in list) arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("done", t.done)
                    .put("remindAt", t.remindAt ?: JSONObject.NULL)
                    .put("notified", t.notified)
                    .put("created", t.created)
                    .put("completedAt", t.completedAt ?: JSONObject.NULL)
                    .put("jinx", t.jinx)
                    .put("jinxId", t.jinxId ?: JSONObject.NULL),
            )
        }.toString()

        fun decode(json: String): List<CoucouTask> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CoucouTask(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    done = o.optBoolean("done"),
                    remindAt = if (o.isNull("remindAt")) null else o.getLong("remindAt"),
                    notified = o.optBoolean("notified"),
                    created = o.optLong("created"),
                    completedAt = if (o.isNull("completedAt")) null else o.getLong("completedAt"),
                    jinx = o.optBoolean("jinx"),
                    jinxId = if (o.isNull("jinxId")) null else o.getString("jinxId"),
                )
            }
        }
    }
}
