package com.openhamshack.core.keys

import android.view.KeyEvent

/*
 * The remote-control keys every face of OpenHamShack Remote answers to
 * (openhamshack docs/REMOTE-KEYS.md §1). Only the keys an app can receive:
 * Home, the voice key, volume and power belong to the TV and never arrive.
 *
 * Key codes are Android's ints rather than Compose's Key, so the map and its
 * tests are plain Kotlin and the Apple twin can be checked against the same
 * vectors (docs/remote-keys.json).
 */

enum class RemoteKey(
    /** The name in docs/remote-keys.json. */
    val id: String,
    /**
     * An accelerator works from anywhere on a face's main screen, whatever
     * has focus (§4 principle 5). The D-pad, OK and Back are not: with focus
     * on a control they move focus or press that control.
     */
    val accelerator: Boolean,
) {
    LEFT("left", false), RIGHT("right", false), UP("up", false), DOWN("down", false),
    OK("ok", false), BACK("back", false),
    MENU("menu", true), PLAY_PAUSE("play_pause", true),
    REWIND("rewind", true), FAST_FORWARD("fast_forward", true),
    CHANNEL_UP("channel_up", true), CHANNEL_DOWN("channel_down", true),
    INFO("info", true),
    DIGIT_0("0", true), DIGIT_1("1", true), DIGIT_2("2", true), DIGIT_3("3", true), DIGIT_4("4", true),
    DIGIT_5("5", true), DIGIT_6("6", true), DIGIT_7("7", true), DIGIT_8("8", true), DIGIT_9("9", true);

    /** 0-9 for a digit key, else null. */
    val digit: Int? get() = if (name.startsWith("DIGIT_")) ordinal - DIGIT_0.ordinal else null

    companion object {
        fun byId(id: String): RemoteKey? = entries.firstOrNull { it.id == id }

        /**
         * A key code from any source a Remote meets: a TV remote, a tablet's
         * keyboard, a Bluetooth gamepad. The aliases are §1's: Enter and the
         * number pad's Enter are OK, Escape is Back, Page Up/Down the channel
         * keys, Play and Pause on their own are Play/Pause, F1 is Info.
         * Gamepad A and B are mapped here rather than left to Android's
         * fallback keys, which only arrive when nothing handled the original.
         */
        fun fromKeyCode(code: Int): RemoteKey? = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> UP
            KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> OK
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> BACK
            KeyEvent.KEYCODE_MENU -> MENU
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> PLAY_PAUSE
            KeyEvent.KEYCODE_MEDIA_REWIND -> REWIND
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> FAST_FORWARD
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> CHANNEL_UP
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> CHANNEL_DOWN
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_F1 -> INFO
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> entries[DIGIT_0.ordinal + code - KeyEvent.KEYCODE_0]
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> entries[DIGIT_0.ordinal + code - KeyEvent.KEYCODE_NUMPAD_0]
            else -> null
        }
    }
}
