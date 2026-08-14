package com.flyphoto.usb_camera_sdk

class GPhoto2Bridge {
    external fun nativeSetLogFile(logFilePath: String)
    external fun nativeInit(pluginDir: String, tempDir: String): String
    external fun nativeConnectCamera(
        fileDescriptor: Int,
        vendorId: Int,
        productId: Int,
        deviceName: String,
    ): String
    external fun nativeListFiles(folder: String): Array<String>
    external fun nativeGetLastMediaScanReport(): Array<String>
    external fun nativeWaitForEvent(timeoutMs: Int): String
    external fun nativeCapture(): String
    external fun nativeDownload(folder: String, name: String, destinationPath: String): String
    external fun nativeDisconnect()

    companion object {
        init {
            System.loadLibrary("flyphoto_gphoto2_bridge")
        }
    }
}
