package com.openhamshack.remote.shack

import android.content.Context

/**
 * The one setting for decoder panels: whether they open by themselves when
 * their decoder starts (on unless turned off), on this device, for every
 * NB/Radio app. It began as SSTV's alone ("sstv"/"autoOpen"); a device that
 * turned that off keeps it off now that it covers every decoder, since
 * someone who did not want pictures jumping up will not want lists either.
 */
object PanelPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("decoders", Context.MODE_PRIVATE)

    fun autoOpen(c: Context): Boolean = prefs(c).getBoolean("autoOpen",
        c.getSharedPreferences("sstv", Context.MODE_PRIVATE).getBoolean("autoOpen", true))

    fun setAutoOpen(c: Context, on: Boolean) = prefs(c).edit().putBoolean("autoOpen", on).apply()
}
