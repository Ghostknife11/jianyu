package org.jianyu.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryDisclosureTest {
    @Test
    fun `world query approval names region only when the entered value will be sent`() {
        assertFalse("coarse-region" in publicWorldQueryDisclosure(null).includedCategories)
        assertFalse("coarse-region" in publicWorldQueryDisclosure("   ").includedCategories)
        assertTrue("coarse-region" in publicWorldQueryDisclosure("虚构市东路12号").includedCategories)
        assertTrue("current-interest" in publicWorldQueryDisclosure("虚构市东路12号").excludedCategories)
    }
}
