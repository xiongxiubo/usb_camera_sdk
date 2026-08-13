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
import java.util.concurrent.atomic.AtomicLong

/** A small, SDK-owned EOS PTP implementation. It deliberately has no UI/service dependencies. */
internal class CanonPtpBackend(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice,
    private val log: (String) -> Unit,
    private val onPhotoAdded: (CanonPtpPhoto) -> Unit,
) {
    private val ioLock = Any()
    private val running = AtomicBoolean(false)
    private val eventListening = AtomicBoolean(false)
    private val eventGeneration = AtomicLong(0)
    private var ptpInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private lateinit var bulkReader: CanonPtpBulkReader
    private var transactionId = 0
    private var eventThread: Thread? = null
    private val photoHandles = CanonPhotoHandleRegistry(
        maxAttempts = PHOTO_INFO_MAX_ATTEMPTS,
        maxAgeMs = PHOTO_INFO_MAX_AGE_MS,
        publishedLimit = PUBLISHED_HANDLE_LIMIT,
    )
    private val handleCatalog = CanonHandleCatalog()
    private val photoCache = mutableMapOf<Int, CanonPtpPhoto>()
    private val inspectedHandles = mutableSetOf<Int>()
    private var keepAliveSupported = true
    private var lastKeepAliveAtMs = 0L
    private var lastCatalogReconcileAtMs = 0L

    fun connect() {
        selectPtpInterface()
        val intf = requireNotNull(ptpInterface)
        val input = requireNotNull(bulkIn)
        val output = requireNotNull(bulkOut)
        bulkReader = CanonPtpBulkReader(AndroidBulkTransport(connection, input))
        log(
            "canon_ptp interface index=${intf.id} class=${intf.interfaceClass} " +
                "bulkInPacket=${input.maxPacketSize} bulkOutPacket=${output.maxPacketSize}",
        )
        check(connection.claimInterface(intf, true)) { "无法占用佳能 PTP USB 接口" }
        try {
            synchronized(ioLock) {
                transaction(OPEN_SESSION, intArrayOf(1))
                transaction(EOS_SET_PC_CONNECT_MODE, intArrayOf(1))
                transaction(EOS_SET_EVENT_MODE, intArrayOf(1))
                val snapshot = readHandleSnapshot()
                replaceCatalogBaseline(snapshot)
                log(
                    "canon_ptp data probe ok storageCount=${snapshot.storageIds.size} " +
                        "baselineHandles=${snapshot.allHandles.size}",
                )
                keepDeviceOnIfDue(force = true)
            }
            running.set(true)
            log("canon_ptp connected device=${device.deviceName}")
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun startMediaEventListening() {
        check(running.get()) { "佳能 PTP 未连接" }
        if (!eventListening.compareAndSet(false, true)) return
        val generation = eventGeneration.incrementAndGet()
        log("canon_ptp event listening start")
        startEventLoop(generation)
    }

    fun stopMediaEventListening() {
        if (!eventListening.getAndSet(false)) return
        eventGeneration.incrementAndGet()
        log("canon_ptp event listening stop requested")
        val thread = eventThread
        if (thread != null && thread != Thread.currentThread()) {
            runCatching { thread.join(EVENT_STOP_TIMEOUT_MS) }
        }
        if (thread?.isAlive == true) {
            log("canon_ptp event listening stop timed out")
        } else {
            eventThread = null
            log("canon_ptp event listening stopped")
        }
    }

    fun close() {
        stopMediaEventListening()
        running.set(false)
        synchronized(ioLock) {
            runCatching { transaction(CLOSE_SESSION, timeoutMs = CLOSE_TIMEOUT_MS) }
                .onFailure { error -> log("canon_ptp close session failed: ${error.message}") }
            bulkReader.reset()
        }
        ptpInterface?.let { intf -> runCatching { connection.releaseInterface(intf) } }
        ptpInterface = null
        bulkIn = null
        bulkOut = null
        photoHandles.clear()
        handleCatalog.clear()
        photoCache.clear()
        inspectedHandles.clear()
        keepAliveSupported = true
        lastKeepAliveAtMs = 0L
        log("canon_ptp closed device=${device.deviceName}")
    }

    fun listPhotos(): List<CanonPtpPhoto> = synchronized(ioLock) {
        check(running.get()) { "佳能 PTP 未连接" }
        val snapshot = readHandleSnapshot()
        updateCatalogForFullList(snapshot)
        val photos = mutableListOf<CanonPtpPhoto>()
        var inspectedSinceMaintenance = 0
        snapshot.handlesByStorage.values.forEach { handles ->
            handles.forEach { handle ->
                photoCache[handle]?.let { cached ->
                    photos += cached
                    return@forEach
                }
                if (inspectedHandles.contains(handle)) return@forEach
                val info = getObjectInfo(handle)
                inspectedHandles += handle
                info?.takeIf { it.isPhoto }?.toPhoto(handle)?.let { photo ->
                    photoCache[handle] = photo
                    photos += photo
                }
                inspectedSinceMaintenance += 1
                if (inspectedSinceMaintenance >= FULL_SCAN_MAINTENANCE_BATCH) {
                    performLongOperationMaintenance()
                    inspectedSinceMaintenance = 0
                }
            }
        }
        performLongOperationMaintenance()
        log(
            "canon_ptp full list complete handles=${snapshot.allHandles.size} " +
                "photos=${photos.size} cached=${photoCache.size}",
        )
        photos.sortedByDescending { it.shotAt }
    }

    fun listNewPhotos(): List<CanonPtpPhoto> = synchronized(ioLock) {
        check(running.get()) { "佳能 PTP 未连接" }
        val snapshot = readHandleSnapshot()
        if (!handleCatalog.hasSameStorage(snapshot.storageIds)) {
            replaceCatalogBaseline(snapshot)
            log(
                "canon_ptp storage baseline reset storageCount=${snapshot.storageIds.size} " +
                    "handles=${snapshot.allHandles.size}",
            )
            keepDeviceOnIfDue()
            return@synchronized emptyList()
        }
        val newHandles = handleCatalog.reconcile(snapshot.allHandles)
        val photos = newHandles.mapNotNull { handle ->
            val info = runCatching { getObjectInfo(handle) }.getOrElse { error ->
                if (isTransientPhotoInfoError(error)) {
                    photoHandles.offer(handle, System.currentTimeMillis())
                } else {
                    inspectedHandles += handle
                    log("canon_ptp incremental photo info failed handle=${handle.hex()}: ${error.message}")
                }
                return@mapNotNull null
            }
            inspectedHandles += handle
            info?.takeIf { it.isPhoto }?.toPhoto(handle)?.also { photo ->
                photoCache[handle] = photo
                photoHandles.markPublished(handle)
            }
        }
        keepDeviceOnIfDue()
        if (newHandles.isNotEmpty()) {
            log(
                "canon_ptp incremental handles=${newHandles.size} photos=${photos.size}",
            )
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
            runCatching { destination.delete() }
            check(temporary.renameTo(destination)) { "无法完成佳能照片写入" }
            destination.absolutePath
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun startEventLoop(generation: Long) {
        eventThread = Thread({
            while (isActiveEventGeneration(generation)) {
                runCatching {
                    synchronized(ioLock) {
                        if (isActiveEventGeneration(generation)) {
                            val eventData = transaction(
                                EOS_EVENT_CHECK,
                                timeoutMs = EVENT_USB_TIMEOUT_MS,
                            ).data
                            if (!isActiveEventGeneration(generation)) return@synchronized
                            handleEosEvents(eventData)
                            if (System.currentTimeMillis() - lastCatalogReconcileAtMs >= CATALOG_RECONCILE_INTERVAL_MS) {
                                runCatching { reconcileHandleCatalog() }
                                    .onFailure { error -> log("canon_ptp event catalog reconcile failed: ${error.message}") }
                                lastCatalogReconcileAtMs = System.currentTimeMillis()
                            }
                            resolvePendingPhotos()
                            keepDeviceOnIfDue()
                        }
                    }
                }.onFailure { error ->
                    if (isActiveEventGeneration(generation)) {
                        log("canon_ptp event check failed: ${error.message}")
                    }
                }
                if (isActiveEventGeneration(generation)) Thread.sleep(EVENT_PERIOD_MS)
            }
            if (Thread.currentThread() == eventThread) eventThread = null
        }, "FlyPhotoCanonPtpEvents").also { it.start() }
    }

    private fun isActiveEventGeneration(generation: Long): Boolean {
        return running.get() && eventListening.get() && eventGeneration.get() == generation
    }

    private fun handleEosEvents(payload: ByteArray) {
        val batch = CanonEosEventParser.decode(payload)
        batch.malformedLength?.let { length ->
            log("canon_ptp malformed EOS event length=$length")
        }
        val now = System.currentTimeMillis()
        batch.events.forEach { event ->
            if (event.code != 0) {
                log(
                    "canon_ptp EOS event code=${event.code.hex()} length=${event.length}" +
                        (event.photo?.let { " handle=${it.handle.hex()} name=${it.fileName}" } ?: ""),
                )
            }
            if (event.code == CanonEosEventParser.WILL_SOON_SHUTDOWN) {
                keepDeviceOnIfDue(force = true)
                return@forEach
            }
            if (event.code != CanonEosEventParser.OBJECT_ADDED &&
                event.code != CanonEosEventParser.OBJECT_ADDED_EX &&
                event.code != CanonEosEventParser.OBJECT_ADDED_EX_64
            ) {
                return@forEach
            }
            val eosPhoto = event.photo
            val handle = eosPhoto?.handle ?: return@forEach
            handleCatalog.observe(handle)
            if (photoCache.containsKey(handle)) return@forEach
            if (!photoHandles.offer(handle, now)) return@forEach
            val photo = eosPhoto.toPhotoOrNull()
            if (photo == null) {
                log("canon_ptp event queued handle=${handle.hex()} for ObjectInfo resolution")
                return@forEach
            }
            inspectedHandles += handle
            photoCache[handle] = photo
            photoHandles.markPublished(handle)
            publishPhoto(photo)
        }
    }

    private fun resolvePendingPhotos() {
        val now = System.currentTimeMillis()
        photoHandles.pendingHandles().forEach { handle ->
            val info = runCatching { getObjectInfo(handle) }.getOrElse { error ->
                val failure = photoHandles.recordFailure(
                    handle = handle,
                    nowMs = now,
                    transient = isTransientPhotoInfoError(error),
                )
                if (!failure.willRetry) {
                    log(
                        "canon_ptp photo info failed handle=${handle.toUInt().toString(16)} " +
                            "attempts=${failure.attempts} error=${error.message}",
                    )
                }
                return@forEach
            }
            if (info == null || !info.isPhoto) {
                log(
                    "canon_ptp object ignored handle=${handle.hex()} " +
                        "fileName=${info?.fileName.orEmpty()} format=${info?.objectFormat ?: 0}",
                )
                photoHandles.discard(handle)
                inspectedHandles += handle
                return@forEach
            }
            val photo = info.toPhoto(handle)
            handleCatalog.observe(handle)
            inspectedHandles += handle
            photoCache[handle] = photo
            photoHandles.markPublished(handle)
            publishPhoto(photo)
        }
    }

    private fun reconcileHandleCatalog() {
        val snapshot = readHandleSnapshot()
        if (!handleCatalog.hasSameStorage(snapshot.storageIds)) {
            replaceCatalogBaseline(snapshot)
            log("canon_ptp event catalog baseline reset storages=${snapshot.storageIds.size} handles=${snapshot.allHandles.size}")
            return
        }
        val newHandles = handleCatalog.reconcile(snapshot.allHandles)
        if (newHandles.isNotEmpty()) {
            log("canon_ptp event catalog discovered handles=${newHandles.size}")
        }
        val now = System.currentTimeMillis()
        newHandles.forEach { handle ->
            if (photoCache.containsKey(handle) || !photoHandles.offer(handle, now)) return@forEach
        }
    }

    private fun CanonEosPhotoEvent.toPhotoOrNull(): CanonPtpPhoto? {
        if (handle == 0 || fileName.isBlank()) return null
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in PHOTO_EXTENSIONS) return null
        return CanonPtpPhoto(
            id = CanonPtpProtocol.photoId(handle),
            folder = "/ptp/${storageId.toUInt().toString(16)}/${parentObject.toUInt().toString(16)}",
            fileName = fileName,
            sizeMb = (compressedSize / (1024 * 1024)).toInt(),
            format = extension.uppercase(),
            shotAt = "--:--:--",
        )
    }

    private fun readHandleSnapshot(): HandleSnapshot {
        val storageIds = CanonPtpProtocol.decodeU32Array(transaction(GET_STORAGE_IDS).data)
        val handlesByStorage = linkedMapOf<Int, IntArray>()
        storageIds.forEach { storageId ->
            handlesByStorage[storageId] = CanonPtpProtocol.decodeU32Array(
                transaction(GET_OBJECT_HANDLES, intArrayOf(storageId, 0, 0)).data,
            )
        }
        return HandleSnapshot(handlesByStorage)
    }

    private fun replaceCatalogBaseline(snapshot: HandleSnapshot) {
        handleCatalog.replaceBaseline(snapshot.storageIds, snapshot.allHandles)
        photoCache.clear()
        inspectedHandles.clear()
        photoHandles.clear()
    }

    private fun updateCatalogForFullList(snapshot: HandleSnapshot) {
        if (!handleCatalog.hasSameStorage(snapshot.storageIds)) {
            replaceCatalogBaseline(snapshot)
            return
        }
        handleCatalog.reconcile(snapshot.allHandles)
        photoCache.keys.retainAll(snapshot.allHandles)
        inspectedHandles.retainAll(snapshot.allHandles)
    }

    private fun performLongOperationMaintenance() {
        runCatching {
            val eventData = transaction(EOS_EVENT_CHECK, timeoutMs = EVENT_USB_TIMEOUT_MS).data
            handleEosEvents(eventData)
            resolvePendingPhotos()
        }.onFailure { error ->
            log("canon_ptp full scan event maintenance failed: ${error.message}")
        }
        keepDeviceOnIfDue()
    }

    private fun keepDeviceOnIfDue(force: Boolean = false) {
        if (!keepAliveSupported || !running.get() && !force) return
        val now = System.currentTimeMillis()
        if (!force && now - lastKeepAliveAtMs < KEEP_ALIVE_INTERVAL_MS) return
        lastKeepAliveAtMs = now
        runCatching { transaction(EOS_KEEP_DEVICE_ON, timeoutMs = KEEP_ALIVE_TIMEOUT_MS) }
            .onSuccess {
                if (force) log("canon_ptp keepalive ok")
            }
            .onFailure { error ->
                if (error is CanonPtpResponseException &&
                    error.responseCode == RESPONSE_OPERATION_NOT_SUPPORTED
                ) {
                    keepAliveSupported = false
                    log("canon_ptp keepalive unsupported; disabled for session")
                } else {
                    log("canon_ptp keepalive failed: ${error.message}")
                }
            }
    }

    private fun isTransientPhotoInfoError(error: Throwable): Boolean {
        return error is CanonPtpTransportException ||
            error is CanonPtpResponseException &&
            error.responseCode in setOf(RESPONSE_DEVICE_BUSY, RESPONSE_INVALID_OBJECT_HANDLE)
    }

    private fun publishPhoto(photo: CanonPtpPhoto) {
        log("canon_ptp photoAdded id=${photo.id} name=${photo.fileName}")
        onPhotoAdded(photo)
        synchronized(captureListeners) { captureListeners.toList() }.forEach { it.complete(photo) }
    }

    private fun getObjectInfo(handle: Int): CanonPtpObjectInfo? {
        val data = transaction(GET_OBJECT_INFO, intArrayOf(handle)).data
        val info = CanonPtpObjectInfo.decode(data)
        log(
            "canon_ptp object info handle=${handle.hex()} " +
                "fileName=${info?.fileName.orEmpty()} size=${info?.compressedSize ?: 0} " +
                "format=${info?.objectFormat ?: 0}",
        )
        return info
    }

    private fun downloadObject(handle: Int, output: FileOutputStream) {
        log("canon_ptp download start handle=${handle.hex()}")
        val expectedTransactionId = writeCommand(GET_OBJECT, intArrayOf(handle))
        var responseReceived = false
        while (!responseReceived) {
            val header = readHeader(USB_TIMEOUT_MS)
            validateHeader(header, GET_OBJECT, expectedTransactionId, allowLargeData = true)
            when (header.type) {
                CONTAINER_DATA -> {
                    check(header.length >= CONTAINER_HEADER_SIZE) { "佳能返回了无效照片数据包" }
                    bulkReader.copyExactly(
                        header.length - CONTAINER_HEADER_SIZE,
                        output,
                        USB_TIMEOUT_MS,
                    )
                }
                CONTAINER_RESPONSE -> {
                    discard(header.length - CONTAINER_HEADER_SIZE, USB_TIMEOUT_MS)
                    if (header.code != RESPONSE_OK) {
                        throw CanonPtpResponseException(GET_OBJECT, header.code)
                    }
                    responseReceived = true
                }
                else -> {
                    discard(header.length - CONTAINER_HEADER_SIZE, USB_TIMEOUT_MS)
                    throw IllegalStateException("佳能下载收到未知 PTP 包 type=${header.type}")
                }
            }
        }
        log("canon_ptp download complete handle=${handle.hex()}")
    }

    private fun transaction(
        operation: Int,
        parameters: IntArray = intArrayOf(),
        timeoutMs: Int = USB_TIMEOUT_MS,
    ): PtpResult {
        val expectedTransactionId = writeCommand(operation, parameters, timeoutMs)
        var data = ByteArray(0)
        while (true) {
            val container = readContainer(operation, expectedTransactionId, timeoutMs)
            when (container.type) {
                CONTAINER_DATA -> data = container.payload
                CONTAINER_RESPONSE -> {
                    if (container.code != RESPONSE_OK) {
                        throw CanonPtpResponseException(operation, container.code)
                    }
                    return PtpResult(data)
                }
                else -> throw IllegalStateException("佳能 PTP 收到未知包 type=${container.type}")
            }
        }
    }

    private fun writeCommand(
        operation: Int,
        parameters: IntArray,
        timeoutMs: Int = USB_TIMEOUT_MS,
    ): Int {
        val currentTransactionId = transactionId++
        val packet = ByteBuffer.allocate(CONTAINER_HEADER_SIZE + parameters.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(packet.capacity())
        packet.putShort(CONTAINER_COMMAND.toShort())
        packet.putShort(operation.toShort())
        packet.putInt(currentTransactionId)
        parameters.forEach(packet::putInt)
        val packetLength = packet.capacity()
        val written = requireNotNull(bulkOut).let { endpoint ->
            connection.bulkTransfer(endpoint, packet.array(), packetLength, timeoutMs)
        }
        if (written != packetLength) {
            throw CanonPtpTransportException(
                "佳能 PTP 命令写入失败 op=${operation.hex()} tx=$currentTransactionId: " +
                    "$written/$packetLength",
            )
        }
        return currentTransactionId
    }

    private fun readContainer(
        operation: Int,
        expectedTransactionId: Int,
        timeoutMs: Int,
    ): PtpContainer {
        val header = readHeader(timeoutMs)
        validateHeader(header, operation, expectedTransactionId)
        val payload = ByteArray(header.length - CONTAINER_HEADER_SIZE)
        bulkReader.readExactly(payload, timeoutMs)
        return PtpContainer(header.type, header.code, payload)
    }

    private fun readHeader(timeoutMs: Int): PtpHeader {
        val bytes = ByteArray(CONTAINER_HEADER_SIZE)
        bulkReader.readExactly(bytes, timeoutMs)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return PtpHeader(
            length = buffer.int,
            type = buffer.short.toInt() and 0xffff,
            code = buffer.short.toInt() and 0xffff,
            transactionId = buffer.int,
        )
    }

    private fun validateHeader(
        header: PtpHeader,
        operation: Int,
        expectedTransactionId: Int,
        allowLargeData: Boolean = false,
    ) {
        check(header.length >= CONTAINER_HEADER_SIZE) {
            "佳能返回了无效 PTP 包长度=${header.length} op=${operation.hex()}"
        }
        if (!allowLargeData || header.type != CONTAINER_DATA) {
            check(header.length <= MAX_CONTAINER_SIZE) {
                "佳能 PTP 包过大=${header.length} op=${operation.hex()}"
            }
        }
        check(header.transactionId == expectedTransactionId) {
            "佳能 PTP 事务不匹配 op=${operation.hex()} " +
                "expected=$expectedTransactionId actual=${header.transactionId}"
        }
    }

    private fun discard(length: Int, timeoutMs: Int) {
        if (length <= 0) return
        bulkReader.readExactly(ByteArray(length), timeoutMs)
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

    private fun Int.hex() = "0x${toString(16)}"

    private data class PtpHeader(
        val length: Int,
        val type: Int,
        val code: Int,
        val transactionId: Int,
    )
    private data class PtpContainer(val type: Int, val code: Int, val payload: ByteArray)
    private data class PtpResult(val data: ByteArray)
    private data class HandleSnapshot(
        val handlesByStorage: Map<Int, IntArray>,
    ) {
        val storageIds: Set<Int> = handlesByStorage.keys.toSet()
        val allHandles: Set<Int> = buildSet {
            handlesByStorage.values.forEach { handles -> addAll(handles.toList()) }
        }
    }
    private class CaptureListener(
        private val latch: CountDownLatch,
        private val callback: (CanonPtpPhoto) -> Unit,
    ) { fun complete(photo: CanonPtpPhoto) { callback(photo); latch.countDown() } }

    private val captureListeners = mutableSetOf<CaptureListener>()

    private class AndroidBulkTransport(
        private val connection: UsbDeviceConnection,
        private val endpoint: UsbEndpoint,
    ) : CanonPtpBulkTransport {
        override val maxPacketSize: Int = endpoint.maxPacketSize

        override fun read(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            return connection.bulkTransfer(endpoint, buffer, length, timeoutMs)
        }
    }

    companion object {
        private const val USB_TIMEOUT_MS = 30_000
        private const val EVENT_USB_TIMEOUT_MS = 2_500
        private const val CLOSE_TIMEOUT_MS = 2_000
        private const val EVENT_PERIOD_MS = 700L
        private const val CATALOG_RECONCILE_INTERVAL_MS = 2_000L
        private const val EVENT_STOP_TIMEOUT_MS = 3_000L
        private const val KEEP_ALIVE_INTERVAL_MS = 20_000L
        private const val KEEP_ALIVE_TIMEOUT_MS = 2_500
        private const val FULL_SCAN_MAINTENANCE_BATCH = 50
        private const val PHOTO_INFO_MAX_ATTEMPTS = 3
        private const val PHOTO_INFO_MAX_AGE_MS = 5_000L
        private const val PUBLISHED_HANDLE_LIMIT = 512
        private const val MAX_CONTAINER_SIZE = 32 * 1024 * 1024
        private const val CONTAINER_HEADER_SIZE = 12
        private const val CONTAINER_COMMAND = 1
        private const val CONTAINER_DATA = 2
        private const val CONTAINER_RESPONSE = 3
        private const val RESPONSE_OK = 0x2001
        private const val RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005
        private const val RESPONSE_INVALID_OBJECT_HANDLE = 0x2009
        private const val RESPONSE_DEVICE_BUSY = 0x2019
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
        private const val EOS_KEEP_DEVICE_ON = 0x911d
        private val PHOTO_EXTENSIONS = setOf(
            "jpg", "jpeg", "jpe", "cr2", "cr3", "raw",
            "mp4", "mov", "m4v", "avi", "mts", "m2ts", "webm",
        )
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
    val isPhoto get() = fileName.substringAfterLast('.', "").lowercase() in setOf(
        "jpg", "jpeg", "jpe", "cr2", "cr3", "raw",
        "mp4", "mov", "m4v", "avi", "mts", "m2ts", "webm",
    )

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

internal class CanonPtpResponseException(
    val operation: Int,
    val responseCode: Int,
) : IllegalStateException(
    "佳能 PTP 0x${operation.toString(16)} 失败: 0x${responseCode.toString(16)}",
)
