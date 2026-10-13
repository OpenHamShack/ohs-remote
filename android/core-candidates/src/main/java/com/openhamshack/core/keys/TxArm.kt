package com.openhamshack.core.keys

/*
 * Transmit from a remote, armed then confirmed (REMOTE-KEYS.md §5.2, decided
 * by Brian 11/10/2026): hold Play/Pause for a second to arm - an overlay
 * names exactly what will be sent and nothing is sent yet - then press OK
 * or Play/Pause within five seconds to start. Any other key, Back, or the
 * five seconds running out disarms. While this device is transmitting (or
 * has Enable TX on), any key stops it and does nothing else.
 *
 * Why this shape and not hold-to-talk: a press of Play/Pause is mute, so the
 * pad's "press twice" would turn mute-then-unmute into a transmission; and a
 * remote whose key-up never arrives (a Bluetooth drop, a flat battery) would
 * leave a held key held. A toggle with a lease does not depend on a key-up.
 */

enum class TxPhase {
    /** Nothing armed; keys do what the map says. */
    IDLE,
    /** Armed: the overlay is up; only OK or Play/Pause pressed starts. */
    ARMED,
    /** Confirmed, and the request is on its way. Any key stops. */
    STARTING,
    /** Transmitting, or Enable TX on, from this device. Any key stops. */
    ON,
}

/** What a key did to transmit. */
enum class TxKey {
    /** Nothing to do with transmit: carry on with the map. */
    PASS,
    /** Taken and dropped (the rest of a key that already armed, stopped or confirmed). */
    TAKEN,
    ARM, CONFIRM, DISARM, STOP,
}

/**
 * The state machine, pure: time is passed in, so the tests run it on any
 * clock. The caller sends what it says (start on [TxKey.CONFIRM], stop on
 * [TxKey.STOP]) and tells it what the server answered ([started], [ended]).
 */
class TxArm(val armMs: Long = ARM_MS) {
    var phase: TxPhase = TxPhase.IDLE
        private set
    /** While [TxPhase.ARMED]: when it disarms by itself. */
    var untilMs: Long = 0L
        private set

    /** Hold Play/Pause (or "Transmit…" in quick controls): the overlay goes up, nothing is sent. */
    fun arm(nowMs: Long) {
        if (phase != TxPhase.IDLE) return
        phase = TxPhase.ARMED
        untilMs = nowMs + armMs
    }

    /**
     * A key down. Any key while starting or on air stops it, on the down,
     * the quickest moment there is. While armed, any key but OK and
     * Play/Pause disarms at once; those two are decided by their press.
     */
    fun keyDown(key: RemoteKey, nowMs: Long): TxKey {
        expire(nowMs)
        return when (phase) {
            TxPhase.IDLE -> TxKey.PASS
            TxPhase.ARMED -> if (key == RemoteKey.OK || key == RemoteKey.PLAY_PAUSE) TxKey.TAKEN else disarm()
            TxPhase.STARTING, TxPhase.ON -> stop()
        }
    }

    /** A gesture: the one that arms from idle, and the press that confirms. */
    fun gesture(g: KeyGesture, nowMs: Long): TxKey {
        expire(nowMs)
        return when (phase) {
            TxPhase.IDLE -> if (g.key == RemoteKey.PLAY_PAUSE && g.gesture == Gesture.HOLD) TxKey.ARM else TxKey.PASS
            TxPhase.ARMED -> {
                if ((g.key == RemoteKey.OK || g.key == RemoteKey.PLAY_PAUSE) && g.gesture == Gesture.PRESS) {
                    phase = TxPhase.STARTING
                    TxKey.CONFIRM
                } else disarm()
            }
            TxPhase.STARTING, TxPhase.ON -> stop()
        }
    }

    /** The clock moved: an arm left for [armMs] disarms by itself. */
    fun tick(nowMs: Long): TxKey = if (expire(nowMs)) TxKey.DISARM else TxKey.PASS

    /** A touch screen's Start button: the same confirm, without a key. */
    fun confirm(nowMs: Long): Boolean {
        expire(nowMs)
        if (phase != TxPhase.ARMED) return false
        phase = TxPhase.STARTING
        return true
    }

    /** The server took it: on the air (or Enable TX on) for this device. */
    fun started() {
        if (phase == TxPhase.STARTING) phase = TxPhase.ON
    }

    /**
     * It is over, whoever ended it: refused, stopped here, stopped at the
     * station, the lease lost. Also the way to drop an arm the face could
     * not honour.
     */
    fun ended() {
        phase = TxPhase.IDLE
    }

    /**
     * What the server says about this device's transmission, each time it
     * says anything: on while idle means it is on (a screen reopened over
     * an Enable TX this device still heartbeats), off while on means it
     * ended at the other end. An arm in progress is left alone: the server
     * cannot know about it.
     */
    fun sync(serverOn: Boolean) {
        when {
            serverOn && (phase == TxPhase.IDLE || phase == TxPhase.STARTING) -> phase = TxPhase.ON
            !serverOn && phase == TxPhase.ON -> phase = TxPhase.IDLE
        }
    }

    private fun disarm(): TxKey {
        phase = TxPhase.IDLE
        return TxKey.DISARM
    }

    private fun stop(): TxKey {
        phase = TxPhase.IDLE
        return TxKey.STOP
    }

    private fun expire(nowMs: Long): Boolean {
        if (phase != TxPhase.ARMED || nowMs < untilMs) return false
        phase = TxPhase.IDLE
        return true
    }

    companion object {
        /** Five seconds to confirm, as decided. */
        const val ARM_MS = 5_000L
    }
}
