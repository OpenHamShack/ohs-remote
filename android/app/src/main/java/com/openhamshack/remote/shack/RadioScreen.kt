package com.openhamshack.remote.shack

// The decisions behind Radio's overhauled screen (Brian approved the
// mockups 23/10/2026: "Mockups are good"), kept out of Compose so the JVM
// tests can run them: which bands the picker shows and in which section,
// the short row of bands used last, and what sits beside Mute - the
// transmit button, or the amber "Receive only" strip.

/** The picker's filter chips: everything, the amateur bands only, or the listening areas only. */
enum class BandFilter { ALL, AMATEUR, LISTEN }

/** The picker's two sections, split where HF ends. */
enum class BandSection { LOW, HIGH }

object BandGrid {
    /** HF ends at 30 MHz: LF, MF and HF (CB included) below, VHF, UHF and the microwave bands above. */
    const val HF_TOP_HZ = 30_000_000L

    fun section(b: BandButton): BandSection = if (b.band.loHz < HF_TOP_HZ) BandSection.LOW else BandSection.HIGH

    fun keep(b: BandButton, f: BandFilter): Boolean = when (f) {
        BandFilter.ALL -> true
        BandFilter.AMATEUR -> b.area == null
        BandFilter.LISTEN -> b.area != null
    }

    /**
     * The picker's sections for [filter], each in frequency order (the
     * buttons already are: RadioBands.buttons), an empty one left out. The
     * buttons are the receiver's own (rxRange), so a band it cannot reach
     * is not here to hide.
     */
    fun sections(buttons: List<BandButton>, filter: BandFilter): List<Pair<BandSection, List<BandButton>>> =
        BandSection.entries.mapNotNull { s ->
            buttons.filter { section(it) == s && keep(it, filter) }.takeIf { it.isNotEmpty() }?.let { s to it }
        }
}

/**
 * The phone's short row beside "Bands ▾": the bands pressed last on this
 * device, newest first. One press is one entry (the same band again moves
 * to the front), and a few more are kept than shown so that hiding the lit
 * band still leaves the row full.
 */
object RecentBands {
    const val SHOWN = 3
    const val KEPT = SHOWN + 1

    fun push(recent: List<String>, name: String): List<String> = (listOf(name) + recent.filter { it != name }).take(KEPT)

    /**
     * What the row shows: up to [SHOWN] of [recent] that still have a button
     * (a receiver swapped for one that cannot reach a band drops it), less
     * the lit one, which the readout already names.
     */
    fun shown(recent: List<String>, buttons: List<BandButton>, lit: BandButton?): List<BandButton> =
        recent.mapNotNull { n -> buttons.firstOrNull { it.name == n } }.filter { it != lit }.take(SHOWN)

    fun encode(recent: List<String>): String = recent.joinToString(",")
    fun decode(raw: String?): List<String> = raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(KEPT)
}

/** Why the transmit button is not there: the server's word for the radio or the mode, a receive-only area, or a device that never transmits. */
enum class RxOnlyWhy { RADIO, MODE, AREA, DEVICE }

/**
 * Beside Mute: the transmit button ([ptt] true), or the amber strip with
 * why. [sentence] is the server's own (txBlocked) where it gave one for the
 * radio or the mode; [area] names the listening area the dial is in.
 */
data class TxSlot(val ptt: Boolean, val why: RxOnlyWhy? = null, val sentence: String = "", val area: BandButton? = null) {
    companion object {
        val PTT = TxSlot(true)

        /**
         * The strip replaces the button whenever nothing could be sent:
         *
         * - a radio with no transmitter (the RSPdx: EngineState.receiveOnlyRadio),
         *   in the server's words ("This radio can only receive");
         * - wide FM, which the server never transmits in (its txBlocked says so);
         * - a receive-only listening area (RX-tagged: not LPD433, inside 70 cm);
         * - a device with no voice transmit at all ([canTransmit] false: a TV,
         *   or an app the controller does not let transmit).
         *
         * In that order, so the server's sentence wins where it has one. Any
         * other refusal (the licence, a station profile not set) is the
         * transmit button's own disabled label, as before: the operator can
         * put that right, and the button says how.
         */
        fun of(canTransmit: Boolean, lit: BandButton?, state: EngineState): TxSlot {
            val area = lit?.takeIf { it.rxTag }
            return when {
                state.receiveOnlyRadio -> TxSlot(false, RxOnlyWhy.RADIO, state.txBlocked, area)
                state.mode == "wfm" -> TxSlot(false, RxOnlyWhy.MODE, state.txBlocked, area)
                area != null -> TxSlot(false, RxOnlyWhy.AREA, area = area)
                !canTransmit -> TxSlot(false, RxOnlyWhy.DEVICE)
                else -> PTT
            }
        }
    }
}

/**
 * The receiver the header names after the box ("GM4BRI · SDRplay"), from
 * the engine's `device` (openhamshack-nb's names, ohscore/ui/chrome.py).
 * The screen says the word from its catalog; null for one it does not know.
 */
enum class Receiver { SIMULATOR, PLUTO, LIBRESDR, HL2, RTLSDR, SDRPLAY, SOAPY;
    companion object {
        fun of(device: String): Receiver? = when (device) {
            "sim" -> SIMULATOR
            "pluto" -> PLUTO
            "libresdr" -> LIBRESDR
            "hl2" -> HL2
            "rtlsdr" -> RTLSDR
            "sdrplay" -> SDRPLAY
            "soapy" -> SOAPY
            else -> null
        }
    }
}
