package com.openhamshack.kind.stationflow

import android.content.res.Resources
import androidx.annotation.StringRes

/**
 * One line the session wants said (a refusal, a decoder started), as a
 * catalog entry and its arguments rather than a sentence: the session has
 * no Context, and should not need one, so the screen that shows the line
 * reads it in the device's language when it arrives. A sentence the
 * Icom module sent is its own translation already and is shown as sent
 * ([verbatim]).
 */
sealed interface Said {
    fun text(r: Resources): String

    /** A catalog entry and its format arguments. */
    class Entry(@StringRes val res: Int, vararg val args: Any) : Said {
        override fun text(r: Resources): String = r.getString(res, *args)
    }

    /** A sentence already in the reader's language. */
    class Verbatim(val text: String) : Said {
        override fun text(r: Resources): String = text
    }

    companion object {
        fun verbatim(text: String): Said = Verbatim(text)
    }
}

/** A catalog entry to say: `Said(R.string.x, arg)`. */
fun Said(@StringRes res: Int, vararg args: Any): Said = Said.Entry(res, *args)
