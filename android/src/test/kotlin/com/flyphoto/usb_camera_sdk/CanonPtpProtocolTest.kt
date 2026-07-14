package com.flyphoto.usb_camera_sdk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanonPtpProtocolTest {
    @Test
    fun photoIdRoundTripsUnsignedObjectHandle() {
        val id = CanonPtpProtocol.photoId(0xf1234567.toInt())

        assertEquals("canon-ptp:f1234567", id)
        assertEquals(0xf1234567.toInt(), CanonPtpProtocol.parsePhotoId(id))
    }

    @Test
    fun decodesObjectHandlesFromLittleEndianPtpArray() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(3).putInt(7).putInt(8).putInt(9).array()

        assertArrayEquals(intArrayOf(7, 8, 9), CanonPtpProtocol.decodeU32Array(bytes))
    }
}
