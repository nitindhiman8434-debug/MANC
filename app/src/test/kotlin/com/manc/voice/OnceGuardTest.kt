package com.manc.voice

import com.manc.voice.bridge.hidden.OnceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell bootstrap once recursed without end (prepare() needed the context, and
 * building the context called prepare()). The guard makes that shape harmless.
 */
class OnceGuardTest {
    @Test fun runsOnce() {
        val g = OnceGuard(); var n = 0
        g.run { n++ }; g.run { n++ }
        assertEquals(1, n); assertTrue(g.done)
    }

    @Test fun reentrantCallReturnsInsteadOfRecursing() {
        val g = OnceGuard(); var outer = 0; var inner = 0
        g.run {
            outer++
            g.run { inner++ }                  // direct re-entry
            val lazyValue by lazy { g.run { inner++ }; 42 } // re-entry through a lazy value
            assertEquals(42, lazyValue)
        }
        assertEquals(1, outer); assertEquals(0, inner)
    }

    @Test fun aFailingBlockIsNotRetriedForever() {
        val g = OnceGuard(); var n = 0
        try { g.run { n++; throw IllegalStateException("boom") } } catch (_: IllegalStateException) { }
        g.run { n++ }
        assertEquals(1, n); assertTrue(g.done)
    }

    @Test fun notDoneBeforeFirstRun() { assertFalse(OnceGuard().done) }
}
