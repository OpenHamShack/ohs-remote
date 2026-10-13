package com.openhamshack.core.keys

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every alias of REMOTE-KEYS.md §1 lands on its key; anything else is not ours. */
class RemoteKeyTest {
    @Test fun everyAliasMapsToItsKey() {
        val want = mapOf(
            KeyEvent.KEYCODE_DPAD_CENTER to RemoteKey.OK, KeyEvent.KEYCODE_ENTER to RemoteKey.OK,
            KeyEvent.KEYCODE_NUMPAD_ENTER to RemoteKey.OK, KeyEvent.KEYCODE_BUTTON_A to RemoteKey.OK,
            KeyEvent.KEYCODE_BACK to RemoteKey.BACK, KeyEvent.KEYCODE_ESCAPE to RemoteKey.BACK, KeyEvent.KEYCODE_BUTTON_B to RemoteKey.BACK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to RemoteKey.PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY to RemoteKey.PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE to RemoteKey.PLAY_PAUSE,
            KeyEvent.KEYCODE_PAGE_UP to RemoteKey.CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_UP to RemoteKey.CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_DOWN to RemoteKey.CHANNEL_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN to RemoteKey.CHANNEL_DOWN,
            KeyEvent.KEYCODE_INFO to RemoteKey.INFO, KeyEvent.KEYCODE_F1 to RemoteKey.INFO,
            KeyEvent.KEYCODE_MENU to RemoteKey.MENU,
            KeyEvent.KEYCODE_MEDIA_REWIND to RemoteKey.REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to RemoteKey.FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_LEFT to RemoteKey.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to RemoteKey.RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to RemoteKey.UP, KeyEvent.KEYCODE_DPAD_DOWN to RemoteKey.DOWN,
            KeyEvent.KEYCODE_0 to RemoteKey.DIGIT_0, KeyEvent.KEYCODE_2 to RemoteKey.DIGIT_2,
            KeyEvent.KEYCODE_NUMPAD_0 to RemoteKey.DIGIT_0, KeyEvent.KEYCODE_NUMPAD_9 to RemoteKey.DIGIT_9,
        )
        want.forEach { (code, key) -> assertEquals("key code $code", key, RemoteKey.fromKeyCode(code)) }
        assertEquals(7, RemoteKey.fromKeyCode(KeyEvent.KEYCODE_0 + 7)?.digit)
    }

    @Test fun keysTheTvKeepsAreNotOurs() {
        for (code in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_A)) {
            assertNull("key code $code", RemoteKey.fromKeyCode(code))
        }
    }
}
