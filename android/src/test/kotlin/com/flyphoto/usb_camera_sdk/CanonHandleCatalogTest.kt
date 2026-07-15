package com.flyphoto.usb_camera_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonHandleCatalogTest {
    @Test
    fun largeBaselineRequiresNoPerObjectMetadataState() {
        val catalog = CanonHandleCatalog()
        val historical = (1..3_000).toSet()

        catalog.replaceBaseline(setOf(0x10001), historical)

        assertTrue(catalog.initialized)
        assertTrue(catalog.hasSameStorage(setOf(0x10001)))
        assertEquals(emptyList<Int>(), catalog.reconcile(historical))
        assertEquals(listOf(3_001), catalog.reconcile(historical + 3_001))
    }

    @Test
    fun storageChangeNeedsNewBaselineInsteadOfReportingHistoryAsNew() {
        val catalog = CanonHandleCatalog()
        catalog.replaceBaseline(setOf(1), setOf(10, 11))

        assertFalse(catalog.hasSameStorage(setOf(2)))

        catalog.replaceBaseline(setOf(2), setOf(100, 101, 102))
        assertEquals(emptyList<Int>(), catalog.reconcile(setOf(100, 101, 102)))
    }

    @Test
    fun observedEventIsNotRepeatedByReconciliation() {
        val catalog = CanonHandleCatalog()
        catalog.replaceBaseline(setOf(1), setOf(10))
        catalog.observe(11)

        assertEquals(emptyList<Int>(), catalog.reconcile(setOf(10, 11)))
    }
}
