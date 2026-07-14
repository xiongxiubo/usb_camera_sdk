/*
 * Portions of the Canon EOS PTP protocol handling are adapted from
 * lightio_v2, which carries the Apache License, Version 2.0.
 *
 * Copyright 2013 Nils Assbeck, Guersel Ayaz and Michael Zoech
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flyphoto.usb_camera_sdk

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A small, SDK-owned EOS PTP implementation. It deliberately has no UI/service dependencies. */
internal class CanonPtpBackend(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice,
    private val log: (String) -> Unit,
    private val onPhotoAdded: (CanonPtpPhoto) -> Unit,
) {
    private val ioLock = Any()
    private val running = AtomicBoolean(false)
    private var ptpInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var transactionId = 0
    private var eventThread: Thread? = null

    fun connect() {
        selectPtpInterface()
        val intf = requireNotNull(ptpInterface)
        check(connection.claimInterface(intf, true)) { "无法占用佳能 PTP USB 接口" }
        try {
            synchronized(ioLock) {
                transaction(OPEN_SESSION, intArrayOf(1))
                transaction(EOS_SET_PC_CONNECT_MODE, intArrayOf(1))
                transaction(EOS_SET_EVENT_MODE, intArrayOf(1))
            }
            running.set(true)
            startEventLoop()
            log("canon_ptp connected device=${device.deviceName}")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun close() {
        running.set(false)
        eventThread?.let { thread ->
            if (thread != Thread.currentThread()) thread.join(1_500)
        }
        eventThread = null
        synchronized(ioLock) {
            runCatching { transaction(CLOSE_SESSION) }
        }
        ptpInterface?.let { intf -> runCatching { connection.releaseInterface(intf) } }
        ptpInterface = null
        bulkIn = null
        bulkOut = null
    }

    fun listPhotos(): List<CanonPtpPhoto> = synchronized(ioLock) {
        check(running.get()) { "佳能 PTP 未连接" }
        val storageIds = CanonPtpProtocol.decodeU32Array(transaction(GET_STORAGE_IDS).data)
        val photos = mutableListOf<CanonPtpPhoto>()
        storageIds.forEach { storageId ->
            val handles = CanonPtpProtocol.decodeU32Array(transaction(GET_OBJECT_HANDLES, intArrayOf(storageId, 0, 0)).data)
            handles.forEach { handle ->
                val info = getObjectInfo(handle) ?: return@forEach
                if (!info.isPhoto) return@forEach
                photos += info.toPhoto(handle)
            }
        }
        photos.sortedByDescending { it.shotAt }
    }

    fun capture(timeoutMs: Long = 12_000): CanonPtpPhoto {
        check(running.get()) { "佳能 PTP 未连接" }
        val result = arrayOfNulls<CanonPtpPhoto>(1)
        val latch = CountDownLatch(1)
        val captureListener = CaptureListener(latch) { photo -> result[0] = photo }
        synchronized(captureListeners) { captureListeners += captureListener }
        try {
            synchronized(ioLock) { transaction(EOS_TAKE_PICTURE) }
            check(latch.await(timeoutMs, TimeUnit.MILLISECONDS)) { "佳能拍摄超时，未收到新照片事件" }
            return checkNotNull(result[0]) { "佳能拍摄未返回照片信息" }
        } finally {
            synchronized(captureListeners) { captureListeners.remove(captureListener) }
        }
    }

    fun download(photoId: String, destination: File): String = synchronized(ioLock) {
        check(running.get()) { "佳能 PTP 未连接" }
        val handle = parsePhotoId(photoId)
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.part")
        runCatching { temporary.delete() }
        try {
            FileOutputStream(temporary).use { output -> downloadObject(handle, output) }
            check(temporary.renameTo(destination)) { "无法完成佳能照片写入" }
            destination.absolutePath
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun startEventLoop() {
        eventThread = Thread({
            while (running.get()) {
                runCatching {
                    synchronized(ioLock) {
                        if (running.get()) handleEosEvents(transaction(EOS_EVENT_CHECK).data)
                    }
                }.onFailure { error ->
                    if (running.get()) log("canon_ptp event check failed: ${error.message}")
                }
                if (running.get()) Thread.sleep(EVENT_PERIOD_MS)
            }
        }, "FlyPhotoCanonPtpEvents").also { it.start() }
    }

    private fun handleEosEvents(payload: ByteArray) {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 8) {
            val eventStart = buffer.position()
            val eventLength = buffer.int
            val eventCode = buffer.int
            if (eventLength < 8 || eventLength > buffer.remaining() + 8) {
                log("canon_ptp malformed EOS event length=$eventLength")
                return
            }
            if ((eventCode == EOS_OBJECT_ADDED || eventCode == EOS_DIR_ITEM_CREATED) && buffer.remaining() >= 10) {
                val handle = buffer.int
                buffer.int // storage id; ObjectInfo remains the source of truth.
                buffer.short // object format; ObjectInfo remains the source of truth.
                getObjectInfo(handle)?.takeIf { it.isPhoto }?.let { info ->
                    publishPhoto(info.toPhoto(handle))
                }
            }
            buffer.position((eventStart + eventLength).coerceAtMost(buffer.limit()))
        }
    }

    private fun publishPhoto(photo: CanonPtpPhoto) {
        log("canon_ptp photoAdded id=${photo.id} name=${photo.fileName}")
        onPhotoAdded(photo)
        synchronized(captureListeners) { captureListeners.toList() }.forEach { it.complete(photo) }
    }

    private fun getObjectInfo(handle: Int): CanonPtpObjectInfo? {
        val data = transaction(GET_OBJECT_INFO, intArrayOf(handle)).data
        return CanonPtpObjectInfo.decode(data)
    }

    private fun downloadObject(handle: Int, output: FileOutputStream) {
        writeCommand(GET_OBJECT, intArrayOf(handle))
        var responseReceived = false
        while (!responseReceived) {
            val header = readHeader()
            when (header.type) {
                CONTAINER_DATA -> {
                    check(header.length >= CONTAINER_HEADER_SIZE) { "佳能返回了无效照片数据包" }
                    copyToStream(header.length - CONTAINER_HEADER_SIZE, output)
                }
                CONTAINER_RESPONSE -> {
                    discard(header.length - CONTAINER_HEADER_SIZE)
                    check(header.code == RESPONSE_OK) { "佳能下载失败: ${responseName(header.code)}" }
                    responseReceived = true
                }
                else -> {
                    discard(header.length - CONTAINER_HEADER_SIZE)
                    throw IllegalStateException("佳能下载收到未知 PTP 包 type=${header.type}")
                }
            }
        }
    }

    private fun transaction(operation: Int, parameters: IntArray = intArrayOf()): PtpResult {
        writeCommand(operation, parameters)
        var data = ByteArray(0)
        while (true) {
            val container = readContainer()
            when (container.type) {
                CONTAINER_DATA -> data = container.payload
                CONTAINER_RESPONSE -> {
                    check(container.code == RESPONSE_OK) {
                        "佳能 PTP 0x${operation.toString(16)} 失败: ${responseName(container.code)}"
                    }
                    return PtpResult(data)
                }
                else -> throw IllegalStateException("佳能 PTP 收到未知包 type=${container.type}")
            }
        }
    }

    private fun writeCommand(operation: Int, parameters: IntArray) {
        val packet = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + parameters.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(packet.capacity())
        packet.putShort(CONTAINER_COMMAND.toShort())
        packet.putShort(operation.toShort())
        packet.putInt(transactionId++)
        parameters.forEach(packet::putInt)
        val packetLength = packet.capacity()
        val written = requireNotNull(bulkOut).let { endpoint ->
            connection.bulkTransfer(endpoint, packet.array(), packetLength, USB_TIMEOUT_MS)
        }
        check(written == packetLength) { "佳能 PTP 命令写入失败: $written/$packetLength" }
    }

    private fun readContainer(): PtpContainer {
        val header = readHeader()
        check(header.length >= CONTAINER_HEADER_SIZE) { "佳能返回了无效 PTP 包长度=${header.length}" }
        check(header.length <= MAX_CONTAINER_SIZE) { "佳能 PTP 包过大=${header.length}" }
        val payload = ByteArray(header.length - CONTAINER_HEADER_SIZE)
        readExactly(payload)
        return PtpContainer(header.type, header.code, payload)
    }

    private fun readHeader(): PtpHeader {
        val bytes = ByteArray(CONTAINER_HEADER_SIZE)
        readExactly(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PtpHeader(buffer.int, buffer.short.toInt() and 0xffff, buffer.short.toInt() and 0xffff)
    }

    private fun readExactly(target: ByteArray) {
        var offset = 0
        val chunk = ByteArray((requireNotNull(bulkIn).maxPacketSize).coerceAtLeast(512))
        while (offset < target.size) {
            val wanted = minOf(chunk.size, target.size - offset)
            val count = connection.bulkTransfer(requireNotNull(bulkIn), chunk, wanted, USB_TIMEOUT_MS)
            check(count > 0) { "佳能 PTP 读取超时或失败: $count" }
            System.arraycopy(chunk, 0, target, offset, count)
            offset += count
        }
    }

    private fun copyToStream(length: Int, output: FileOutputStream) {
        val chunk = ByteArray((requireNotNull(bulkIn).maxPacketSize * 64).coerceAtLeast(16 * 1024))
        var remaining = length
        while (remaining > 0) {
            val count = connection.bulkTransfer(requireNotNull(bulkIn), chunk, minOf(chunk.size, remaining), USB_TIMEOUT_MS)
            check(count > 0) { "佳能照片传输中断: $count" }
            output.write(chunk, 0, count)
            remaining -= count
        }
    }

    private fun discard(length: Int) {
        if (length <= 0) return
        readExactly(ByteArray(length))
    }

    private fun selectPtpInterface() {
        var fallback: Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? = null
        for (index in 0 until device.interfaceCount) {
            val intf = device.getInterface(index)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (endpointIndex in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(endpointIndex)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input == null || output == null || intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) continue
            val candidate = Triple(intf, input, output)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                ptpInterface = intf; bulkIn = input; bulkOut = output
                return
            }
            if (fallback == null) fallback = candidate
        }
        fallback?.let { (intf, input, output) -> ptpInterface = intf; bulkIn = input; bulkOut = output }
        check(ptpInterface != null) { "未找到佳能 PTP 静态图像接口，请切换至 PC 遥控/PTP 模式后重试" }
    }

    private fun parsePhotoId(id: String): Int {
        return CanonPtpProtocol.parsePhotoId(id)
    }

    private fun responseName(code: Int) = "0x${code.toString(16)}"

    private data class PtpHeader(val length: Int, val type: Int, val code: Int)
    private data class PtpContainer(val type: Int, val code: Int, val payload: ByteArray)
    private data class PtpResult(val data: ByteArray)
    private class CaptureListener(
        private val latch: CountDownLatch,
        private val callback: (CanonPtpPhoto) -> Unit,
    ) { fun complete(photo: CanonPtpPhoto) { callback(photo); latch.countDown() } }

    private val captureListeners = mutableSetOf<CaptureListener>()

    companion object {
        private const val USB_TIMEOUT_MS = 30_000
        private const val EVENT_PERIOD_MS = 700L
        private const val MAX_CONTAINER_SIZE = 32 * 1024 * 1024
        private const val CONTAINER_HEADER_SIZE = 12
        private const val CONTAINER_COMMAND = 1
        private const val CONTAINER_DATA = 2
        private const val CONTAINER_RESPONSE = 3
        private const val RESPONSE_OK = 0x2001
        private const val OPEN_SESSION = 0x1002
        private const val CLOSE_SESSION = 0x1003
        private const val GET_STORAGE_IDS = 0x1004
        private const val GET_OBJECT_HANDLES = 0x1007
        private const val GET_OBJECT_INFO = 0x1008
        private const val GET_OBJECT = 0x1009
        private const val EOS_TAKE_PICTURE = 0x910f
        private const val EOS_SET_PC_CONNECT_MODE = 0x9114
        private const val EOS_SET_EVENT_MODE = 0x9115
        private const val EOS_EVENT_CHECK = 0x9116
        private const val EOS_OBJECT_ADDED = 0xc181
        private const val EOS_DIR_ITEM_CREATED = 0xc1a7
        internal const val PHOTO_ID_PREFIX = "canon-ptp:"
    }
}

internal data class CanonPtpPhoto(
    val id: String,
    val folder: String,
    val fileName: String,
    val sizeMb: Int,
    val format: String,
    val shotAt: String,
)

private data class CanonPtpObjectInfo(
    val storageId: Int,
    val objectFormat: Int,
    val compressedSize: Long,
    val parentObject: Int,
    val fileName: String,
    val captureDate: String,
) {
    val isPhoto get() = fileName.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "jpe", "cr2", "cr3", "raw")

    fun toPhoto(handle: Int): CanonPtpPhoto {
        val extension = fileName.substringAfterLast('.', "JPG").uppercase()
        return CanonPtpPhoto(
            id = CanonPtpProtocol.photoId(handle),
            folder = "/ptp/${storageId.toUInt().toString(16)}/${parentObject.toUInt().toString(16)}",
            fileName = fileName,
            sizeMb = (compressedSize / (1024 * 1024)).toInt(),
            format = extension,
            shotAt = captureDate.ifBlank { "--:--:--" },
        )
    }

    companion object {
        fun decode(bytes: ByteArray): CanonPtpObjectInfo? = runCatching {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val storageId = buffer.int
            val format = buffer.short.toInt() and 0xffff
            buffer.short
            val size = buffer.int.toLong() and 0xffffffffL
            buffer.short; buffer.int; buffer.int; buffer.int; buffer.int; buffer.int
            val parent = buffer.int
            buffer.short; buffer.int; buffer.int
            val fileName = readPtpString(buffer)
            val captureDate = readPtpString(buffer)
            CanonPtpObjectInfo(storageId, format, size, parent, fileName, captureDate)
        }.getOrNull()

        private fun readPtpString(buffer: ByteBuffer): String {
            if (!buffer.hasRemaining()) return ""
            val length = buffer.get().toInt() and 0xff
            if (length == 0) return ""
            val characters = CharArray((length - 1).coerceAtLeast(0))
            characters.indices.forEach { index -> characters[index] = buffer.char }
            if (buffer.remaining() >= 2) buffer.char
            return String(characters)
        }
    }
}

internal object CanonPtpProtocol {
    fun photoId(handle: Int): String =
        "${CanonPtpBackend.PHOTO_ID_PREFIX}${handle.toUInt().toString(16)}"

    fun parsePhotoId(id: String): Int {
        require(id.startsWith(CanonPtpBackend.PHOTO_ID_PREFIX)) { "无效的佳能照片 ID" }
        return id.removePrefix(CanonPtpBackend.PHOTO_ID_PREFIX).toLongOrNull(16)?.toInt()
            ?: throw IllegalArgumentException("无效的佳能照片 ID")
    }

    fun decodeU32Array(bytes: ByteArray): IntArray {
        if (bytes.size < 4) return intArrayOf()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int.coerceAtMost(buffer.remaining() / 4)
        return IntArray(count) { buffer.int }
    }
}
