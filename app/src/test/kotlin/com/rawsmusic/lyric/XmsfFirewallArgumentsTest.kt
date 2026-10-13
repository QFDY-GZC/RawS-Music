package com.rawsmusic.lyric

import org.junit.Assert.*
import org.junit.Test

class XmsfFirewallArgumentsTest {
    @Test fun booleanDisableCannotAccidentallyBecomeIntegerRuleDefault() {
        assertTrue(runCatching {
            XmsfFirewallArguments.adapt(arrayOf(Int::class.javaPrimitiveType!!), arrayOf(false))
        }.isFailure)
    }
    @Test fun integerDenyCannotAccidentallyBecomeBooleanEnable() {
        assertTrue(runCatching {
            XmsfFirewallArguments.adapt(arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(2))
        }.isFailure)
        assertEquals(false, XmsfFirewallArguments.adapt(
            arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(false))[0])
        assertEquals(2, XmsfFirewallArguments.adapt(
            arrayOf(Int::class.javaPrimitiveType!!), arrayOf(2))[0])
    }
    @Test fun failedChainActivationMustNotReportRuleApplicationAsSuccess() {
        var ruleApplied = false
        assertTrue(runCatching {
            XmsfFirewallArguments.modern(false, { error("chain unavailable") }, { ruleApplied = true })
        }.isFailure)
        assertFalse(ruleApplied)
    }
    @Test fun restoreDoesNotReenableTheDenyChain() {
        var chainEnabled = false
        var ruleRestored = false
        XmsfFirewallArguments.modern(true, { chainEnabled = true }, { ruleRestored = true })
        assertFalse(chainEnabled)
        assertTrue(ruleRestored)
    }
}
