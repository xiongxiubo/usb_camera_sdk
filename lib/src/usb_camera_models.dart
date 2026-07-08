enum CameraConnectionStatus {
  disconnected,
  permissionRequired,
  connecting,
  connected,
  failed,
  unsupported,
}

enum CameraIngestionMode {
  eventAndPolling,
  manualSync,
}

class UsbCameraCapabilities {
  const UsbCameraCapabilities({
    required this.ingestionMode,
  });

  final CameraIngestionMode ingestionMode;

  bool get supportsAutomaticIngestion =>
      ingestionMode == CameraIngestionMode.eventAndPolling;

  static const eventAndPolling = UsbCameraCapabilities(
    ingestionMode: CameraIngestionMode.eventAndPolling,
  );

  static const manualSync = UsbCameraCapabilities(
    ingestionMode: CameraIngestionMode.manualSync,
  );

  factory UsbCameraCapabilities.forDevice(UsbCameraDevice device) {
    if (device.isCanon) return manualSync;
    return eventAndPolling;
  }
}

class UsbCameraDevice {
  const UsbCameraDevice({
    required this.deviceName,
    required this.vendorId,
    required this.productId,
    this.deviceClass,
    this.deviceSubclass,
    this.deviceProtocol,
    this.manufacturerName,
    this.productName,
    this.serialNumber,
  });

  final String deviceName;
  final int vendorId;
  final int productId;
  final int? deviceClass;
  final int? deviceSubclass;
  final int? deviceProtocol;
  final String? manufacturerName;
  final String? productName;
  final String? serialNumber;

  String get displayName => productName ?? manufacturerName ?? deviceName;

  bool get isCanon => vendorId == 0x04a9;

  UsbCameraCapabilities get capabilities =>
      UsbCameraCapabilities.forDevice(this);

  factory UsbCameraDevice.fromMap(Map<dynamic, dynamic> map) {
    return UsbCameraDevice(
      deviceName: map['deviceName']?.toString() ?? '',
      vendorId: _intValue(map['vendorId']),
      productId: _intValue(map['productId']),
      deviceClass: _nullableIntValue(map['deviceClass']),
      deviceSubclass: _nullableIntValue(map['deviceSubclass']),
      deviceProtocol: _nullableIntValue(map['deviceProtocol']),
      manufacturerName: map['manufacturerName']?.toString(),
      productName: map['productName']?.toString(),
      serialNumber: map['serialNumber']?.toString(),
    );
  }
}

class UsbCameraPhoto {
  const UsbCameraPhoto({
    required this.id,
    required this.fileName,
    required this.shotAt,
    required this.sizeMb,
    required this.format,
    required this.folder,
    this.localPath,
  });

  final String id;
  final String fileName;
  final String shotAt;
  final int sizeMb;
  final String format;
  final String folder;
  final String? localPath;

  factory UsbCameraPhoto.fromMap(Map<dynamic, dynamic> map) {
    final fileName = map['fileName']?.toString() ?? '';
    final folder = map['folder']?.toString() ?? '/';
    return UsbCameraPhoto(
      id: map['id']?.toString() ?? '$folder/$fileName',
      fileName: fileName,
      shotAt: map['shotAt']?.toString() ?? '--:--:--',
      sizeMb: _intValue(map['sizeMb']),
      format:
          (map['format']?.toString() ?? fileName.split('.').last).toUpperCase(),
      folder: folder,
      localPath: map['localPath']?.toString(),
    );
  }
}

class CameraTransferProgress {
  const CameraTransferProgress({
    required this.fileName,
    required this.progress,
  });

  final String fileName;
  final double progress;
}

int _intValue(dynamic value) {
  return _nullableIntValue(value) ?? 0;
}

int? _nullableIntValue(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value);
  return null;
}
