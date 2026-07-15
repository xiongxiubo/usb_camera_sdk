package com.flyphoto.usb_camera_sdk

/** Tracks Canon EOS object events until ObjectInfo becomes readable. */
internal class CanonPhotoHandleRegistry(
    private val maxAttempts: Int,
    private val maxAgeMs: Long,
    private val publishedLimit: Int,
) {
    private val pending = linkedMapOf<Int, PendingHandle>()
    private val published = linkedSetOf<Int>()

    fun offer(handle: Int, nowMs: Long): Boolean {
        if (published.contains(handle) || pending.containsKey(handle)) return false
        pending[handle] = PendingHandle(firstSeenAtMs = nowMs)
        return true
    }

    fun pendingHandles(): List<Int> = pending.keys.toList()

    fun recordFailure(
        handle: Int,
        nowMs: Long,
        transient: Boolean,
    ): CanonPhotoHandleFailure {
        val candidate = pending[handle]
            ?: return CanonPhotoHandleFailure(attempts = 0, willRetry = false)
        candidate.attempts += 1
        val exhausted = candidate.attempts >= maxAttempts ||
            nowMs - candidate.firstSeenAtMs >= maxAgeMs
        val willRetry = transient && !exhausted
        if (!willRetry) pending.remove(handle)
        return CanonPhotoHandleFailure(candidate.attempts, willRetry)
    }

    fun discard(handle: Int) {
        pending.remove(handle)
    }

    fun markPublished(handle: Int) {
        pending.remove(handle)
        published += handle
        while (published.size > publishedLimit) published.remove(published.first())
    }

    fun clear() {
        pending.clear()
        published.clear()
    }

    private data class PendingHandle(
        val firstSeenAtMs: Long,
        var attempts: Int = 0,
    )
}

internal data class CanonPhotoHandleFailure(
    val attempts: Int,
    val willRetry: Boolean,
)
