package com.flyphoto.usb_camera_sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanonEosEventParserTest {
    @Test
    fun decodesObjectAddedEx() {
        val payload = photoEvent(
            code = CanonEosEventParser.OBJECT_ADDED_EX,
            length = 0x40,
            parentOffset = 0x20,
            nameOffset = 0x28,
            fileName = "IMG_1234.JPG",
        )

        val event = CanonEosEventParser.decode(payload).events.single()
        val photo = assertNotNull(event.photo).let { event.photo!! }

        assertEquals(CanonEosEventParser.OBJECT_ADDED_EX, event.code)
        assertEquals(0x10203040, photo.handle)
        assertEquals(0x00010001, photo.storageId)
        assertEquals(0x3801, photo.objectFormat)
        assertEquals(7_340_032L, photo.compressedSize)
        assertEquals(0x55, photo.parentObject)
        assertEquals("IMG_1234.JPG", photo.fileName)
    }

    @Test
    fun decodesObjectAddedEx64Offsets() {
        val payload = photoEvent(
            code = CanonEosEventParser.OBJECT_ADDED_EX_64,
            length = 0x48,
            parentOffset = 0x24,
            nameOffset = 0x2c,
            fileName = "IMG_5678.JPG",
        )

        val event = CanonEosEventParser.decode(payload).events.single()
        val photo = assertNotNull(event.photo).let { event.photo!! }

        assertEquals(CanonEosEventParser.OBJECT_ADDED_EX_64, event.code)
        assertEquals(0x55, photo.parentObject)
        assertEquals("IMG_5678.JPG", photo.fileName)
    }

    @Test
    fun incompletePhotoMetadataRetainsHandleForObjectInfoFallback() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12)
            .putInt(CanonEosEventParser.OBJECT_ADDED_EX)
            .putInt(0x1234)
            .array()

        val photo = CanonEosEventParser.decode(payload).events.single().photo

        assertNotNull(photo)
        assertEquals(0x1234, photo!!.handle)
        assertEquals("", photo.fileName)
    }

    @Test
    fun reportsMalformedLengthWithoutInventingPhoto() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(100)
            .putInt(CanonEosEventParser.OBJECT_ADDED_EX)
            .putInt(1)
            .array()

        val batch = CanonEosEventParser.decode(payload)

        assertEquals(100, batch.malformedLength)
        assertEquals(emptyList<CanonEosEvent>(), batch.events)
        assertNull(batch.events.firstOrNull()?.photo)
    }

    @Test
    fun decodesWillSoonShutdown() {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(8)
            .putInt(CanonEosEventParser.WILL_SOON_SHUTDOWN)
            .array()

        val event = CanonEosEventParser.decode(payload).events.single()

        assertEquals(CanonEosEventParser.WILL_SOON_SHUTDOWN, event.code)
        assertNull(event.photo)
    }

    private fun photoEvent(
        code: Int,
        length: Int,
        parentOffset: Int,
        nameOffset: Int,
        fileName: String,
    ): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, length)
        buffer.putInt(4, code)
        buffer.putInt(0x08, 0x10203040)
        buffer.putInt(0x0c, 0x00010001)
        buffer.putShort(0x10, 0x3801.toShort())
        buffer.putInt(0x1c, 7_340_032)
        buffer.putInt(parentOffset, 0x55)
        val name = fileName.toByteArray(StandardCharsets.UTF_8)
        name.copyInto(bytes, destinationOffset = nameOffset)
        return bytes
    }
}
