package com.example.sonicchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistItem(val time: Long, val dir: String, val text: String, val partial: Boolean)

object History {
    private const val FILE = "history.json"

    fun load(ctx: Context): MutableList<HistItem> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<HistItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(HistItem(o.getLong("t"), o.getString("d"), o.getString("x"), o.optBoolean("p")))
            }
            out
        } catch (e: Exception) { mutableListOf() }
    }

    fun save(ctx: Context, list: List<HistItem>) {
        val arr = JSONArray()
        list.takeLast(200).forEach {
            arr.put(JSONObject().put("t", it.time).put("d", it.dir).put("x", it.text).put("p", it.partial))
        }
        File(ctx.filesDir, FILE).writeText(arr.toString())
    }

    fun add(ctx: Context, item: HistItem) {
        val l = load(ctx)
        l.add(item)
        save(ctx, l)
    }

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
