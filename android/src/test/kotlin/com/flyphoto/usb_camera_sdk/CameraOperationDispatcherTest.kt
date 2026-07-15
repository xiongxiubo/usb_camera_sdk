package com.flyphoto.usb_camera_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CameraOperationDispatcherTest {
    @Test
    fun foregroundOperationRunsBeforeRescheduledEventPoll() {
        val dispatcher = CameraOperationDispatcher()
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstPollStarted = CountDownLatch(1)
        val releaseFirstPoll = CountDownLatch(1)
        val finished = CountDownLatch(2)

        dispatcher.schedule(0) {
            order += "poll-1"
            firstPollStarted.countDown()
            releaseFirstPoll.await(2, TimeUnit.SECONDS)
            dispatcher.schedule(0) {
                order += "poll-2"
                finished.countDown()
            }
        }

        assertTrue(firstPollStarted.await(2, TimeUnit.SECONDS))
        dispatcher.execute {
            order += "download"
            finished.countDown()
        }
        releaseFirstPoll.countDown()

        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("poll-1", "download", "poll-2"), order)
        dispatcher.shutdown()
    }
}
