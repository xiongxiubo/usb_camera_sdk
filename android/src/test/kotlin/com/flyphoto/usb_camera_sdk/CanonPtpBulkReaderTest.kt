package com.flyphoto.usb_camera_sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

class CanonPtpBulkReaderTest {
    @Test
    fun eosR8ReadUsesFullEndpointPacketAndPreservesSurplusBytes() {
        val bytes = ByteArray(700) { index -> (index and 0xff).toByte() }
        val transport = FakeBulkTransport(bytes = bytes, rejectShortReads = true)
        val reader = CanonPtpBulkReader(transport)

        val header = ByteArray(12)
        val payload = ByteArray(688)
        reader.readExactly(header, timeoutMs = 1_000)
        reader.readExactly(payload, timeoutMs = 1_000)

        assertArrayEquals(bytes.copyOfRange(0, 12), header)
        assertArrayEquals(bytes.copyOfRange(12, bytes.size), payload)
        assertTrue(transport.requestedLengths.all { it >= 512 })
    }

    @Test
    fun fragmentedUsbPacketsAreCombinedIntoExactPtpRanges() {
        val bytes = ByteArray(40) { index -> (index + 1).toByte() }
        val transport = FakeBulkTransport(
            bytes = bytes,
            chunkSizes = ArrayDeque(listOf(5, 7, 28)),
        )
        val reader = CanonPtpBulkReader(transport)
        val result = ByteArray(bytes.size)

        reader.readExactly(result, timeoutMs = 1_000)

        assertArrayEquals(bytes, result)
        assertEquals(3, transport.readCalls)
    }

    @Test
    fun streamedPhotoConsumesBufferedPrefixBeforeReadingMorePackets() {
        val bytes = ByteArray(1_200) { index -> (index % 251).toByte() }
        val transport = FakeBulkTransport(bytes)
        val reader = CanonPtpBulkReader(transport)
        val header = ByteArray(12)
        val output = ByteArrayOutputStream()

        reader.readExactly(header, timeoutMs = 1_000)
        reader.copyExactly(bytes.size - header.size, output, timeoutMs = 1_000)

        assertArrayEquals(bytes.copyOfRange(0, 12), header)
        assertArrayEquals(bytes.copyOfRange(12, bytes.size), output.toByteArray())
        assertEquals(3, transport.readCalls)
    }

    @Test
    fun zeroLengthPacketBeforeResponseIsIgnored() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val transport = FakeBulkTransport(
            bytes = bytes,
            chunkSizes = ArrayDeque(listOf(0, bytes.size)),
        )
        val reader = CanonPtpBulkReader(transport)
        val result = ByteArray(bytes.size)

        reader.readExactly(result, timeoutMs = 1_000)

        assertArrayEquals(bytes, result)
        assertEquals(2, transport.readCalls)
    }

    private class FakeBulkTransport(
        private val bytes: ByteArray,
        private val rejectShortReads: Boolean = false,
        private val chunkSizes: ArrayDeque<Int> = ArrayDeque(),
    ) : CanonPtpBulkTransport {
        override val maxPacketSize = 512
        val requestedLengths = mutableListOf<Int>()
        var readCalls = 0
            private set
        private var offset = 0

        override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            requestedLengths += length
            readCalls += 1
            if (rejectShortReads && length < maxPacketSize) return -1
            val requestedChunk = if (chunkSizes.isEmpty()) length else chunkSizes.removeFirst()
            if (requestedChunk == 0) return 0
            if (offset >= bytes.size) return -1
            val count = minOf(requestedChunk, length, bytes.size - offset)
            System.arraycopy(bytes, offset, buffer, 0, count)
            offset += count
            return count
        }
    }
}
