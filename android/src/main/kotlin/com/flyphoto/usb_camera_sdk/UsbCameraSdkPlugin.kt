package com.flyphoto.usb_camera_sdk

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

class UsbCameraSdkPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware {
    private val methodChannelName = "flyphoto/camera_usb"
    private val eventChannelName = "flyphoto/camera_usb_events"
    private val usbPermissionAction = "com.flyphoto.usb_camera_sdk.USB_PERMISSION"

    private lateinit var applicationContext: Context
    private var activity: Activity? = null
    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var usbManager: UsbManager? = null
    private val bridge = GPhoto2Bridge()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraDispatcher = CameraOperationDispatcher()
    private val mediaEventListening = AtomicBoolean(false)
    private val pendingMediaEventLock = Any()
    private val cameraOperationLock = Any()
    private val pendingMediaEvents = mutableListOf<Map<String, Any?>>()
    private val mediaEventPollScheduled = AtomicBoolean(false)
    private val genericMediaCatalog = mutableSetOf<String>()
    private var lastGenericMediaPollAtMs = 0L
    private var eventSink: EventChannel.EventSink? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    @Volatile
    private var activeConnection: UsbDeviceConnection? = null
    @Volatile
    private var activeDevice: UsbDevice? = null
    private var canonPtpBackend: CanonPtpBackend? = null
    private var activeBackend = "libgphoto2"
    private var receiverRegistered = false
    private var downloadsLogUri: Uri? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> handlePermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val attached = intent.usbDevice()
                    appendCameraLog(
                        "usb attached device=${attached?.deviceName} " +
                            "vendor=${attached?.vendorId} product=${attached?.productId}",
                    )
                    emitEvent("deviceAttached", attached?.toMap())
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice()
                    appendCameraLog(
                        "usb detached device=${detached?.deviceName} " +
                            "active=${activeDevice?.deviceName} backend=$activeBackend",
                    )
                    if (detached?.deviceName == activeDevice?.deviceName) {
                        mediaEventListening.set(false)
                        cameraDispatcher.execute(::disconnectCamera)
                    }
                    emitEvent("deviceDetached", detached?.toMap())
                }
            }
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        methodChannel = MethodChannel(binding.binaryMessenger, methodChannelName).also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(binding.binaryMessenger, eventChannelName).also {
            it.setStreamHandler(this)
        }
        registerUsbReceiver()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        mediaEventListening.set(false)
        cameraDispatcher.execute(::disconnectCamera)
        cameraDispatcher.shutdown()
        unregisterUsbReceiver()
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
        eventSink = null
        usbManager = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        val mainResult = MainThreadResult(result, mainHandler)
        when (call.method) {
            "listDevices" -> result.success(listDevices())
            "requestPermission" -> requestPermission(call.argument<String>("deviceName"), result)
            "connect" -> cameraDispatcher.execute {
                connect(call.argument<String>("deviceName"), mainResult)
            }
            "disconnect" -> {
                mediaEventListening.set(false)
                cameraDispatcher.execute {
                    disconnectCamera()
                    mainResult.success(null)
                }
            }
            "releaseCameraControl" -> {
                mediaEventListening.set(false)
                cameraDispatcher.execute {
                    releaseCameraControl()
                    mainResult.success(null)
                }
            }
            "capture" -> cameraDispatcher.execute { capture(mainResult) }
            "listPhotos" -> cameraDispatcher.execute {
                mainResult.success(listPhotos(call.argument<String>("folder") ?: "/"))
            }
            "listMedia" -> cameraDispatcher.execute {
                mainResult.success(listMedia(call.argument<String>("folder") ?: "/"))
            }
            "scanMedia" -> cameraDispatcher.execute {
                mainResult.success(scanMedia(call.argument<String>("folder") ?: "/").toMap())
            }
            "listNewPhotos" -> cameraDispatcher.execute {
                mainResult.success(listNewPhotos(call.argument<String>("folder") ?: "/"))
            }
            "listNewMedia" -> cameraDispatcher.execute {
                mainResult.success(listNewMedia(call.argument<String>("folder") ?: "/"))
            }
            "scanNewMedia" -> cameraDispatcher.execute {
                mainResult.success(scanNewMedia(call.argument<String>("folder") ?: "/").toMap())
            }
            "drainPhotoEvents" -> result.success(drainPhotoEvents())
            "drainMediaEvents" -> result.success(drainMediaEvents())
            "startPhotoEventListening" -> {
                cameraDispatcher.execute { startMediaEventListening(mainResult) }
            }
            "startMediaEventListening" -> {
                cameraDispatcher.execute { startMediaEventListening(mainResult) }
            }
            "stopPhotoEventListening" -> {
                mediaEventListening.set(false)
                cameraDispatcher.execute {
                    stopMediaEventListening()
                    mainResult.success(null)
                }
            }
            "stopMediaEventListening" -> {
                mediaEventListening.set(false)
                cameraDispatcher.execute {
                    stopMediaEventListening()
                    mainResult.success(null)
                }
            }
            "downloadPhoto" -> cameraDispatcher.execute {
                downloadPhoto(
                    folder = call.argument<String>("folder") ?: "/",
                    name = call.argument<String>("name"),
                    id = call.argument<String>("id"),
                    result = mainResult,
                )
            }
            "downloadMedia" -> cameraDispatcher.execute {
                downloadPhoto(
                    folder = call.argument<String>("folder") ?: "/",
                    name = call.argument<String>("name"),
                    id = call.argument<String>("id"),
                    result = mainResult,
                )
            }
            "getCameraLog" -> result.success(internalCameraLogFile().readTextIfExists())
            "getCameraLogPath" -> result.success(internalCameraLogFile().absolutePath)
            "exportCameraLog" -> result.success(exportCameraLogToDownloads())
            "appendCameraLog" -> {
                cameraDispatcher.execute {
                    appendCameraLog(call.argument<String>("message") ?: "")
                    mainResult.success(null)
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(usbPermissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            applicationContext.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        runCatching { applicationContext.unregisterReceiver(usbReceiver) }
        receiverRegistered = false
    }

    private fun listDevices(): List<Map<String, Any?>> {
        return usbManager?.deviceList?.values?.map { it.toMap() }.orEmpty()
    }

    private fun requestPermission(deviceName: String?, result: MethodChannel.Result) {
        val manager = usbManager
        if (manager == null) {
            result.error("unsupported", "当前平台不支持 USB 相机", null)
            return
        }
        val device = findDevice(deviceName)
        if (device == null) {
            result.error("no_device", "未找到 USB 相机", null)
            return
        }
        if (manager.hasPermission(device)) {
            result.success(true)
            return
        }
        if (pendingPermissionResult != null) {
            result.error("permission_in_progress", "已有 USB 授权请求正在处理中", null)
            return
        }
        pendingPermissionResult = result
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val intent = PendingIntent.getBroadcast(applicationContext, 0, Intent(usbPermissionAction), flags)
        manager.requestPermission(device, intent)
    }

    private fun handlePermissionResult(intent: Intent) {
        val device = intent.usbDevice()
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        pendingPermissionResult?.success(granted)
        pendingPermissionResult = null
        emitEvent(
            if (granted) "permissionGranted" else "permissionDenied",
            device?.toMap(),
        )
    }

    private fun connect(deviceName: String?, result: MethodChannel.Result) {
        runCatching {
            connectCamera(deviceName, result)
        }.onFailure { error ->
            disconnectCamera()
            result.error("connect_crashed", error.message ?: "USB 相机连接异常", error.stackTraceToString())
        }
    }

    private fun connectCamera(deviceName: String?, result: MethodChannel.Result) {
        val manager = usbManager
        if (manager == null) {
            result.error("unsupported", "当前平台不支持 USB 相机", null)
            return
        }
        prepareCameraLogFile()
        appendCameraLog("========== connect start ==========")
        appendCameraLog("requestedDeviceName=$deviceName")
        val device = findDevice(deviceName)
        if (device == null) {
            appendCameraLog("no_device: ${manager.deviceList.values.map { it.toLogMap() }}")
            result.error("no_device", "未找到 USB 相机", null)
            return
        }
        appendCameraLog("device=${device.toLogMap()}")
        if (!manager.hasPermission(device)) {
            appendCameraLog("permission_denied")
            result.error("permission_denied", "没有 USB 设备权限", device.toMap())
            return
        }

        var connection: UsbDeviceConnection = manager.openDevice(device) ?: run {
            appendCameraLog("open_failed")
            result.error("open_failed", "无法打开 USB 设备", device.toMap())
            return
        }
        appendCameraLog("openDevice ok fd=${connection.fileDescriptor}")

        disconnectCamera()
        genericMediaCatalog.clear()
        lastGenericMediaPollAtMs = 0L
        activeConnection = connection
        activeDevice = device

        if (device.isCanon()) {
            val canonicalConnection = connection
            val canonConnected = runCatching {
                CanonPtpBackend(
                    connection = canonicalConnection,
                    device = device,
                    log = ::appendCameraLog,
                    onPhotoAdded = ::handleCanonPhotoAdded,
                ).also { backend ->
                    backend.connect()
                    canonPtpBackend = backend
                    activeBackend = "canon_ptp"
                }
            }
            if (canonConnected.isSuccess) {
                completeConnection(result, device, pluginDir = null)
                return
            }

            val canonError = canonConnected.exceptionOrNull()
            appendCameraLog("canon_ptp connect failed; falling back to libgphoto2: ${canonError?.message}")
            canonPtpBackend?.close()
            canonPtpBackend = null
            canonicalConnection.close()
            connection = manager.openDevice(device) ?: run {
                activeConnection = null
                activeDevice = null
                result.error("open_failed", "佳能 PTP 失败后无法重新打开 USB 设备", device.toMap())
                return
            }
            activeConnection = connection
            activeBackend = "libgphoto2_fallback"
        }

        val pluginDir = preparePluginDirectory()
        val tempDir = File(applicationContext.cacheDir, "gphoto2").apply { mkdirs() }.absolutePath
        bridge.nativeSetLogFile(internalCameraLogFile().absolutePath)
        appendCameraLog("pluginDir=$pluginDir")
        appendCameraLog("tempDir=$tempDir")
        appendCameraLog("logFile=${internalCameraLogFile().absolutePath}")
        appendCameraLog("pluginFiles=${File(pluginDir, "lib/libgphoto2_port/0.12.2").listFiles()?.map { it.name }}")
        val initResult = synchronized(cameraOperationLock) {
            bridge.nativeInit(pluginDir, tempDir)
        }
        appendCameraLog("nativeInit=$initResult")
        if (initResult != "ok") {
            appendCameraLog("native_init_failed=$initResult")
            val exportedPath = exportCameraLogToDownloads()
            appendCameraLog("exportedLog=$exportedPath")
            disconnectCamera()
            result.error("native_init_failed", initResult, null)
            return
        }

        val nativeResult = synchronized(cameraOperationLock) {
            bridge.nativeConnectCamera(
                connection.fileDescriptor,
                device.vendorId,
                device.productId,
                device.deviceName,
            )
        }
        appendCameraLog("nativeConnectCamera=$nativeResult")
        if (nativeResult != "ok") {
            appendCameraLog("native_connect_failed=$nativeResult")
            val exportedPath = exportCameraLogToDownloads()
            appendCameraLog("exportedLog=$exportedPath")
            disconnectCamera()
            result.error("native_connect_failed", nativeResult, device.toMap())
            return
        }
        completeConnection(result, device, pluginDir)
    }

    private fun completeConnection(
        result: MethodChannel.Result,
        device: UsbDevice,
        pluginDir: String?,
    ) {
        val isCanon = device.isCanon()
        val payload = device.toMap().toMutableMap().apply {
            put("model", device.productName ?: device.deviceName)
            if (pluginDir != null) put("pluginDir", pluginDir)
            put("logFile", internalCameraLogFile().absolutePath)
            put("exportedLogFile", cameraLogPath())
            put("isCanon", isCanon)
            put("backend", activeBackend)
            if (isCanon) {
                put(
                    "canonStrategy",
                    if (activeBackend == "canon_ptp") "custom_ptp" else "libgphoto2_fallback",
                )
            }
        }
        if (isCanon) {
            appendCameraLog("canon strategy=${payload["canonStrategy"]} backend=$activeBackend")
        }
        appendCameraLog("connect success payload=${payload.redactedForLog()}")
        emitEvent("connected", payload)
        result.success(payload)
    }

    private fun capture(result: MethodChannel.Result) {
        val start = System.currentTimeMillis()
        appendCameraLog("capture start")
        val canonBackend = canonPtpBackend
        if (canonBackend != null) {
            val photo = runCatching { canonBackend.capture() }.getOrElse { error ->
                result.error("capture_failed", error.message ?: "佳能拍摄失败", null)
                return
            }
            appendCameraLog("capture result=${photo.id} elapsedMs=${System.currentTimeMillis() - start}")
            result.success(photo.id)
            return
        }
        val nativeResult = synchronized(cameraOperationLock) { bridge.nativeCapture() }
        val elapsed = System.currentTimeMillis() - start
        appendCameraLog("capture result=$nativeResult elapsedMs=$elapsed")
        if (isCameraPath(nativeResult)) {
            result.success(nativeResult)
            return
        }
        appendCameraLog("capture failed message=$nativeResult")
        result.error(
            "capture_failed",
            nativeResult.ifBlank { "相机拍摄失败" },
            mapOf("elapsed_ms" to elapsed),
        )
    }

    private fun isCameraPath(value: String): Boolean {
        val text = value.trim()
        if (text.isEmpty()) return false
        if (text.contains("|") || text.contains("\n")) return false
        if (!text.contains("/")) return false
        val name = text.substringAfterLast('/')
        if (name.isBlank() || !name.contains('.')) return false
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf(
            "jpg", "jpeg", "jpe", "arw", "raw", "dng", "cr2", "cr3", "nef", "raf", "orf", "rw2", "pef", "srw", "x3f",
        )
    }

    private fun scanMedia(folder: String): CameraMediaScanPayload {
        canonPtpBackend?.let { backend ->
            return runCatching {
                val media = backend.listPhotos().map { photo -> photo.toMap() }
                CameraMediaScanPayload(
                    media = media,
                    state = if (media.isEmpty()) "empty" else "ready",
                    backend = activeBackend,
                    folderCount = media.map { it["folder"] }.distinct().size,
                    fileCount = media.size,
                )
            }.getOrElse { error ->
                appendCameraLog("canon_ptp listMedia failed: ${error.message}")
                CameraMediaScanPayload(
                    state = "failed",
                    backend = activeBackend,
                    errors = listOf(error.message ?: "佳能媒体扫描失败"),
                )
            }
        }
        return scanGenericMedia(folder)
    }

    private fun scanGenericMedia(folder: String): CameraMediaScanPayload {
        val (encodedFiles, reportValues) = synchronized(cameraOperationLock) {
            bridge.nativeListFiles(folder) to bridge.nativeGetLastMediaScanReport()
        }
        val files = encodedFiles.map { encoded ->
            val parts = encoded.split("|")
            mapOf(
                "id" to encoded,
                "folder" to (parts.getOrNull(0) ?: folder),
                "fileName" to (parts.getOrNull(1) ?: encoded),
                "sizeMb" to (parts.getOrNull(2)?.toIntOrNull() ?: 0),
                "format" to (parts.getOrNull(3) ?: parts.getOrNull(1)?.substringAfterLast('.', "JPG") ?: "JPG"),
                "shotAt" to (parts.getOrNull(4) ?: "--:--:--"),
                "mediaType" to mediaTypeForFileName(parts.getOrNull(1) ?: encoded),
                "mimeType" to mimeTypeForFileName(parts.getOrNull(1) ?: encoded),
            )
        }
        files.filter { it["mediaType"] == "unknown" }.forEach { file ->
            appendCameraLog(
                "media list ignored unknown file=${file["fileName"]} " +
                    "folder=${file["folder"]}",
            )
        }
        val report = NativeMediaScanReport.fromValues(reportValues)
        val supported = files.filter { it["mediaType"] != "unknown" }
        val rootFailed = report.rootFilesResult < 0 && report.rootFoldersResult < 0
        val sonyStorageUnavailable = activeDevice?.vendorId == SONY_VENDOR_ID &&
            report.discoveredFileCount == 0 && report.visitedFolderCount <= 1
        val state = when {
            supported.isNotEmpty() -> "ready"
            rootFailed -> "failed"
            sonyStorageUnavailable -> "storage_unavailable"
            else -> "empty"
        }
        appendCameraLog(
            "media scan state=$state backend=$activeBackend " +
                "folders=${report.visitedFolderCount} files=${report.discoveredFileCount} " +
                "supported=${supported.size} errors=${report.errors.size}",
        )
        report.errors.forEach { error -> appendCameraLog("media scan error=$error") }
        return CameraMediaScanPayload(
            media = supported,
            state = state,
            backend = activeBackend,
            folderCount = report.visitedFolderCount,
            fileCount = report.discoveredFileCount,
            errors = report.errors,
        )
    }

    private fun listMedia(folder: String): List<Map<String, Any?>> = scanMedia(folder).media

    private fun listPhotos(folder: String): List<Map<String, Any?>> =
        listMedia(folder).filter { it["mediaType"] == "image" }

    private fun scanNewMedia(folder: String): CameraMediaScanPayload {
        canonPtpBackend?.let { backend ->
            return runCatching {
                val media = backend.listNewPhotos().map { photo -> photo.toMap() }
                CameraMediaScanPayload(
                    media = media,
                    state = if (media.isEmpty()) "empty" else "ready",
                    backend = activeBackend,
                    folderCount = media.map { it["folder"] }.distinct().size,
                    fileCount = media.size,
                )
            }.getOrElse { error ->
                appendCameraLog("canon_ptp listNewMedia failed: ${error.message}")
                CameraMediaScanPayload(
                    state = "failed",
                    backend = activeBackend,
                    errors = listOf(error.message ?: "佳能增量媒体扫描失败"),
                )
            }
        }
        return scanGenericMedia(folder)
    }

    private fun listNewMedia(folder: String): List<Map<String, Any?>> =
        scanNewMedia(folder).media

    private fun listNewPhotos(folder: String): List<Map<String, Any?>> =
        listNewMedia(folder).filter { it["mediaType"] == "image" }

    private fun drainPhotoEvents(): List<Map<String, Any?>> {
        return synchronized(pendingMediaEventLock) {
            val events = pendingMediaEvents.filter { it["mediaType"] == "image" }
            pendingMediaEvents.clear()
            events
        }
    }

    private fun drainMediaEvents(): List<Map<String, Any?>> {
        return synchronized(pendingMediaEventLock) {
            val events = pendingMediaEvents.toList()
            pendingMediaEvents.clear()
            events
        }
    }

    private fun startMediaEventListening(result: MethodChannel.Result) {
        if (activeDevice == null || activeConnection == null) {
            result.error("not_connected", "USB 相机未连接", null)
            return
        }
        runCatching { startMediaEventListening() }
            .onSuccess { result.success(null) }
            .onFailure { error ->
                appendCameraLog("media event listening failed: ${error.message}")
                result.error(
                    "event_listening_failed",
                    error.message ?: "相机事件监听启动失败",
                    null,
                )
            }
    }

    private fun startMediaEventListening() {
        if (activeDevice == null || activeConnection == null) {
            appendCameraLog("media event listening skipped: not connected")
            return
        }
        if (!mediaEventListening.compareAndSet(false, true)) return
        appendCameraLog("media event listening start")
        canonPtpBackend?.let { backend ->
            runCatching { backend.startMediaEventListening() }.onFailure {
                mediaEventListening.set(false)
                throw it
            }
            return
        }
        runCatching { seedGenericMediaCatalog() }
            .onFailure { error ->
                appendCameraLog("generic media catalog baseline failed: ${error.message}")
            }
        scheduleMediaEventPoll()
    }

    private fun stopMediaEventListening() {
        val wasListening = mediaEventListening.getAndSet(false)
        canonPtpBackend?.stopMediaEventListening()
        if (wasListening) appendCameraLog("media event listening stop requested")
    }

    private fun scheduleMediaEventPoll() {
        if (!mediaEventListening.get() || canonPtpBackend != null) return
        if (!mediaEventPollScheduled.compareAndSet(false, true)) return
        cameraDispatcher.schedule(25) {
            mediaEventPollScheduled.set(false)
            if (!mediaEventListening.get() || canonPtpBackend != null) return@schedule
            val event = synchronized(cameraOperationLock) {
                bridge.nativeWaitForEvent(250)
            }
            if (mediaEventListening.get()) handleMediaEventResult(event)
            if (mediaEventListening.get() && canonPtpBackend == null &&
                System.currentTimeMillis() - lastGenericMediaPollAtMs >= GENERIC_MEDIA_POLL_INTERVAL_MS
            ) {
                runCatching { pollGenericMediaCatalog() }
                    .onFailure { error ->
                        appendCameraLog("generic media catalog poll failed: ${error.message}")
                    }
                lastGenericMediaPollAtMs = System.currentTimeMillis()
            }
            // Re-submit instead of looping while holding/reacquiring the lock. Any
            // foreground list/download request already queued runs before this poll.
            scheduleMediaEventPoll()
        }
    }

    private fun handleMediaEventResult(event: String) {
        when {
            event == "timeout" || event == "captureComplete" || event == "unknown" -> return
            event == "disconnected" -> {
                appendCameraLog("media event disconnected")
                mediaEventListening.set(false)
            }
            event.startsWith("fileAdded|") -> {
                val parts = event.split("|", limit = 3)
                val folder = parts.getOrNull(1)?.ifBlank { "/" } ?: "/"
                val name = parts.getOrNull(2).orEmpty()
                appendCameraLog("media event fileAdded folder=$folder name=$name")
                if (name.isBlank()) return
                val payload = mediaPayload(folder, name)
                if (payload["mediaType"] == "unknown") {
                    appendCameraLog("media event ignored unknown file=$name folder=$folder")
                    return
                }
                publishGenericMedia(payload)
            }
            event.startsWith("folderAdded|") -> appendCameraLog("media event $event")
            event.startsWith("error|") -> {
                appendCameraLog("media event $event")
                if (event.contains("Could not find the requested device on the USB port")) {
                    mediaEventListening.set(false)
                }
            }
            else -> appendCameraLog("media event unhandled=$event")
        }
    }

    private fun mediaPayload(folder: String, name: String): Map<String, Any?> {
        val format = name.substringAfterLast('.', "JPG").uppercase()
        return mapOf(
            "id" to "$folder|$name|0|$format|--:--:--",
            "folder" to folder,
            "fileName" to name,
            "sizeMb" to 0,
            "format" to format,
            "shotAt" to "--:--:--",
            "mediaType" to mediaTypeForFileName(name),
            "mimeType" to mimeTypeForFileName(name),
        )
    }

    private fun seedGenericMediaCatalog() {
        val files = synchronized(cameraOperationLock) {
            bridge.nativeListFiles("/").toList()
        }
        val keys = files.mapNotNull { encodedMediaKey(it) }.toSet()
        genericMediaCatalog.clear()
        genericMediaCatalog.addAll(keys)
        lastGenericMediaPollAtMs = System.currentTimeMillis()
        appendCameraLog("generic media catalog baseline files=${files.size} supported=${keys.size}")
    }

    private fun pollGenericMediaCatalog() {
        val files = synchronized(cameraOperationLock) {
            bridge.nativeListFiles("/").toList()
        }
        var discovered = 0
        files.forEach { encoded ->
            val parts = encoded.split("|", limit = 3)
            val folder = parts.getOrNull(0)?.ifBlank { "/" } ?: "/"
            val name = parts.getOrNull(1).orEmpty()
            if (name.isBlank()) return@forEach
            val mediaType = mediaTypeForFileName(name)
            if (mediaType == "unknown") return@forEach
            val key = genericMediaKey(folder, name)
            if (genericMediaCatalog.contains(key)) return@forEach
            val payload = mediaPayload(folder, name)
            publishGenericMedia(payload)
            if (genericMediaCatalog.contains(key)) discovered += 1
        }
        if (discovered > 0) {
            appendCameraLog("generic media catalog discovered files=$discovered")
        }
    }

    private fun publishGenericMedia(payload: Map<String, Any?>) {
        val folder = payload["folder"]?.toString().orEmpty()
        val name = payload["fileName"]?.toString().orEmpty()
        if (name.isBlank() || payload["mediaType"] == "unknown") return
        if (!genericMediaCatalog.add(genericMediaKey(folder, name))) return
        synchronized(pendingMediaEventLock) {
            pendingMediaEvents.add(payload)
        }
        appendCameraLog(
            "generic media added folder=$folder name=$name " +
                "type=${payload["mediaType"]}",
        )
        emitEvent(
            if (payload["mediaType"] == "video") "mediaAdded" else "photoAdded",
            payload,
        )
    }

    private fun encodedMediaKey(encoded: String): String? {
        val parts = encoded.split("|", limit = 3)
        val folder = parts.getOrNull(0)?.ifBlank { "/" } ?: "/"
        val name = parts.getOrNull(1).orEmpty()
        if (name.isBlank() || mediaTypeForFileName(name) == "unknown") return null
        return genericMediaKey(folder, name)
    }

    private fun genericMediaKey(folder: String, name: String): String {
        return "${folder.trim().lowercase()}/${name.trim().lowercase()}"
            .replace("//", "/")
    }

    private fun downloadPhoto(folder: String, name: String?, id: String?, result: MethodChannel.Result) {
        if (name.isNullOrBlank()) {
            result.error("invalid_argument", "缺少媒体文件名", null)
            return
        }
        val downloads = File(applicationContext.cacheDir, "camera-downloads").apply { mkdirs() }
        val canonBackend = canonPtpBackend
        val destinationName = if (canonBackend != null && !id.isNullOrBlank()) {
            "${id.hashCode().toUInt().toString(16)}_${name.substringAfterLast('/')}"
        } else {
            name.substringAfterLast('/')
        }
        val destination = File(downloads, destinationName)
        val path = canonBackend?.let { backend ->
            val photoId = id ?: run {
                result.error("invalid_argument", "缺少佳能媒体 ID", null)
                return
            }
            runCatching { backend.download(photoId, destination) }
                .getOrElse { error ->
                    result.error("download_failed", error.message ?: "佳能媒体下载失败", null)
                    return
                }
        } ?: run {
            val temporary = File(downloads, ".${destination.name}.part")
            runCatching { temporary.delete() }
            val nativePath = synchronized(cameraOperationLock) {
                bridge.nativeDownload(folder, name, temporary.absolutePath)
            }
            if (nativePath != temporary.absolutePath || !temporary.isFile) {
                temporary.delete()
                result.error("download_failed", nativePath.ifBlank { "相机媒体下载失败" }, null)
                return
            }
            runCatching { destination.delete() }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                result.error("download_failed", "无法完成媒体文件写入", null)
                return
            }
            destination.absolutePath
        }
        result.success(path)
    }

    private fun releaseCameraControl() {
        appendCameraLog("release camera control start")
        stopMediaEventListening()
        canonPtpBackend?.close() ?: synchronized(cameraOperationLock) { bridge.nativeDisconnect() }
        appendCameraLog("release camera control done")
    }

    private fun disconnectCamera() {
        appendCameraLog(
            "disconnect start device=${activeDevice?.deviceName} backend=$activeBackend",
        )
        stopMediaEventListening()
        canonPtpBackend?.close()
        canonPtpBackend = null
        synchronized(cameraOperationLock) { bridge.nativeDisconnect() }
        activeConnection?.close()
        activeConnection = null
        activeDevice = null
        activeBackend = "libgphoto2"
        genericMediaCatalog.clear()
        lastGenericMediaPollAtMs = 0L
        appendCameraLog("disconnect complete")
        emitEvent("disconnected", null)
    }

    private fun handleCanonPhotoAdded(photo: CanonPtpPhoto) {
        if (!mediaEventListening.get()) return
        val payload = photo.toMap()
        synchronized(pendingMediaEventLock) { pendingMediaEvents.add(payload) }
        emitEvent(
            if (payload["mediaType"] == "video") "mediaAdded" else "photoAdded",
            payload,
        )
    }

    private fun CanonPtpPhoto.toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "folder" to folder,
        "fileName" to fileName,
        "sizeMb" to sizeMb,
        "format" to format,
        "shotAt" to shotAt,
        "mediaType" to mediaTypeForFileName(fileName),
        "mimeType" to mimeTypeForFileName(fileName),
    )

    private fun mediaTypeForFileName(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            in IMAGE_EXTENSIONS -> "image"
            in VIDEO_EXTENSIONS -> "video"
            else -> "unknown"
        }
    }

    private fun mimeTypeForFileName(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "jpe" -> "image/jpeg"
            "dng" -> "image/dng"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m4v" -> "video/x-m4v"
            "avi" -> "video/x-msvideo"
            "mts", "m2ts" -> "video/mp2t"
            "webm" -> "video/webm"
            else -> "application/octet-stream"
        }
    }

    private fun findDevice(deviceName: String?): UsbDevice? {
        val devices = usbManager?.deviceList?.values.orEmpty()
        if (!deviceName.isNullOrBlank()) return devices.firstOrNull { it.deviceName == deviceName }
        return devices.firstOrNull()
    }

    private fun preparePluginDirectory(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val sourceRoot = "libgphoto2/$abi"
        val outputRoot = File(applicationContext.filesDir, "libgphoto2/$abi")
        copyAssetTree(sourceRoot, outputRoot)
        return outputRoot.absolutePath
    }

    private fun copyAssetTree(assetPath: String, output: File) {
        val children = applicationContext.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            output.parentFile?.mkdirs()
            applicationContext.assets.open(assetPath).use { input -> output.outputStream().use { input.copyTo(it) } }
            return
        }
        output.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(output, child))
    }

    private fun prepareCameraLogFile() {
        runCatching {
            val file = internalCameraLogFile()
            file.parentFile?.mkdirs()
            file.writeText("${LocalDateTime.now()} camera log start\n")
        }
    }

    private fun cameraLogPath(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Download/flyphoto-camera-usb.log"
        } else {
            legacyDownloadsLogFile().absolutePath
        }
    }

    private fun appendCameraLog(message: String) {
        val line = "${LocalDateTime.now()} [android] $message\n"
        runCatching {
            val file = internalCameraLogFile()
            file.parentFile?.mkdirs()
            file.appendText(line)
        }
    }

    private fun exportCameraLogToDownloads(): String {
        val source = internalCameraLogFile()
        if (!source.isFile) return ""
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val uri = downloadsLogUri ?: findOrCreateDownloadsLogUri()
                downloadsLogUri = uri
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("open Downloads log failed")
            } else {
                val destination = legacyDownloadsLogFile()
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
            cameraLogPath()
        }.getOrDefault("")
    }

    private fun findOrCreateDownloadsLogUri(): Uri {
        val resolver = applicationContext.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("flyphoto-camera-usb.log"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, cursor.getLong(0).toString())
            }
        }
        return resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "flyphoto-camera-usb.log")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            },
        ) ?: error("create Downloads log failed")
    }

    private fun legacyDownloadsLogFile(): File {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "flyphoto-camera-usb.log")
    }

    private fun internalCameraLogFile(): File =
        File(applicationContext.filesDir, "logs/camera-usb.log")

    private fun File.readTextIfExists(): String {
        return if (exists()) readText() else ""
    }

    private fun emitEvent(type: String, payload: Any?) {
        mainHandler.post {
            eventSink?.success(mapOf("type" to type, "payload" to payload))
        }
    }

    private class MainThreadResult(
        private val delegate: MethodChannel.Result,
        private val handler: Handler,
    ) : MethodChannel.Result {
        override fun success(result: Any?) {
            handler.post { delegate.success(result) }
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            handler.post { delegate.error(errorCode, errorMessage, errorDetails) }
        }

        override fun notImplemented() {
            handler.post(delegate::notImplemented)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun UsbDevice.isCanon(): Boolean = vendorId == 0x04A9

    private fun UsbDevice.toMap(): Map<String, Any?> {
        return mapOf(
            "deviceName" to deviceName,
            "vendorId" to vendorId,
            "productId" to productId,
            "deviceClass" to deviceClass,
            "deviceSubclass" to deviceSubclass,
            "deviceProtocol" to deviceProtocol,
            "manufacturerName" to manufacturerName,
            "productName" to productName,
            "serialNumber" to runCatching { serialNumber }.getOrNull(),
            "isCanon" to isCanon(),
        )
    }

    private fun UsbDevice.toLogMap(): Map<String, Any?> =
        toMap().redactedForLog()
}

private data class CameraMediaScanPayload(
    val media: List<Map<String, Any?>> = emptyList(),
    val state: String,
    val backend: String,
    val folderCount: Int = 0,
    val fileCount: Int = 0,
    val errors: List<String> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "media" to media,
        "state" to state,
        "backend" to backend,
        "folderCount" to folderCount,
        "fileCount" to fileCount,
        "errors" to errors,
    )
}

