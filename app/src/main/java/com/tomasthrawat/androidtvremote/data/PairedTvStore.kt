package com.tomasthrawat.androidtvremote.data

import android.content.Context

data class PairedTv(val name: String, val host: String)

/** Simple persisted list of TVs this app has already paired with (host -> name). */
class PairedTvStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("paired_tvs", Context.MODE_PRIVATE)

    fun save(tv: PairedTv) {
        prefs.edit().putString(tv.host, tv.name).apply()
    }

    fun all(): List<PairedTv> =
        prefs.all.mapNotNull { (host, name) -> if (name is String) PairedTv(name, host) else null }

    fun isPaired(host: String): Boolean = prefs.contains(host)
}
