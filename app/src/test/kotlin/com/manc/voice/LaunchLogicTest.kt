package com.manc.voice

import com.manc.voice.adb.LaunchLogic
import com.manc.voice.adb.LaunchLogic.Inputs
import com.manc.voice.adb.LaunchLogic.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions behind "pair once, then automatic". */
class LaunchLogicTest {
    private val fresh = Inputs(connected = false, busy = false, paired = false, devOptions = false,
        wifi = true, wirelessDebugging = false, canToggle = false)

    @Test fun firstRunWalksTheUserThroughInOrder() {
        assertEquals(Step.NEED_DEV_OPTIONS, LaunchLogic.decide(fresh))
        assertEquals(Step.NEED_WIRELESS_DEBUGGING, LaunchLogic.decide(fresh.copy(devOptions = true)))
        assertEquals(Step.NEED_PAIRING, LaunchLogic.decide(fresh.copy(devOptions = true, wirelessDebugging = true)))
        assertEquals(Step.WORKING, LaunchLogic.decide(fresh.copy(devOptions = true, wirelessDebugging = true, busy = true)))
        assertEquals(Step.CONNECTED, LaunchLogic.decide(fresh.copy(connected = true)))
    }

    @Test fun wifiIsAskedForOnlyWhenNeeded() {
        assertEquals(Step.NEED_WIFI, LaunchLogic.decide(fresh.copy(devOptions = true, wifi = false)))
        // a running helper needs no Wi-Fi at all
        assertEquals(Step.CONNECTED, LaunchLogic.decide(fresh.copy(connected = true, wifi = false)))
    }

    @Test fun afterRebootItRelaunchesWithoutTheUser() {
        val rebooted = Inputs(connected = false, busy = false, paired = true, devOptions = true,
            wifi = true, wirelessDebugging = false, canToggle = true)
        assertEquals(Step.READY_TO_LAUNCH, LaunchLogic.decide(rebooted))
        // without the self-toggle permission the user has to flip the switch once
        assertEquals(Step.NEED_WIRELESS_DEBUGGING, LaunchLogic.decide(rebooted.copy(canToggle = false)))
        assertEquals(Step.READY_TO_LAUNCH, LaunchLogic.decide(rebooted.copy(canToggle = false, wirelessDebugging = true)))
        assertEquals(Step.NEED_WIFI, LaunchLogic.decide(rebooted.copy(wifi = false)))
    }

    @Test fun wirelessDebuggingOnImpliesDeveloperOptions() {
        assertEquals(Step.NEED_PAIRING, LaunchLogic.decide(fresh.copy(devOptions = false, wirelessDebugging = true)))
    }

    @Test fun retryBacksOffAndIsCapped() {
        assertEquals(20, LaunchLogic.retryGapSeconds(0))
        assertEquals(20, LaunchLogic.retryGapSeconds(2))
        assertEquals(40, LaunchLogic.retryGapSeconds(3))
        assertEquals(80, LaunchLogic.retryGapSeconds(4))
        assertEquals(300, LaunchLogic.retryGapSeconds(7))
        assertEquals(300, LaunchLogic.retryGapSeconds(50))
        for (f in 0..60) assertTrue(LaunchLogic.retryGapSeconds(f) in 20..300)
    }

    @Test fun launchScriptStartsADetachedHelperForThisPackage() {
        val s = LaunchLogic.buildLaunchScript("com.manc.voice.debug", 42, 0)
        assertTrue(s.contains("pm grant com.manc.voice.debug android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(s.contains("app_process /system/bin --nice-name=manc_voice_bridge com.manc.voice.server.BridgeServer com.manc.voice.debug 42 0"))
        assertTrue(s.contains("</dev/null &"))
        assertTrue(s.trim().endsWith(LaunchLogic.DONE_MARKER))
        // it waits for the helper's own confirmation and reports when that never comes
        assertTrue(s.contains("grep -q \"${LaunchLogic.DELIVERED_MARKER}\""))
        assertTrue(s.contains("content call --uri content://com.manc.voice.debug.bridge --method ${LaunchLogic.PING_METHOD}"))
        assertTrue(s.contains("logcat -d"))
        // "cmd &; next" is a shell syntax error: commands are separated by newlines
        assertFalse(s.contains("&;"))
        assertFalse(s.contains("& ;"))
    }

    @Test fun grantScriptOnlyGrantsThePermission() {
        val s = LaunchLogic.buildGrantScript("com.manc.voice.debug")
        assertTrue(s.startsWith("pm grant com.manc.voice.debug android.permission.WRITE_SECURE_SETTINGS"))
        assertFalse(s.contains("app_process"))
        assertTrue(s.trim().endsWith(LaunchLogic.DONE_MARKER))
    }

    @Test(expected = IllegalArgumentException::class)
    fun launchScriptRefusesShellMetacharacters() {
        LaunchLogic.buildLaunchScript("com.x; rm -rf /", 1, 0)
    }

    @Test fun onlyTheOwningAppMayUseTheBridge() {
        assertTrue(LaunchLogic.isAllowedBridgeCaller(callerUid = 10234, appUid = 10234, selfUid = 2000))
        assertTrue(LaunchLogic.isAllowedBridgeCaller(callerUid = 2000, appUid = 10234, selfUid = 2000))
        assertTrue(LaunchLogic.isAllowedBridgeCaller(callerUid = 0, appUid = 10234, selfUid = 2000))
        assertFalse(LaunchLogic.isAllowedBridgeCaller(callerUid = 10999, appUid = 10234, selfUid = 2000))
        // unresolved app UID: no check (the Binder is only handed to that app anyway)
        assertTrue(LaunchLogic.isAllowedBridgeCaller(callerUid = 10999, appUid = -1, selfUid = 2000))
    }

    @Test fun onlyShellOrRootMayDeliverTheBridge() {
        assertTrue(LaunchLogic.isTrustedCaller(2000))
        assertTrue(LaunchLogic.isTrustedCaller(0))
        assertTrue(LaunchLogic.isTrustedCaller(1002000)) // shell in a secondary user
        assertFalse(LaunchLogic.isTrustedCaller(10234))  // an ordinary app
        assertFalse(LaunchLogic.isTrustedCaller(1000))   // even system is not expected here
    }
}