private data class NativeMediaScanReport(
    val rootFilesResult: Int,
    val rootFoldersResult: Int,
    val visitedFolderCount: Int,
    val discoveredFileCount: Int,
    val errors: List<String>,
) {
    companion object {
        fun fromValues(values: Array<String>): NativeMediaScanReport {
            fun integer(key: String): Int = values
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
                ?.toIntOrNull() ?: -1

            return NativeMediaScanReport(
                rootFilesResult = integer("rootFilesResult"),
                rootFoldersResult = integer("rootFoldersResult"),
                visitedFolderCount = integer("visitedFolderCount").coerceAtLeast(0),
                discoveredFileCount = integer("discoveredFileCount").coerceAtLeast(0),
                errors = values
                    .filter { it.startsWith("error=") }
                    .map { it.substringAfter('=').trim() }
                    .filter { it.isNotEmpty() },
            )
        }
    }
}

private fun Map<String, Any?>.redactedForLog(): Map<String, Any?> =
    toMutableMap().apply {
        val serial = this["serialNumber"]?.toString().orEmpty()
        if (serial.isNotEmpty()) {
            this["serialNumber"] = if (serial.length <= 4) "****" else "****${serial.takeLast(4)}"
        }
    }

private val IMAGE_EXTENSIONS = setOf(
    "jpg", "jpeg", "jpe", "arw", "raw", "dng", "cr2", "cr3", "nef", "raf",
    "orf", "rw2", "pef", "srw", "x3f",
)

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mov", "m4v", "avi", "mts", "m2ts", "webm",
)

private const val SONY_VENDOR_ID = 0x054c
private const val GENERIC_MEDIA_POLL_INTERVAL_MS = 2_000L
