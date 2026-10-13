package com.openhamshack.remote.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.openhamshack.remote.R
import com.openhamshack.remote.api.BoxConfig
import com.openhamshack.remote.api.Qo100
import com.openhamshack.remote.api.Receiver

// The catalog side of the pure functions in Format.kt and Qo100Tune.kt:
// they decide what to say, these say it in the device's language. Kept
// apart so the pure half stays testable on the JVM without Android's
// resources (docs/I18N.md).

/**
 * The locale the UI is showing, as a BCP 47 tag for ShackFormat. Read from
 * the composition's configuration rather than Locale.getDefault(), so a
 * per-app language (Android 13's setting) and a configuration change are
 * both followed.
 */
@Composable
fun localeTag(): String = LocalConfiguration.current.locales[0].toLanguageTag()

/**
 * The QO-100 state line: whole phrases joined by " · ", the app's
 * separator, so each phrase is one catalog entry a translator can take
 * whole; the signal count is a plural. [tapHint] adds the tablet's "tap one
 * to receive it" (the TV selects with OK and says so elsewhere).
 */
@Composable
fun qo100StateText(state: Qo100State, tapHint: Boolean = false): String = when (state) {
    Qo100State.Connecting -> stringResource(R.string.qo100_connecting)
    is Qo100State.Failed -> state.error
    is Qo100State.Stale -> stringResource(R.string.qo100_stale, state.seconds.toInt())
    is Qo100State.Live -> listOfNotNull(
        stringResource(R.string.qo100_live),
        pluralStringResource(R.plurals.qo100_signals, state.signals, state.signals),
        if (state.outOfBand) stringResource(R.string.qo100_out_of_band) else null,
        if (tapHint && state.signals > 0) stringResource(R.string.qo100_tap_to_receive) else null,
    ).joinToString(" · ")
}

/** The noise-reduction switch's words: on or off. */
@Composable
internal fun nrLabel(on: Boolean?): String = androidx.compose.ui.res.stringResource(
    if (on == true) com.openhamshack.remote.R.string.shack_nr_on else com.openhamshack.remote.R.string.shack_nr_off)

/** "437.000 MHz · 333 kS", with " · LNB" when received through one. */
@Composable
fun tuningText(r: Receiver): String = stringResource(
    if ((r.offsetMHz ?: 0.0) > 0) R.string.wb_tuning_lnb else R.string.wb_tuning, mhz(r.frequencyMHz), ks(r.symbolRateKs))

/**
 * The receive line the console's strip shows: lock, MER, margin, as
 * phrases joined by " · ". The receiver's own label (its lock word, from
 * the box) is shown as sent; "Locked" only stands in when it is blank.
 */
@Composable
fun lockText(r: Receiver): String = when (r.locked) {
    true -> listOfNotNull(
        r.label.ifBlank { stringResource(R.string.wb_locked) },
        r.mer?.let { stringResource(R.string.wb_mer, db(it)) },
        r.marginDb?.let { stringResource(R.string.wb_margin, (if (it >= 0) "+" else "") + db(it)) },
    ).joinToString(" · ")
    else -> r.label.ifBlank { "—" }
}

/** What the box transmits: frequency, rate, standard and MODCOD, power. */
@Composable
fun txText(c: BoxConfig): String = listOfNotNull(
    stringResource(R.string.wb_mhz, mhz(c.frequencyMHz)),
    stringResource(R.string.wb_ks, ks(c.symbolRateKs)),
    txModcod(c),
    c.gainDb?.let { stringResource(R.string.wb_db, it.toInt().toString()) },
).joinToString(" · ")

/** A picture source by name; one this app does not know, as the box sent it. */
@Composable
fun sourceText(source: String): String = sourceRes(source)?.let { stringResource(it) } ?: source.ifBlank { "—" }

/** A TV channel's name in the banner: a receiver (and its tuner), or a preset frequency. */
fun channelTitle(res: Resources, ch: TvChannel): String = when (ch) {
    is TvChannel.Hear ->
        if (ch.tuner.isNotBlank()) res.getString(R.string.wb_channel_receiver_tuner, ch.id, ch.tuner)
        else res.getString(R.string.wb_channel_receiver, ch.id)
    is TvChannel.Tune -> res.getString(R.string.wb_mhz, ch.preset.label)
}

/**
 * The credit BATC's spectrum is due: its name (the box's, else the
 * monitor's own), where it is, and that it came through the box. The
 * tablet adds the source's address when the box gave one; the TV, read
 * from across a room, leaves it out.
 */
@Composable
fun qo100Credit(qo: Qo100?, tv: Boolean = false): String {
    val source = qo?.source ?: stringResource(R.string.wb_qo100_default_source)
    val url = qo?.sourceUrl?.ifBlank { null }
    return when {
        tv -> stringResource(R.string.wb_qo100_credit_tv, source)
        url != null -> stringResource(R.string.wb_qo100_credit_url, source, url)
        else -> stringResource(R.string.wb_qo100_credit, source)
    }
}
