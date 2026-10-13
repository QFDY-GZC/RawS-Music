package com.rawsmusic.module.player.lyrics

import org.junit.Assert.*
import org.junit.Test

class BluetoothLyricDiagnosticGateTest {
    @Test fun repeatedTicksDoNotSpamButAStuckLineStillHasAHeartbeat() {
        val gate = BluetoothLyricDiagnosticGate()
        assertTrue(gate.shouldLog("SELECT", "line1", 0))
        assertFalse(gate.shouldLog("SELECT", "line1", 220))
        assertFalse(gate.shouldLog("SELECT", "line1", 14999))
        assertTrue(gate.shouldLog("SELECT", "line1", 15000))
    }
    @Test fun rapidChangesAreRateLimitedWithoutLosingTheLatestState() {
        val gate = BluetoothLyricDiagnosticGate()
        assertTrue(gate.shouldLog("SELECT", "line1", 0))
        assertFalse(gate.shouldLog("SELECT", "line2", 220))
        assertFalse(gate.shouldLog("SELECT", "line3", 2999))
        assertTrue(gate.shouldLog("SELECT", "line3", 3000))
    }
    @Test fun SelectionAndFinalCommitHaveIndependentBudgets() {
        val gate = BluetoothLyricDiagnosticGate()
        assertTrue(gate.shouldLog("SELECT", "line1", 0))
        assertTrue(gate.shouldLog("COMMIT", "line1", 0))
        assertFalse(gate.shouldLog("COMMIT", "line2", 100))
    }
}
