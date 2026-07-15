package com.flyphoto.usb_camera_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonPhotoHandleRegistryTest {
    @Test
    fun duplicateEosEventsCreateOnePendingPhoto() {
        val registry = registry()

        assertTrue(registry.offer(0x1234, nowMs = 100))
        assertFalse(registry.offer(0x1234, nowMs = 101))
        assertEquals(listOf(0x1234), registry.pendingHandles())

        registry.markPublished(0x1234)
        assertFalse(registry.offer(0x1234, nowMs = 102))
        assertTrue(registry.pendingHandles().isEmpty())
    }

    @Test
    fun transientObjectInfoFailureRetriesThenExhausts() {
        val registry = registry()
        registry.offer(7, nowMs = 0)

        assertTrue(registry.recordFailure(7, nowMs = 100, transient = true).willRetry)
        assertTrue(registry.recordFailure(7, nowMs = 200, transient = true).willRetry)
        val exhausted = registry.recordFailure(7, nowMs = 300, transient = true)

        assertEquals(3, exhausted.attempts)
        assertFalse(exhausted.willRetry)
        assertTrue(registry.pendingHandles().isEmpty())
    }

    @Test
    fun nonTransientFailureIsRemovedImmediately() {
        val registry = registry()
        registry.offer(9, nowMs = 0)

        val failure = registry.recordFailure(9, nowMs = 100, transient = false)

        assertFalse(failure.willRetry)
        assertTrue(registry.pendingHandles().isEmpty())
    }

    private fun registry() = CanonPhotoHandleRegistry(
        maxAttempts = 3,
        maxAgeMs = 5_000,
        publishedLimit = 10,
    )
}
