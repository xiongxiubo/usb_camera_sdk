package com.flyphoto.usb_camera_sdk

import java.io.OutputStream

/** Minimal transport boundary so Canon PTP packet framing can be JVM-tested. */
internal interface CanonPtpBulkTransport {
    val maxPacketSize: Int

    fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int
}

/**
 * Reads exact PTP byte ranges without issuing short USB reads.
 *
 * Some recent Canon bodies, including the EOS R8, reject a bulk-in request
 * smaller than the endpoint packet size. A PTP header is only 12 bytes, so
 * every USB read uses a full packet buffer and surplus bytes are retained for
 * the following payload or container.
 */
internal class CanonPtpBulkReader(
    private val transport: CanonPtpBulkTransport,
) {
    private val receiveBuffer = ByteArray(transport.maxPacketSize.coerceAtLeast(MIN_READ_SIZE))
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    fun readExactly(target: ByteArray, timeoutMs: Int) {
        readExactly(target, 0, target.size, timeoutMs)
    }

    fun readExactly(target: ByteArray, offset: Int, length: Int, timeoutMs: Int) {
        require(offset >= 0 && length >= 0 && offset + length <= target.size)
        var targetOffset = offset
        var remaining = length
        while (remaining > 0) {
            ensurePending(timeoutMs)
            val available = pending.size - pendingOffset
            val copied = minOf(available, remaining)
            System.arraycopy(pending, pendingOffset, target, targetOffset, copied)
            pendingOffset += copied
            targetOffset += copied
            remaining -= copied
            clearConsumedPending()
        }
    }

    fun copyExactly(length: Int, output: OutputStream, timeoutMs: Int) {
        require(length >= 0)
        var remaining = length
        while (remaining > 0) {
            ensurePending(timeoutMs)
            val available = pending.size - pendingOffset
            val copied = minOf(available, remaining)
            output.write(pending, pendingOffset, copied)
            pendingOffset += copied
            remaining -= copied
            clearConsumedPending()
        }
    }

    fun reset() {
        pending = ByteArray(0)
        pendingOffset = 0
    }

    private fun ensurePending(timeoutMs: Int) {
        if (pendingOffset < pending.size) return
        var zeroLengthReads = 0
        while (true) {
            val count = transport.read(receiveBuffer, receiveBuffer.size, timeoutMs)
            if (count > 0) {
                pending = receiveBuffer.copyOf(count)
                pendingOffset = 0
                return
            }
            if (count == 0 && zeroLengthReads < MAX_ZERO_LENGTH_READS) {
                zeroLengthReads += 1
                continue
            }
            throw CanonPtpTransportException("佳能 PTP 读取超时或失败: $count")
        }
    }

    private fun clearConsumedPending() {
        if (pendingOffset < pending.size) return
        pending = ByteArray(0)
        pendingOffset = 0
    }

    private companion object {
        const val MIN_READ_SIZE = 512
        const val MAX_ZERO_LENGTH_READS = 2
    }
}

internal class CanonPtpTransportException(message: String) : IllegalStateException(message)
