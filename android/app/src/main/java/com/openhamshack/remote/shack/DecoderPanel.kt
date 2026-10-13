package com.openhamshack.remote.shack

// Which decoder panel the NB/Radio screens show, and when it opens and
// closes by itself - OpenWebRX+'s behaviour (Brian, 09/10/2026: "decoders
// automatically open"): pick FT8, or a bookmark whose mode is FT8, and the
// message list is there; go back to USB and it goes away. Pure, so the
// rules are tested on the JVM rather than found by pressing keys on a TV.
//
// The engine runs the decoder for its current mode by itself (CW decodes in
// CW, FT8 in FT8: REMOTE-CONTRACT 12), so "a decoder is active" is the
// engine's `mode` state, whoever changed it - this screen, a bookmark, or
// the operator at the station.

/** The panel a decoder's output is shown in. */
enum class DecoderPanel {
    NONE,
    /** A character decoder's running text: CW, RTTY, PSK. */
    TEXT,
    /** A slotted decoder's messages, WSJT-X's list: FT8, FT4, Q65, JS8. */
    SLOTS,
    /** SSTV: the picture as it is painted. */
    SSTV;

    companion object {
        // The engines' decoders as ohscore/modes has them. A mode that is not
        // here (a future WSPR, a new text mode) is learnt from the first
        // event it sends ([of]'s `seen`), so a new engine decoder gets its
        // panel without a release of this app.
        private val SLOT_MODES = setOf("ft8", "ft4", "q65", "js8")
        private val TEXT_MODES = setOf("cw", "rtty", "psk31", "psk63", "psk125")

        /**
         * The panel for [mode]: known decoders by name, else what that mode's
         * own events have shown it to be ([seen], filled from the events
         * socket), else none - a voice mode (USB, FM, FreeDV) has no panel.
         */
        fun of(mode: String, seen: Map<String, DecoderPanel> = emptyMap()): DecoderPanel = when {
            mode in SLOT_MODES -> SLOTS
            mode in TEXT_MODES -> TEXT
            mode == "sstv" -> SSTV
            else -> seen[mode] ?: NONE
        }

        /** What a decoder event says its mode's panel is (an engine's decoder this build has no name for). */
        fun ofEvent(e: EngineEvent): Pair<String, DecoderPanel>? = when (e) {
            is EngineEvent.Decoded -> e.mode to TEXT
            is EngineEvent.Slot -> e.decode.mode to SLOTS
            is EngineEvent.Sstv -> "sstv" to SSTV
            else -> null
        }
    }
}

/**
 * The panel on screen. [active] is the decoder the engine is running;
 * [shown] the panel drawn, NONE when closed. Kept apart because the
 * operator may close a panel while its decoder runs on (BACK on it), and
 * it then stays closed until the decoder changes.
 */
data class PanelState(val active: DecoderPanel = DecoderPanel.NONE, val shown: DecoderPanel = DecoderPanel.NONE) {
    /**
     * The engine's decoder is now [now]. A decoder starting opens its panel
     * when [autoOpen] (the setting, on by default) - or when a panel was
     * already open, which the operator opened by hand and so wants kept
     * through a change of mode. A decoder stopping (a voice mode) closes it.
     * The same decoder again (a state message that changed something else)
     * changes nothing, so a panel closed by hand is not reopened by the
     * next dial move.
     */
    fun decoder(now: DecoderPanel, autoOpen: Boolean): PanelState = when {
        now == active -> this
        now == DecoderPanel.NONE -> PanelState(now, DecoderPanel.NONE)
        autoOpen || shown != DecoderPanel.NONE -> PanelState(now, now)
        else -> PanelState(now, DecoderPanel.NONE)
    }

    /**
     * An SSTV picture has started. The decoder was already running, but a
     * picture arriving is the moment someone wants to see it: the panel
     * opens again even if it was closed, unless opening by itself is off.
     */
    fun sstvStarted(autoOpen: Boolean): PanelState =
        if (autoOpen && active == DecoderPanel.SSTV) copy(shown = DecoderPanel.SSTV) else this

    /** The operator closed the panel; it stays closed until the decoder changes. */
    fun close(): PanelState = copy(shown = DecoderPanel.NONE)

    /** The operator opened the panel: the running decoder's, else (a voice mode) nothing to open. */
    fun open(): PanelState = copy(shown = active)
}
