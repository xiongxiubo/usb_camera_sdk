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
    private val photoEventListening = AtomicBoolean(false)
    private val pendingPhotoEventLock = Any()
    private val cameraOperationLock = Any()
    private val pendingPhotoEvents = mutableListOf<Map<String, Any?>>()
    private var photoEventThread: Thread? = null
    private var eventSink: EventChannel.EventSink? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var receiverRegistered = false
    private var downloadsLogUri: Uri? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                usbPermissionAction -> handlePermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    emitEvent("deviceAttached", intent.usbDevice()?.toMap())
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = intent.usbDevice()
                    if (detached?.deviceName == activeDevice?.deviceName) disconnectCamera()
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
        disconnectCamera()
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
        when (call.method) {
            "listDevices" -> result.success(listDevices())
            "requestPermission" -> requestPermission(call.argument<String>("deviceName"), result)
            "connect" -> connect(call.argument<String>("deviceName"), result)
            "disconnect" -> {
                disconnectCamera()
                result.success(null)
            }
            "releaseCameraControl" -> {
                releaseCameraControl()
                result.success(null)
            }
            "capture" -> capture(result)
            "listPhotos" -> result.success(listPhotos(call.argument<String>("folder") ?: "/"))
            "drainPhotoEvents" -> result.success(drainPhotoEvents())
            "startPhotoEventListening" -> {
                startPhotoEventListening(result)
            }
            "stopPhotoEventListening" -> {
                stopPhotoEventListening()
                result.success(null)
            }
            "downloadPhoto" -> downloadPhoto(
                folder = call.argument<String>("folder") ?: "/",
                name = call.argument<String>("name"),
                result = result,
            )
            "getCameraLog" -> result.success("")
            "getCameraLogPath" -> result.success(cameraLogPath())
            "exportCameraLog" -> result.success(exportCameraLogToDownloads())
            "appendCameraLog" -> {
                appendCameraLog(call.argument<String>("message") ?: "")
                result.success(null)
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
        appendCameraLog("========== connect start ==========")
        appendCameraLog("requestedDeviceName=$deviceName")
        val device = findDevice(deviceName)
        if (device == null) {
            appendCameraLog("no_device: ${manager.deviceList.values.map { it.toMap() }}")
            result.error("no_device", "未找到 USB 相机", null)
            return
        }
        appendCameraLog("device=${device.toMap()}")
        if (!manager.hasPermission(device)) {
            appendCameraLog("permission_denied")
            result.error("permission_denied", "没有 USB 设备权限", device.toMap())
            return
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            appendCameraLog("open_failed")
            result.error("open_failed", "无法打开 USB 设备", device.toMap())
            return
        }
        appendCameraLog("openDevice ok fd=${connection.fileDescriptor}")

        activeConnection?.close()
        activeConnection = connection
        activeDevice = device

        val pluginDir = preparePluginDirectory()
        val tempDir = File(applicationContext.cacheDir, "gphoto2").apply { mkdirs() }.absolutePath
        prepareCameraLogFile()
        bridge.nativeSetLogFile("")
        appendCameraLog("pluginDir=$pluginDir")
        appendCameraLog("tempDir=$tempDir")
        appendCameraLog("logFile=${cameraLogPath()}")
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
        val isCanon = device.isCanon()
        val payload = device.toMap().toMutableMap().apply {
            put("model", device.productName ?: device.deviceName)
            put("pluginDir", pluginDir)
            put("logFile", cameraLogPath())
            put("exportedLogFile", cameraLogPath())
            put("isCanon", isCanon)
            put("backend", "libgphoto2")
            if (isCanon) put("canonStrategy", "physical_shutter_release_control")
        }
        if (isCanon) {
            appendCameraLog("canon strategy=physical_shutter_release_control backend=libgphoto2")
        }
        appendCameraLog("connect success payload=$payload")
        emitEvent("connected", payload)
        result.success(payload)
    }

    private fun capture(result: MethodChannel.Result) {
        val start = System.currentTimeMillis()
        appendCameraLog("capture start")
        val nativeResult = synchronized(cameraOperationLock) {
            bridge.nativeCapture()
        }
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

    private fun listPhotos(folder: String): List<Map<String, Any?>> {
        return synchronized(cameraOperationLock) {
            bridge.nativeListFiles(folder)
        }.map { encoded ->
            val parts = encoded.split("|")
            mapOf(
                "id" to encoded,
                "folder" to (parts.getOrNull(0) ?: folder),
                "fileName" to (parts.getOrNull(1) ?: encoded),
                "sizeMb" to (parts.getOrNull(2)?.toIntOrNull() ?: 0),
                "format" to (parts.getOrNull(3) ?: parts.getOrNull(1)?.substringAfterLast('.', "JPG") ?: "JPG"),
                "shotAt" to (parts.getOrNull(4) ?: "--:--:--"),
            )
        }
    }

    private fun drainPhotoEvents(): List<Map<String, Any?>> {
        return synchronized(pendingPhotoEventLock) {
            val events = pendingPhotoEvents.toList()
            pendingPhotoEvents.clear()
            events
        }
    }

    private fun startPhotoEventListening(result: MethodChannel.Result) {
        if (activeDevice == null || activeConnection == null) {
            result.error("not_connected", "USB 相机未连接", null)
            return
        }
        startPhotoEventListening()
        result.success(null)
    }

    private fun startPhotoEventListening() {
        if (activeDevice == null || activeConnection == null) {
            appendCameraLog("photo event listening skipped: not connected")
            return
        }
        if (!photoEventListening.compareAndSet(false, true)) return
        appendCameraLog("photo event listening start")
        photoEventThread = Thread({
            while (photoEventListening.get()) {
                val event = synchronized(cameraOperationLock) {
                    bridge.nativeWaitForEvent(750)
                }
                if (!photoEventListening.get()) break
                handlePhotoEventResult(event)
            }
            appendCameraLog("photo event listening stopped")
        }, "FlyPhotoCameraEvents").also { it.start() }
    }

    private fun stopPhotoEventListening() {
        if (!photoEventListening.getAndSet(false)) return
        appendCameraLog("photo event listening stop requested")
        val thread = photoEventThread
        if (thread != null && thread != Thread.currentThread()) {
            runCatching { thread.join(1200) }
        }
        photoEventThread = null
    }

    private fun handlePhotoEventResult(event: String) {
        when {
            event == "timeout" || event == "captureComplete" || event == "unknown" -> return
            event == "disconnected" -> {
                appendCameraLog("photo event disconnected")
                photoEventListening.set(false)
            }
            event.startsWith("fileAdded|") -> {
                val parts = event.split("|", limit = 3)
                val folder = parts.getOrNull(1)?.ifBlank { "/" } ?: "/"
                val name = parts.getOrNull(2).orEmpty()
                appendCameraLog("photo event fileAdded folder=$folder name=$name")
                if (name.isBlank()) return
                val payload = photoPayload(folder, name)
                synchronized(pendingPhotoEventLock) {
                    pendingPhotoEvents.add(payload)
                }
                emitEvent("photoAdded", payload)
            }
            event.startsWith("folderAdded|") -> appendCameraLog("photo event $event")
            event.startsWith("error|") -> {
                appendCameraLog("photo event $event")
                if (event.contains("Could not find the requested device on the USB port")) {
                    photoEventListening.set(false)
                }
            }
            else -> appendCameraLog("photo event unhandled=$event")
        }
    }

    private fun photoPayload(folder: String, name: String): Map<String, Any?> {
        val format = name.substringAfterLast('.', "JPG").uppercase()
        return mapOf(
            "id" to "$folder|$name|0|$format|--:--:--",
            "folder" to folder,
            "fileName" to name,
            "sizeMb" to 0,
            "format" to format,
            "shotAt" to "--:--:--",
        )
    }

    private fun downloadPhoto(folder: String, name: String?, result: MethodChannel.Result) {
        if (name.isNullOrBlank()) {
            result.error("invalid_argument", "缺少照片文件名", null)
            return
        }
        val downloads = File(applicationContext.cacheDir, "camera-downloads").apply { mkdirs() }
        val destination = File(downloads, name.substringAfterLast('/'))
        val path = synchronized(cameraOperationLock) {
            bridge.nativeDownload(folder, name, destination.absolutePath)
        }
        result.success(path)
    }

    private fun releaseCameraControl() {
        appendCameraLog("release camera control start")
        stopPhotoEventListening()
        synchronized(cameraOperationLock) {
            bridge.nativeDisconnect()
        }
        appendCameraLog("release camera control done")
    }

    private fun disconnectCamera() {
        stopPhotoEventListening()
        synchronized(cameraOperationLock) {
            bridge.nativeDisconnect()
        }
        activeConnection?.close()
        activeConnection = null
        activeDevice = null
        emitEvent("disconnected", null)
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
        deleteInternalCameraLog()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val uri = downloadsLogUri ?: findOrCreateDownloadsLogUri()
                downloadsLogUri = uri
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write("${LocalDateTime.now()} camera log start\n".toByteArray())
                }
            } else {
                @Suppress("DEPRECATION")
                val file = legacyDownloadsLogFile()
                file.parentFile?.mkdirs()
                file.writeText("${LocalDateTime.now()} camera log start\n")
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val uri = downloadsLogUri ?: findOrCreateDownloadsLogUri()
                downloadsLogUri = uri
                resolver.openOutputStream(uri, "wa")?.use { output ->
                    output.write(line.toByteArray())
                }
            } else {
                legacyDownloadsLogFile().appendText(line)
            }
        }
    }

    private fun exportCameraLogToDownloads(): String = cameraLogPath()

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

    private fun deleteInternalCameraLog() {
        runCatching {
            File(applicationContext.filesDir, "logs/camera-usb.log").delete()
        }
    }

    private fun File.readTextIfExists(): String {
        return if (exists()) readText() else ""
    }

    private fun emitEvent(type: String, payload: Any?) {
        mainHandler.post {
            eventSink?.success(mapOf("type" to type, "payload" to payload))
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
}
