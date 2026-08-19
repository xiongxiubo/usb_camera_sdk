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

enum UsbCameraMediaType {
  image,
  video,
  unknown,
}

enum UsbCameraMediaScanState {
  ready,
  empty,
  storageUnavailable,
  failed;

  static UsbCameraMediaScanState fromWire(String? value) {
    return switch (value?.trim().toLowerCase()) {
      'ready' => UsbCameraMediaScanState.ready,
      'storage_unavailable' => UsbCameraMediaScanState.storageUnavailable,
      'failed' => UsbCameraMediaScanState.failed,
      _ => UsbCameraMediaScanState.empty,
    };
  }
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
    UsbCameraMediaType? mediaType,
    String? mimeType,
  })  : _mediaType = mediaType,
        _mimeType = mimeType;

  final String id;
  final String fileName;
  final String shotAt;
  final int sizeMb;
  final String format;
  final String folder;
  final String? localPath;
  final UsbCameraMediaType? _mediaType;
  final String? _mimeType;

  UsbCameraMediaType get mediaType =>
      _mediaType ?? usbCameraMediaTypeForFileName(fileName);

  String get mimeType => _mimeType ?? usbCameraMimeTypeForFileName(fileName);

  bool get isImage => mediaType == UsbCameraMediaType.image;

  bool get isVideo => mediaType == UsbCameraMediaType.video;

  bool get isSupportedMedia => mediaType != UsbCameraMediaType.unknown;

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
      mediaType: _mediaTypeValue(map['mediaType'] ?? map['media_type']),
      mimeType: map['mimeType']?.toString() ?? map['mime_type']?.toString(),
    );
  }
}

/// Generic name for camera files. The alias keeps the original public
/// [UsbCameraPhoto] API source-compatible for existing integrations.
typedef UsbCameraMediaFile = UsbCameraPhoto;

class UsbCameraMediaScanResult {
  const UsbCameraMediaScanResult({
    required this.media,
    required this.state,
    required this.backend,
    required this.folderCount,
    required this.fileCount,
    required this.errors,
  });

  static const empty = UsbCameraMediaScanResult(
    media: [],
    state: UsbCameraMediaScanState.empty,
    backend: '',
    folderCount: 0,
    fileCount: 0,
    errors: [],
  );

  final List<UsbCameraMediaFile> media;
  final UsbCameraMediaScanState state;
  final String backend;
  final int folderCount;
  final int fileCount;
  final List<String> errors;

  bool get storageBrowsable =>
      state == UsbCameraMediaScanState.ready ||
      state == UsbCameraMediaScanState.empty;

  factory UsbCameraMediaScanResult.fromMap(Map<dynamic, dynamic> map) {
    final rawMedia = map['media'];
    final rawErrors = map['errors'];
    return UsbCameraMediaScanResult(
      media: rawMedia is List
          ? rawMedia
              .whereType<Map<dynamic, dynamic>>()
              .map(UsbCameraPhoto.fromMap)
              .where((file) => file.isSupportedMedia)
              .toList(growable: false)
          : const [],
      state: UsbCameraMediaScanState.fromWire(map['state']?.toString()),
      backend: map['backend']?.toString() ?? '',
      folderCount: _intValue(map['folderCount'] ?? map['folder_count']),
      fileCount: _intValue(map['fileCount'] ?? map['file_count']),
      errors: rawErrors is List
          ? rawErrors
              .map((item) => item.toString().trim())
              .where((item) => item.isNotEmpty)
              .toList(growable: false)
          : const [],
    );
  }
}

class UsbCameraMediaPage {
  const UsbCameraMediaPage({
    required this.media,
    required this.state,
    required this.backend,
    required this.folderCount,
    required this.fileCount,
    required this.nextCursor,
    required this.hasMore,
    required this.errors,
  });

  static const empty = UsbCameraMediaPage(
    media: [],
    state: UsbCameraMediaScanState.empty,
    backend: '',
    folderCount: 0,
    fileCount: 0,
    nextCursor: null,
    hasMore: false,
    errors: [],
  );

  final List<UsbCameraMediaFile> media;
  final UsbCameraMediaScanState state;
  final String backend;
  final int folderCount;
  final int fileCount;
  final String? nextCursor;
  final bool hasMore;
  final List<String> errors;

  factory UsbCameraMediaPage.fromMap(Map<dynamic, dynamic> map) {
    final rawMedia = map['media'];
    final rawErrors = map['errors'];
    return UsbCameraMediaPage(
      media: rawMedia is List
          ? rawMedia
              .whereType<Map<dynamic, dynamic>>()
              .map(UsbCameraPhoto.fromMap)
              .where((file) => file.isSupportedMedia)
              .toList(growable: false)
          : const [],
      state: UsbCameraMediaScanState.fromWire(map['state']?.toString()),
      backend: map['backend']?.toString() ?? '',
      folderCount: _intValue(map['folderCount'] ?? map['folder_count']),
      fileCount: _intValue(map['fileCount'] ?? map['file_count']),
      nextCursor:
          map['nextCursor']?.toString() ?? map['next_cursor']?.toString(),
      hasMore: map['hasMore'] == true || map['has_more'] == true,
      errors: rawErrors is List
          ? rawErrors
              .map((item) => item.toString().trim())
              .where((item) => item.isNotEmpty)
              .toList(growable: false)
          : const [],
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

UsbCameraMediaType usbCameraMediaTypeForFileName(String fileName) {
  final extension = _fileExtension(fileName);
  if (_imageExtensions.contains(extension)) return UsbCameraMediaType.image;
  if (_videoExtensions.contains(extension)) return UsbCameraMediaType.video;
  return UsbCameraMediaType.unknown;
}

String usbCameraMimeTypeForFileName(String fileName) {
  switch (_fileExtension(fileName)) {
    case 'jpg':
    case 'jpeg':
    case 'jpe':
      return 'image/jpeg';
    case 'dng':
      return 'image/dng';
    case 'mp4':
      return 'video/mp4';
    case 'mov':
      return 'video/quicktime';
    case 'm4v':
      return 'video/x-m4v';
    case 'avi':
      return 'video/x-msvideo';
    case 'mts':
    case 'm2ts':
      return 'video/mp2t';
    case 'webm':
      return 'video/webm';
  }
  return 'application/octet-stream';
}

UsbCameraMediaType? _mediaTypeValue(dynamic value) {
  switch (value?.toString().trim().toLowerCase()) {
    case 'image':
    case 'photo':
      return UsbCameraMediaType.image;
    case 'video':
      return UsbCameraMediaType.video;
    case 'unknown':
      return UsbCameraMediaType.unknown;
  }
  return null;
}

String _fileExtension(String fileName) {
  final name = fileName.trim().toLowerCase();
  final index = name.lastIndexOf('.');
  if (index < 0 || index == name.length - 1) return '';
  return name.substring(index + 1);
}

const _imageExtensions = {
  'jpg',
  'jpeg',
  'jpe',
  'arw',
  'raw',
  'dng',
  'cr2',
  'cr3',
  'nef',
  'raf',
  'orf',
  'rw2',
  'pef',
  'srw',
  'x3f',
};

const _videoExtensions = {
  'mp4',
  'mov',
  'm4v',
  'avi',
  'mts',
  'm2ts',
  'webm',
};
