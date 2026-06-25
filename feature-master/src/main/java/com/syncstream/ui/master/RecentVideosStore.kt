package com.syncstream.ui.master

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A previously-streamed video the master can re-pick from its recent list. */
data class RecentVideo(val uri: String, val name: String)

/**
 * Persists the last [MAX] videos the master actually streamed, newest-first and de-duped by URI,
 * in [android.content.SharedPreferences] (encoded as a small JSON array — no extra dependency).
 * Re-streaming a video promotes it back to the front. The stored URIs are SAF document URIs for
 * which a persistable read permission was taken when first picked, so they survive process death.
 */
class RecentVideosStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<RecentVideo>> = _recent.asStateFlow()

    /** Promote [uri] to the front (de-duped), cap to [MAX], and persist. */
    fun record(uri: Uri, name: String) {
        val key = uri.toString()
        val deduped = _recent.value.filterNot { it.uri == key }
        val updated = (listOf(RecentVideo(key, name)) + deduped).take(MAX)
        _recent.value = updated
        persist(updated)
    }

    private fun load(): List<RecentVideo> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return@runCatching emptyList()
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("uri")
                if (u.isNotEmpty()) add(RecentVideo(u, o.optString("name", u)))
            }
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<RecentVideo>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val PREFS = "syncstream_master"
        const val KEY = "recent_videos"
        const val MAX = 10
    }
}
