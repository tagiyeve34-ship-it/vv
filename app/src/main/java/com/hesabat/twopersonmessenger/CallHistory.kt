package com.hesabat.twopersonmessenger

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class CallLogItem(
    val id: Long = System.currentTimeMillis(),
    val callUuid: String = "",
    val direction: String = "outgoing",
    val type: String = "audio",
    val status: String = "completed",
    val startedAt: Long = System.currentTimeMillis(),
    val durationSec: Long = 0
)

object CallHistory {
    private const val PREF = "call_history"
    private const val KEY = "items"
    private val gson = Gson()
    fun list(c: Context): MutableList<CallLogItem> {
        val raw=c.getSharedPreferences(PREF,0).getString(KEY,"[]") ?: "[]"
        return runCatching { gson.fromJson<List<CallLogItem>>(raw, object:TypeToken<List<CallLogItem>>(){}.type).toMutableList() }.getOrElse { mutableListOf() }
    }
    fun add(c: Context, item: CallLogItem) {
        val a=list(c); a.removeAll { it.callUuid.isNotBlank() && it.callUuid==item.callUuid && it.direction==item.direction }; a.add(0,item)
        c.getSharedPreferences(PREF,0).edit().putString(KEY,gson.toJson(a.take(300))).apply()
    }
}
