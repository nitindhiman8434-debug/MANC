package com.manc.voice

import com.manc.voice.server.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The control-line contract between the in-app client and the shell-UID server. */
class BridgeProtocolTest {

    @Test fun everywhereRoundTrips() {
        val c = BridgeProtocol.Command.Everywhere(16000)
        assertEquals(c, BridgeProtocol.parse(BridgeProtocol.format(c)))
    }

    @Test fun uidRoundTrips() {
        val c = BridgeProtocol.Command.Uid(10234, 16000)
        assertEquals(c, BridgeProtocol.parse(BridgeProtocol.format(c)))
    }

    @Test fun presetRoundTrips() {
        val c = BridgeProtocol.Command.Preset(7, 48000)
        assertEquals(c, BridgeProtocol.parse(BridgeProtocol.format(c)))
    }

    @Test fun describeAndStopRoundTrip() {
        assertEquals(BridgeProtocol.Command.Describe, BridgeProtocol.parse(BridgeProtocol.format(BridgeProtocol.Command.Describe)))
        assertEquals(BridgeProtocol.Command.Stop, BridgeProtocol.parse(BridgeProtocol.format(BridgeProtocol.Command.Stop)))
    }

    @Test fun toleratesWhitespaceAndCase() {
        assertEquals(BridgeProtocol.Command.Everywhere(16000), BridgeProtocol.parse("  everywhere   16000  "))
    }

    @Test fun rejectsGarbage() {
        assertNull(BridgeProtocol.parse(""))
        assertNull(BridgeProtocol.parse("FOO 1 2"))
        assertNull(BridgeProtocol.parse("UID notanumber 16000"))
        assertNull(BridgeProtocol.parse("EVERYWHERE"))
    }

    @Test fun socketNamesAreNamespacedPerApp() {
        val a = BridgeProtocol.socketName("com.manc.voice", BridgeProtocol.MIC)
        val b = BridgeProtocol.socketName("com.manc.voice.debug", BridgeProtocol.MIC)
        assertTrue(a != b)
        assertTrue(a.contains("mic"))
    }
}
