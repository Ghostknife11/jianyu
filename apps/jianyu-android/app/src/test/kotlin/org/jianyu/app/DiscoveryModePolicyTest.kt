package org.jianyu.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryModePolicyTest {
    @Test
    fun demonstrationNeverPersistsEvenWhenOldDraftRequestedIt() {
        assertFalse(discoveryContextMayPersist(useOfflineDemo = true, requestedPersist = true))
        assertFalse(discoveryChoiceMayPersist("offline-demo"))
    }

    @Test
    fun formalAiModeStillHonorsTheFamiliesRetentionDecision() {
        assertTrue(discoveryContextMayPersist(useOfflineDemo = false, requestedPersist = true))
        assertFalse(discoveryContextMayPersist(useOfflineDemo = false, requestedPersist = false))
        assertTrue(discoveryChoiceMayPersist("byok-ai"))
    }
}
