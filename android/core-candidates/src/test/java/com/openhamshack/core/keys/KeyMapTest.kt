package com.openhamshack.core.keys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin map against the shared vectors, docs/remote-keys.json, line for
 * line and both ways: a binding in one and not the other fails, with every
 * difference listed at once. The Apple twin reads the same file, so the two
 * platforms cannot drift apart (as FormatVectorsTest keeps i18n together).
 */
class KeyMapTest {
    private val doc: JSONObject = run {
        // Gradle runs unit tests in the module's directory; the file is at the repo root's docs/.
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null && !File(dir, "docs/remote-keys.json").exists()) dir = dir.parentFile
        JSONObject(File(requireNotNull(dir) { "docs/remote-keys.json not found above the test's directory" }, "docs/remote-keys.json").readText())
    }

    @Test fun theMapIsTheSharedVectorsExactly() {
        val a = doc.getJSONArray("bindings")
        val shared = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            KeyBinding(
                requireNotNull(FaceClass.byId(o.getString("class"))) { o.toString() },
                requireNotNull(RemoteKey.byId(o.getString("key"))) { o.toString() },
                requireNotNull(Gesture.entries.firstOrNull { it.id == o.getString("gesture") }) { o.toString() },
                requireNotNull(RemoteAction.byId(o.getString("action"))) { o.toString() },
            )
        }
        val ours = KeyMap.STANDARD.bindings
        val missing = shared - ours.toSet()
        val extra = ours - shared.toSet()
        assertTrue("in remote-keys.json, not in KeyMap: $missing\nin KeyMap, not in remote-keys.json: $extra",
            missing.isEmpty() && extra.isEmpty())
        assertEquals("one line each", shared.size, ours.size)
    }

    @Test fun theTimingsAreTheSharedOnes() {
        val hold = doc.getJSONObject("holdMs")
        assertEquals(hold.getLong("ok"), KeyGestures.defaultHoldMs(RemoteKey.OK))
        assertEquals(hold.getLong("menu"), KeyGestures.defaultHoldMs(RemoteKey.MENU))
        assertEquals(hold.getLong("play_pause"), KeyGestures.defaultHoldMs(RemoteKey.PLAY_PAUSE))
        assertEquals(doc.getJSONObject("transmit").getLong("armMs"), TxArm.ARM_MS)
        assertEquals(doc.getLong("overlayIdleMs"), OverlayStack.IDLE_MS)
    }

    @Test fun dPadOkAndBackReachEveryJobBothClassesHave() {
        // Principle 2: Menu, media and channel keys are accelerators, never the only way. The
        // jobs they alone carry (settings, help, step, band, mute, transmit) are on quick
        // controls for a remote without them; here, the D-pad's own jobs must all be bound.
        for (c in FaceClass.entries) {
            val pad = KeyMap.STANDARD.of(c).filter { !it.key.accelerator }.map { it.action }.toSet()
            for (a in listOf(RemoteAction.CHOOSE, RemoteAction.CONTROLS, RemoteAction.FOCUS_DOWN, RemoteAction.FREQUENCY, RemoteAction.BACK)) {
                assertTrue("$c: $a on the D-pad, OK or Back", a in pad)
            }
        }
    }

    @Test fun sameKeySameJobAcrossClasses() {
        // Principle 1: what differs between a radio and a video face is the meaning, not the job.
        val radio = KeyMap.STANDARD.of(FaceClass.RADIO).associate { (it.key to it.gesture) to it.action }
        val video = KeyMap.STANDARD.of(FaceClass.VIDEO).associate { (it.key to it.gesture) to it.action }
        for (k in listOf(RemoteKey.UP, RemoteKey.DOWN, RemoteKey.MENU, RemoteKey.PLAY_PAUSE, RemoteKey.INFO, RemoteKey.BACK)) {
            for (g in Gesture.entries) assertEquals("$k $g", radio[k to g], video[k to g])
        }
        assertEquals(RemoteAction.TUNE_UP, radio[RemoteKey.RIGHT to Gesture.PRESS])
        assertEquals(RemoteAction.CHANNEL_NEXT, video[RemoteKey.RIGHT to Gesture.PRESS])
        assertEquals("a video face changes one channel per press", null, video[RemoteKey.RIGHT to Gesture.REPEAT])
    }
}
