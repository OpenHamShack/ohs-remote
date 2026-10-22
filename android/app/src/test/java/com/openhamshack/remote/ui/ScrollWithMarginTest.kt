package com.openhamshack.remote.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The TV controls row's bring-into-view: a focused item is scrolled clear
 * of the row's ends by the margin, so its focus ring (and its focus lift)
 * is not cut off by the scroll's clip. On the Fire TV the last item had
 * been left flush with the edge (Radio's antenna item, 22/10/2026).
 */
class ScrollWithMarginTest {
    private val spec = ScrollWithMargin(16f)

    @Test fun anItemPastTheEndIsBroughtInClearOfIt() {
        // 200 px item from 950 in a 1000 px view: its end must land at 984, not 1000.
        assertEquals(166f, spec.calculateScrollDistance(950f, 200f, 1000f))
    }

    @Test fun anItemPastTheStartIsBroughtInClearOfIt() {
        assertEquals(-66f, spec.calculateScrollDistance(-50f, 200f, 1000f))
    }

    @Test fun anItemAlreadyClearDoesNotMove() {
        assertEquals(0f, spec.calculateScrollDistance(400f, 200f, 1000f))
    }

    @Test fun anItemInViewButTouchingTheEdgeIsMovedOffIt() {
        // Fully visible, flush with the end: what the Fire TV showed.
        assertEquals(16f, spec.calculateScrollDistance(800f, 200f, 1000f))
    }
}
