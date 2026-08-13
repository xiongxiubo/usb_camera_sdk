package com.flyphoto.usb_camera_sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

internal data class CanonEosEvent(
    val code: Int,
    val length: Int,
    val photo: CanonEosPhotoEvent? = null,
)

internal data class CanonEosPhotoEvent(
    val handle: Int,
    val storageId: Int,
    val objectFormat: Int,
    val compressedSize: Long,
    val parentObject: Int,
    val fileName: String,
)

internal data class CanonEosEventBatch(
    val events: List<CanonEosEvent>,
    val malformedLength: Int? = null,
)

/** Decoder for Canon EOS GetEvent records, including the R-series 64-bit variant. */
internal object CanonEosEventParser {
    const val OBJECT_ADDED = 0x4002
    const val OBJECT_ADDED_EX = 0xc181
    const val WILL_SOON_SHUTDOWN = 0xc18d
    const val OBJECT_ADDED_EX_64 = 0xc1a7

    fun decode(payload: ByteArray): CanonEosEventBatch {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val events = mutableListOf<CanonEosEvent>()
        while (buffer.remaining() >= EVENT_HEADER_SIZE) {
            val eventStart = buffer.position()
            val eventLength = buffer.int
            val eventCode = buffer.int
            if (eventLength < EVENT_HEADER_SIZE || eventStart + eventLength > buffer.limit()) {
                return CanonEosEventBatch(events, malformedLength = eventLength)
            }
            if (eventLength == EVENT_HEADER_SIZE && eventCode == 0) break
            val photo = when (eventCode) {
                OBJECT_ADDED -> decodeHandleOnly(payload, eventStart, eventLength)
                OBJECT_ADDED_EX -> decodePhoto(payload, eventStart, eventLength, NAME_OFFSET_32, PARENT_OFFSET_32)
                OBJECT_ADDED_EX_64 -> decodePhoto(payload, eventStart, eventLength, NAME_OFFSET_64, PARENT_OFFSET_64)
                else -> null
            }
            events += CanonEosEvent(eventCode, eventLength, photo)
            buffer.position(eventStart + eventLength)
        }
        return CanonEosEventBatch(events)
    }

    private fun decodePhoto(
        payload: ByteArray,
        eventStart: Int,
        eventLength: Int,
        nameOffset: Int,
        parentOffset: Int,
    ): CanonEosPhotoEvent? {
        if (eventLength < HANDLE_OFFSET + Int.SIZE_BYTES) return null
        val eventEnd = eventStart + eventLength
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val handle = buffer.getInt(eventStart + HANDLE_OFFSET)
        val storageId = if (eventLength >= STORAGE_OFFSET + Int.SIZE_BYTES) {
            buffer.getInt(eventStart + STORAGE_OFFSET)
        } else {
            0
        }
        val objectFormat = if (eventLength >= FORMAT_OFFSET + Short.SIZE_BYTES) {
            buffer.getShort(eventStart + FORMAT_OFFSET).toInt() and 0xffff
        } else {
            0
        }
        val compressedSize = if (eventLength >= SIZE_OFFSET + Int.SIZE_BYTES) {
            buffer.getInt(eventStart + SIZE_OFFSET).toLong() and 0xffffffffL
        } else {
            0L
        }
        val parentObject = if (eventLength >= parentOffset + Int.SIZE_BYTES) {
            buffer.getInt(eventStart + parentOffset)
        } else {
            0
        }
        val nameStart = eventStart + nameOffset
        var nameEnd = nameStart.coerceAtMost(eventEnd)
        while (nameEnd < eventEnd && payload[nameEnd].toInt() != 0) nameEnd += 1
        val fileName = if (nameStart < eventEnd) {
            String(payload, nameStart, nameEnd - nameStart, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return CanonEosPhotoEvent(
            handle = handle,
            storageId = storageId,
            objectFormat = objectFormat,
            compressedSize = compressedSize,
            parentObject = parentObject,
            fileName = fileName,
        )
    }

    private fun decodeHandleOnly(
        payload: ByteArray,
        eventStart: Int,
        eventLength: Int,
    ): CanonEosPhotoEvent? {
        if (eventLength < HANDLE_OFFSET + Int.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return CanonEosPhotoEvent(
            handle = buffer.getInt(eventStart + HANDLE_OFFSET),
            storageId = 0,
            objectFormat = 0,
            compressedSize = 0L,
            parentObject = 0,
            fileName = "",
        )
    }

    private const val EVENT_HEADER_SIZE = 8
    private const val HANDLE_OFFSET = 0x08
    private const val STORAGE_OFFSET = 0x0c
    private const val FORMAT_OFFSET = 0x10
    private const val SIZE_OFFSET = 0x1c
    private const val PARENT_OFFSET_32 = 0x20
    private const val NAME_OFFSET_32 = 0x28
    private const val PARENT_OFFSET_64 = 0x24
    private const val NAME_OFFSET_64 = 0x2c
}
