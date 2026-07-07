package com.flyphoto.usb_camera_sdk

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.time.LocalDateTime

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
    private var eventSink: EventChannel.EventSink? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var activeConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var receiverRegistered = false

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
            "capture" -> result.success(bridge.nativeCapture())
            "listPhotos" -> result.success(listPhotos(call.argument<String>("folder") ?: "/"))
            "downloadPhoto" -> downloadPhoto(
                folder = call.argument<String>("folder") ?: "/",
                name = call.argument<String>("name"),
                result = result,
            )
            "getCameraLog" -> result.success(cameraLogFile().readTextIfExists())
            "getCameraLogPath" -> result.success(cameraLogFile().absolutePath)
            "exportCameraLog" -> result.success(exportCameraLogToDownloads())
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
        val logFile = cameraLogFile()
        bridge.nativeSetLogFile(logFile.absolutePath)
        appendCameraLog("pluginDir=$pluginDir")
        appendCameraLog("tempDir=$tempDir")
        appendCameraLog("logFile=${logFile.absolutePath}")
        appendCameraLog("pluginFiles=${File(pluginDir, "lib/libgphoto2_port/0.12.2").listFiles()?.map { it.name }}")
        val initResult = bridge.nativeInit(pluginDir, tempDir)
        appendCameraLog("nativeInit=$initResult")
        if (initResult != "ok") {
            appendCameraLog("native_init_failed=$initResult")
            val exportedPath = exportCameraLogToDownloads()
            appendCameraLog("exportedLog=$exportedPath")
            disconnectCamera()
            result.error("native_init_failed", initResult, null)
            return
        }

        val nativeResult = bridge.nativeConnectCamera(
            connection.fileDescriptor,
            device.vendorId,
            device.productId,
            device.deviceName,
        )
        appendCameraLog("nativeConnectCamera=$nativeResult")
        if (nativeResult != "ok") {
            appendCameraLog("native_connect_failed=$nativeResult")
            val exportedPath = exportCameraLogToDownloads()
            appendCameraLog("exportedLog=$exportedPath")
            disconnectCamera()
            result.error("native_connect_failed", nativeResult, device.toMap())
            return
        }

        val payload = device.toMap().toMutableMap().apply {
            put("model", device.productName ?: device.deviceName)
            put("pluginDir", pluginDir)
            put("logFile", logFile.absolutePath)
            put("exportedLogFile", exportCameraLogToDownloads())
        }
        appendCameraLog("connect success payload=$payload")
        emitEvent("connected", payload)
        result.success(payload)
    }

    private fun listPhotos(folder: String): List<Map<String, Any?>> {
        return bridge.nativeListFiles(folder).map { encoded ->
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

    private fun downloadPhoto(folder: String, name: String?, result: MethodChannel.Result) {
        if (name.isNullOrBlank()) {
            result.error("invalid_argument", "缺少照片文件名", null)
            return
        }
        val downloads = File(applicationContext.cacheDir, "camera-downloads").apply { mkdirs() }
        val destination = File(downloads, name.substringAfterLast('/'))
        val path = bridge.nativeDownload(folder, name, destination.absolutePath)
        result.success(path)
    }

    private fun disconnectCamera() {
        bridge.nativeDisconnect()
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

    private fun cameraLogFile(): File {
        val dir = File(applicationContext.filesDir, "logs").apply { mkdirs() }
        return File(dir, "camera-usb.log")
    }

    private fun appendCameraLog(message: String) {
        val line = "${LocalDateTime.now()} [android] $message\n"
        cameraLogFile().appendText(line)
    }

    private fun exportCameraLogToDownloads(): String {
        val source = cameraLogFile()
        if (!source.exists()) return ""
        val fileName = "flyphoto-camera-usb.log"
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val existingUri = resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(fileName),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                    } else {
                        null
                    }
                }
                val uri = existingUri ?: resolver.insert(
                    collection,
                    android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    },
                ) ?: return@runCatching ""
                resolver.openOutputStream(uri, "wt")?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                }
                "Download/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.mkdirs()
                val target = File(downloads, fileName)
                source.copyTo(target, overwrite = true)
                target.absolutePath
            }
        }.getOrElse { error ->
            appendCameraLog("exportCameraLogToDownloads failed=${error.message}")
            ""
        }
    }

    private fun File.readTextIfExists(): String {
        return if (exists()) readText() else ""
    }

    private fun emitEvent(type: String, payload: Any?) {
        eventSink?.success(mapOf("type" to type, "payload" to payload))
    }

    private fun Intent.usbDevice(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

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
        )
    }
}
