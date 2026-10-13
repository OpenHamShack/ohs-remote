package com.openhamshack.core.keys

/**
 * What a key asks a face to do. The ids are the controller pad's where one
 * exists (openhamshack contract/CONTROLLER-PAD.md §3), so a pad and a remote
 * mean the same thing by the same word, and a later station-profile
 * remapping (REMOTE-KEYS.md §7) has names to work with.
 */
enum class RemoteAction(val id: String) {
    TUNE_DOWN("tune:down"), TUNE_UP("tune:up"),
    STEP_DOWN("step:down"), STEP_UP("step:up"),
    BAND_PREV("band:prev"), BAND_NEXT("band:next"),
    CHANNEL_PREV("channel:prev"), CHANNEL_NEXT("channel:next"),
    CHOOSE("choose"), CONTROLS("controls"), FOCUS_DOWN("focus:down"),
    FREQUENCY("frequency"), SETTINGS("settings"), HELP("help"),
    MUTE("mute"), TRANSMIT("transmit"), BACK("back");

    companion object {
        fun byId(id: String): RemoteAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The two kinds of face. A radio's Left/Right tune the dial; a video
 * receiver's change channel: the same job, "change what I receive by the
 * smallest move" (§4 principle 1).
 */
enum class FaceClass(val id: String) {
    RADIO("radio"), VIDEO("video");

    companion object {
        fun byId(id: String): FaceClass? = entries.firstOrNull { it.id == id }
    }
}

/** One line of the map: on a face of [faceClass], [key] [gesture] asks for [action]. */
data class KeyBinding(val faceClass: FaceClass, val key: RemoteKey, val gesture: Gesture, val action: RemoteAction)

/**
 * The standard key map (REMOTE-KEYS.md §4, accepted by Brian 11/10/2026) as
 * one table. Faces never look at raw keys: they say which actions they
 * [can] do and do them; the dispatcher and the key legend both read this.
 *
 * `docs/remote-keys.json` is the same table for the Apple twin and for
 * reading; `KeyMapTest` fails if the two differ by a single line, so a
 * change here is a change there, in the same commit.
 */
class KeyMap(val bindings: List<KeyBinding>) {
    private val index = bindings.associateBy { Triple(it.faceClass, it.key, it.gesture) }

    init {
        require(index.size == bindings.size) { "a key and gesture bound twice on one face class" }
    }

    fun action(faceClass: FaceClass, key: RemoteKey, gesture: Gesture): RemoteAction? = index[Triple(faceClass, key, gesture)]?.action

    /** Every binding of a face class, in the table's order (the legend's order). */
    fun of(faceClass: FaceClass): List<KeyBinding> = bindings.filter { it.faceClass == faceClass }

    companion object {
        private fun MutableList<KeyBinding>.add(c: FaceClass, k: RemoteKey, g: Gesture, a: RemoteAction) = add(KeyBinding(c, k, g, a))

        /** Bindings both classes share, then each class's own. */
        val STANDARD: KeyMap = KeyMap(buildList {
            for (c in FaceClass.entries) {
                val radio = c == FaceClass.RADIO
                // Left/Right: the smallest change of what is received. A radio
                // tunes on while the key is held; a video face changes one
                // channel per press, because every retune costs the tuner a
                // lock (TvTune.kt) and a held key would queue a dozen.
                add(c, RemoteKey.LEFT, Gesture.PRESS, if (radio) RemoteAction.TUNE_DOWN else RemoteAction.CHANNEL_PREV)
                add(c, RemoteKey.RIGHT, Gesture.PRESS, if (radio) RemoteAction.TUNE_UP else RemoteAction.CHANNEL_NEXT)
                if (radio) {
                    add(c, RemoteKey.LEFT, Gesture.REPEAT, RemoteAction.TUNE_DOWN)
                    add(c, RemoteKey.RIGHT, Gesture.REPEAT, RemoteAction.TUNE_UP)
                }
                add(c, RemoteKey.UP, Gesture.PRESS, RemoteAction.CHOOSE)
                add(c, RemoteKey.DOWN, Gesture.PRESS, RemoteAction.FOCUS_DOWN)
                add(c, RemoteKey.OK, Gesture.PRESS, RemoteAction.CONTROLS)
                add(c, RemoteKey.OK, Gesture.HOLD, RemoteAction.FREQUENCY)
                add(c, RemoteKey.BACK, Gesture.PRESS, RemoteAction.BACK)
                add(c, RemoteKey.MENU, Gesture.PRESS, RemoteAction.SETTINGS)
                add(c, RemoteKey.MENU, Gesture.HOLD, RemoteAction.HELP)
                add(c, RemoteKey.PLAY_PAUSE, Gesture.PRESS, RemoteAction.MUTE)
                add(c, RemoteKey.PLAY_PAUSE, Gesture.HOLD, RemoteAction.TRANSMIT)
                // Rewind/FF: the coarser change. Step size on a radio (Left/Right
                // already tune); the band on a video face.
                add(c, RemoteKey.REWIND, Gesture.PRESS, if (radio) RemoteAction.STEP_DOWN else RemoteAction.BAND_PREV)
                add(c, RemoteKey.FAST_FORWARD, Gesture.PRESS, if (radio) RemoteAction.STEP_UP else RemoteAction.BAND_NEXT)
                // Channel keys: the bigger jump. A band on a radio (NB, with one
                // band, steps its bookmarks instead); a channel on a video face.
                add(c, RemoteKey.CHANNEL_UP, Gesture.PRESS, if (radio) RemoteAction.BAND_NEXT else RemoteAction.CHANNEL_NEXT)
                add(c, RemoteKey.CHANNEL_DOWN, Gesture.PRESS, if (radio) RemoteAction.BAND_PREV else RemoteAction.CHANNEL_PREV)
                add(c, RemoteKey.INFO, Gesture.PRESS, RemoteAction.HELP)
                // A digit opens the frequency dialog with that digit typed.
                for (d in RemoteKey.DIGIT_0.ordinal..RemoteKey.DIGIT_9.ordinal) {
                    add(c, RemoteKey.entries[d], Gesture.PRESS, RemoteAction.FREQUENCY)
                }
            }
        })
    }
}
