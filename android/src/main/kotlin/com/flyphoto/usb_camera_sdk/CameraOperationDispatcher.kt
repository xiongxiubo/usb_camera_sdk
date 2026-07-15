package com.flyphoto.usb_camera_sdk

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A single fair submission point for blocking PTP/libgphoto2 work.
 *
 * Event polling re-schedules itself instead of owning a permanent loop, so a
 * foreground download submitted during a poll is already queued first.
 */
internal class CameraOperationDispatcher(
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "FlyPhotoCameraOperations")
        },
) {
    fun execute(action: () -> Unit) {
        runCatching { executor.execute(action) }
    }

    fun schedule(delayMs: Long, action: () -> Unit) {
        runCatching { executor.schedule(action, delayMs, TimeUnit.MILLISECONDS) }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
