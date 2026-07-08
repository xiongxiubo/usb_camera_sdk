import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'usb_camera_models.dart';
import 'usb_camera_repository.dart';

class UsbCameraController extends ChangeNotifier {
  UsbCameraController({required UsbCameraRepository repository})
      : _repository = repository {
    _eventSubscription = _repository.events.listen(_handleNativeEvent);
    if (!_repository.isSupported) {
      _status = CameraConnectionStatus.unsupported;
    }
  }

  final UsbCameraRepository _repository;
  StreamSubscription<Map<dynamic, dynamic>>? _eventSubscription;
  final _addedPhotoController = StreamController<UsbCameraPhoto>.broadcast();

  var _status = CameraConnectionStatus.disconnected;
  var _devices = <UsbCameraDevice>[];
  var _photos = <UsbCameraPhoto>[];
  UsbCameraDevice? _connectedDevice;
  String? _errorMessage;
  bool _isLoading = false;
  bool _eventListening = false;
  bool _captureInFlight = false;

  CameraConnectionStatus get status => _status;
  List<UsbCameraDevice> get devices => List.unmodifiable(_devices);
  List<UsbCameraPhoto> get photos => List.unmodifiable(_photos);
  UsbCameraDevice? get connectedDevice => _connectedDevice;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  bool get isConnected => _status == CameraConnectionStatus.connected;
  bool get isFailed => _status == CameraConnectionStatus.failed;
  bool get isPhotoEventListening => _eventListening;
  UsbCameraCapabilities get capabilities =>
      _connectedDevice?.capabilities ?? UsbCameraCapabilities.eventAndPolling;
  bool get supportsAutomaticPhotoIngestion =>
      capabilities.supportsAutomaticIngestion;
  bool get isCanonConnected => _connectedDevice?.isCanon ?? false;
  String get cameraModel => _connectedDevice?.displayName ?? 'USB 相机';
  Stream<UsbCameraPhoto> get addedPhotos => _addedPhotoController.stream;

  Future<void> loadDevices() async {
    await _run(() async {
      _devices = await _repository.listDevices();
      if (_devices.isEmpty && _status != CameraConnectionStatus.unsupported) {
        _status = CameraConnectionStatus.disconnected;
      }
    });
  }

  Future<bool> connectFirstAvailable() async {
    if (!_repository.isSupported) {
      _status = CameraConnectionStatus.unsupported;
      _errorMessage = '当前平台不支持 USB 相机';
      notifyListeners();
      return false;
    }

    await loadDevices();
    final device = _devices.isEmpty ? null : _devices.first;
    if (device == null) {
      _status = CameraConnectionStatus.disconnected;
      _errorMessage = '未找到 USB 相机';
      notifyListeners();
      return false;
    }
    return connect(device);
  }

  Future<bool> connect(UsbCameraDevice device) async {
    _status = CameraConnectionStatus.connecting;
    _errorMessage = null;
    notifyListeners();

    try {
      final granted = await _repository.requestPermission(device);
      if (!granted) {
        _status = CameraConnectionStatus.permissionRequired;
        _errorMessage = '需要授权访问 USB 相机';
        notifyListeners();
        return false;
      }
      _connectedDevice = await _repository.connect(device);
      _status = CameraConnectionStatus.connected;
      if (supportsAutomaticPhotoIngestion) {
        await refreshPhotos();
      } else {
        await appendLog(
          'camera connected: manual sync mode skips initial listPhotos',
        );
      }
      notifyListeners();
      return true;
    } on PlatformException catch (error) {
      _status = CameraConnectionStatus.failed;
      _errorMessage = error.message ?? '相机连接失败';
      notifyListeners();
      return false;
    } catch (error) {
      _status = CameraConnectionStatus.failed;
      _errorMessage = error.toString();
      notifyListeners();
      return false;
    }
  }

  Future<void> releaseControlForPhysicalShutter() async {
    if (!isConnected) return;
    await appendLog('camera physical shutter release control start');
    await stopPhotoEventListening();
    await _repository.releaseCameraControl();
    _photos = [];
    await appendLog('camera physical shutter release control done');
    notifyListeners();
  }

  Future<void> disconnect() async {
    await stopPhotoEventListening();
    await _repository.disconnect();
    _connectedDevice = null;
    _photos = [];
    _status = _repository.isSupported
        ? CameraConnectionStatus.disconnected
        : CameraConnectionStatus.unsupported;
    notifyListeners();
  }

  Future<List<UsbCameraPhoto>> drainPhotoEvents() async {
    if (!isConnected) return const [];
    try {
      final events = await _repository.drainPhotoEvents();
      final uploadableEvents = _preferJpegPhotos(events);
      for (final photo in uploadableEvents) {
        final exists = _photos.any((item) =>
            item.id == photo.id ||
            (item.folder == photo.folder && item.fileName == photo.fileName));
        if (!exists) {
          _photos = [photo, ..._photos];
        }
      }
      if (uploadableEvents.isNotEmpty) notifyListeners();
      return events;
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
      return const [];
    }
  }

  Future<void> startPhotoEventListening() async {
    if (!isConnected) return;
    if (!supportsAutomaticPhotoIngestion) {
      await appendLog(
        'photo event listening skipped: manual sync ingestion mode',
      );
      return;
    }
    try {
      await _repository.startPhotoEventListening();
      _eventListening = true;
    } on PlatformException catch (error) {
      _errorMessage = error.message ?? '相机事件监听启动失败';
      notifyListeners();
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
    }
  }

  Future<void> startPassivePhotoEventExperiment() async {
    if (!isConnected) return;
    try {
      await appendLog(
        'passive photo event experiment start: wait_for_event only',
      );
      await _repository.startPhotoEventListening();
      _eventListening = true;
      notifyListeners();
    } on PlatformException catch (error) {
      _errorMessage = error.message ?? '被动事件监听启动失败';
      await appendLog(
        'passive photo event experiment failed ${error.code}: ${_errorMessage ?? ''}',
      );
      notifyListeners();
    } catch (error) {
      _errorMessage = error.toString();
      await appendLog('passive photo event experiment failed $error');
      notifyListeners();
    }
  }

  Future<void> stopPhotoEventListening() async {
    try {
      await _repository.stopPhotoEventListening();
      _eventListening = false;
    } catch (_) {
      // The native listener is best-effort cleanup during disconnect/dispose.
    }
  }

  Future<void> appendLog(String message) async {
    try {
      await _repository.appendCameraLog(message);
    } catch (_) {
      // Logging must never affect camera capture or upload flow.
    }
  }

  Future<void> refreshPhotos() async {
    if (!isConnected) return;
    await _run(() async {
      _photos = _preferJpegPhotos(await _repository.listPhotos());
    });
  }

  Future<List<UsbCameraPhoto>> refreshPhotosIncremental() async {
    if (!isConnected) return const [];
    try {
      final currentKeys = _photos.map(_photoPairKey).toSet();
      final latest = _preferJpegPhotos(await _repository.listPhotos());
      final fresh = latest
          .where((photo) => !currentKeys.contains(_photoPairKey(photo)))
          .toList();
      if (fresh.isNotEmpty || latest.length != _photos.length) {
        _photos = [
          ...fresh,
          for (final photo in _photos)
            latest.firstWhere(
              (item) => _photoPairKey(item) == _photoPairKey(photo),
              orElse: () => photo,
            ),
        ];
        final seen = <String>{};
        _photos = [
          for (final photo in _photos)
            if (seen.add(_photoPairKey(photo))) photo,
        ];
        notifyListeners();
      }
      return fresh;
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
      return const [];
    }
  }

  Future<List<UsbCameraPhoto>> resolveAddedPhoto(
      UsbCameraPhoto eventPhoto) async {
    if (!isConnected) {
      return _isJpegFileName(eventPhoto.fileName) ? [eventPhoto] : const [];
    }
    await appendLog(
      'resolve photo event folder=${eventPhoto.folder} name=${eventPhoto.fileName}',
    );

    if (_isRawFileName(eventPhoto.fileName)) {
      final beforeKeys = _photos.map(_photoPairKey).toSet();
      for (var attempt = 1; attempt <= 4; attempt += 1) {
        if (attempt > 1) {
          await Future<void>.delayed(const Duration(milliseconds: 500));
        }
        await refreshPhotos();
        final companion = _findJpegCompanion(eventPhoto, _photos);
        if (companion != null) {
          await appendLog(
            'resolve photo raw companion=${companion.folder}/${companion.fileName}',
          );
          return [companion];
        }
        final freshJpeg = _findFirstNewPhoto(beforeKeys, _photos);
        if (freshJpeg != null) {
          await appendLog(
            'resolve photo raw fallback fresh jpeg=${freshJpeg.folder}/${freshJpeg.fileName}',
          );
          return [freshJpeg];
        }
      }
      await appendLog(
        'resolve photo raw ignored no jpeg companion ${eventPhoto.folder}/${eventPhoto.fileName}',
      );
      return const [];
    }

    var fallback = _isJpegFileName(eventPhoto.fileName)
        ? <UsbCameraPhoto>[eventPhoto]
        : <UsbCameraPhoto>[];
    for (var attempt = 1; attempt <= 2; attempt += 1) {
      if (attempt > 1) {
        await Future<void>.delayed(const Duration(milliseconds: 600));
      }
      final fresh = await refreshPhotosIncremental();
      final realFresh = fresh
          .where((photo) => !_isTemporaryCaptureName(photo.fileName))
          .toList();
      if (realFresh.isNotEmpty) {
        await appendLog(
          'resolve photo fresh=${realFresh.map((photo) => '${photo.folder}/${photo.fileName}').join(',')}',
        );
        return realFresh;
      }
      if (fresh.isNotEmpty) {
        fallback = fresh;
        await appendLog(
          'resolve photo temporary direct=${fresh.map((photo) => '${photo.folder}/${photo.fileName}').join(',')}',
        );
        return fresh;
      }
      final matched = _findMatchingListedPhoto(eventPhoto);
      if (matched != null) {
        await appendLog(
          'resolve photo matched=${matched.folder}/${matched.fileName}',
        );
        return [matched];
      }
    }

    await appendLog(
      'resolve photo fallback=${fallback.map((photo) => '${photo.folder}/${photo.fileName}').join(',')}',
    );
    return fallback;
  }

  Future<String?> captureAndDownload() async {
    if (!isConnected || _captureInFlight) return null;
    _captureInFlight = true;
    final shouldResumeEvents = _eventListening;
    try {
      if (shouldResumeEvents) {
        await stopPhotoEventListening();
      }
      await appendLog('capture start');
      final beforeKeys = _photos.map(_photoPairKey).toSet();
      final capturedPath = await _repository.capture();
      await appendLog('capture path=$capturedPath');
      UsbCameraPhoto? capturedPhoto;
      var latest = <UsbCameraPhoto>[];
      for (var attempt = 1; attempt <= 3; attempt += 1) {
        if (attempt > 1) {
          await Future<void>.delayed(const Duration(milliseconds: 700));
        }
        latest = _preferJpegPhotos(await _repository.listPhotos());
        capturedPhoto = _findCapturedPhoto(capturedPath, latest);
        capturedPhoto ??= _findJpegCompanion(
          UsbCameraPhoto(
            id: capturedPath,
            fileName: capturedPath.split('/').last,
            shotAt: '--:--:--',
            sizeMb: 0,
            format: 'RAW',
            folder: capturedPath.contains('/')
                ? capturedPath.substring(0, capturedPath.lastIndexOf('/'))
                : '/',
          ),
          latest,
        );
        capturedPhoto ??= _findFirstNewPhoto(beforeKeys, latest);
        if (capturedPhoto != null) break;
      }
      _photos = latest;
      notifyListeners();

      if (capturedPhoto == null) {
        _errorMessage = '相机已触发拍摄，但未找到新增 JPEG 照片';
        await appendLog('capture failed no new jpeg path=$capturedPath');
        notifyListeners();
        return null;
      }
      return _repository.downloadPhoto(capturedPhoto);
    } on PlatformException catch (error) {
      _errorMessage = error.message ?? '相机拍摄失败';
      await appendLog('capture failed ${error.code}: ${_errorMessage ?? ''}');
      notifyListeners();
      return null;
    } catch (error) {
      _errorMessage = error.toString();
      await appendLog('capture failed $error');
      notifyListeners();
      return null;
    } finally {
      if (shouldResumeEvents && isConnected) {
        await startPhotoEventListening();
      }
      _captureInFlight = false;
    }
  }

  bool isRawPhotoName(String fileName) => _isRawFileName(fileName);

  bool isJpegPhotoName(String fileName) => _isJpegFileName(fileName);

  UsbCameraPhoto? findJpegCompanionFor(UsbCameraPhoto photo) {
    return _findJpegCompanion(photo, _photos);
  }

  Future<UsbCameraPhoto?> resolveJpegPhoto(UsbCameraPhoto photo) async {
    if (_isJpegFileName(photo.fileName)) return photo;
    if (!_isRawFileName(photo.fileName) || !isConnected) return null;
    for (var attempt = 1; attempt <= 3; attempt += 1) {
      if (attempt > 1) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
      await refreshPhotos();
      final companion = _findJpegCompanion(photo, _photos);
      if (companion != null) return companion;
    }
    return null;
  }

  bool _isJpegFileName(String fileName) {
    switch (_fileExtension(fileName)) {
      case 'jpg':
      case 'jpeg':
      case 'jpe':
        return true;
    }
    return false;
  }

  bool _isRawFileName(String fileName) {
    switch (_fileExtension(fileName)) {
      case 'arw':
      case 'raw':
      case 'dng':
      case 'cr2':
      case 'cr3':
      case 'nef':
      case 'raf':
      case 'orf':
      case 'rw2':
      case 'pef':
      case 'srw':
      case 'x3f':
        return true;
    }
    return false;
  }

  String _fileExtension(String fileName) {
    final index = fileName.lastIndexOf('.');
    if (index < 0 || index == fileName.length - 1) return '';
    return fileName.substring(index + 1).toLowerCase();
  }

  String _photoPairKey(UsbCameraPhoto photo) {
    final folder = photo.folder.trim().toLowerCase();
    final name = photo.fileName.trim().toLowerCase();
    final dot = name.lastIndexOf('.');
    final baseName = dot > 0 ? name.substring(0, dot) : name;
    final normalizedBase = baseName.startsWith('capt_')
        ? baseName.substring('capt_'.length)
        : baseName;
    return '$folder/$normalizedBase';
  }

  List<UsbCameraPhoto> _preferJpegPhotos(List<UsbCameraPhoto> photos) {
    final preferredByKey = <String, UsbCameraPhoto>{};
    for (final photo in photos) {
      final isJpeg = _isJpegFileName(photo.fileName);
      final isRaw = _isRawFileName(photo.fileName);
      if (!isJpeg && !isRaw) continue;
      final key = _photoPairKey(photo);
      final current = preferredByKey[key];
      if (isJpeg || current == null || !_isJpegFileName(current.fileName)) {
        preferredByKey[key] = photo;
      }
    }
    final seen = <String>{};
    return [
      for (final photo in photos)
        if (_isJpegFileName(photo.fileName) &&
            identical(preferredByKey[_photoPairKey(photo)], photo) &&
            seen.add(_photoPairKey(photo)))
          photo,
    ];
  }

  UsbCameraPhoto? _findJpegCompanion(
    UsbCameraPhoto photo,
    List<UsbCameraPhoto> photos,
  ) {
    final key = _photoPairKey(photo);
    for (final candidate in photos) {
      if (_photoPairKey(candidate) == key &&
          _isJpegFileName(candidate.fileName)) {
        return candidate;
      }
    }
    return null;
  }

  bool _isTemporaryCaptureName(String fileName) {
    return fileName.toLowerCase().startsWith('capt_');
  }

  UsbCameraPhoto? _findMatchingListedPhoto(UsbCameraPhoto eventPhoto) {
    if (_isRawFileName(eventPhoto.fileName)) {
      return _findJpegCompanion(eventPhoto, _photos);
    }
    final eventName = eventPhoto.fileName.toLowerCase();
    final normalizedEventName = eventName.startsWith('capt_')
        ? eventName.substring('capt_'.length)
        : eventName;
    final eventBase = normalizedEventName.split('.').first;
    for (final photo in _photos) {
      if (!_isJpegFileName(photo.fileName)) continue;
      if (photo.id == eventPhoto.id) return photo;
      if (photo.folder == eventPhoto.folder &&
          photo.fileName == eventPhoto.fileName) {
        return photo;
      }
      final listedName = photo.fileName.toLowerCase();
      final listedBase = listedName.split('.').first;
      if (listedName == normalizedEventName || listedBase == eventBase) {
        return photo;
      }
    }
    return null;
  }

  UsbCameraPhoto? _findCapturedPhoto(
    String capturedPath,
    List<UsbCameraPhoto> photos,
  ) {
    final normalized = capturedPath.trim();
    if (normalized.isEmpty || !normalized.contains('/')) return null;
    for (final photo in photos) {
      final path = '${photo.folder}/${photo.fileName}'.replaceAll('//', '/');
      if (path == normalized || path.endsWith(normalized)) return photo;
    }
    return null;
  }

  UsbCameraPhoto? _findFirstNewPhoto(
    Set<String> beforeKeys,
    List<UsbCameraPhoto> photos,
  ) {
    for (final photo in photos) {
      if (!beforeKeys.contains(_photoPairKey(photo))) return photo;
    }
    return null;
  }

  Future<String?> downloadPhoto(UsbCameraPhoto photo) async {
    try {
      var targetPhoto = photo;
      if (_isRawFileName(photo.fileName)) {
        final companion = await resolveJpegPhoto(photo);
        if (companion == null) {
          _errorMessage = '未找到对应 JPEG 文件，已跳过 RAW 下载';
          await appendLog(
            'download raw skipped no jpeg companion ${photo.folder}/${photo.fileName}',
          );
          notifyListeners();
          return null;
        }
        targetPhoto = companion;
        await appendLog(
          'download raw redirected jpeg ${targetPhoto.folder}/${targetPhoto.fileName}',
        );
      }
      if (!_isJpegFileName(targetPhoto.fileName)) {
        _errorMessage = '当前仅支持下载 JPEG 照片';
        notifyListeners();
        return null;
      }
      return await _repository.downloadPhoto(targetPhoto);
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
      return null;
    }
  }

  Future<void> _run(Future<void> Function() action) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();
    try {
      await action();
    } on PlatformException catch (error) {
      _errorMessage = error.message ?? 'USB 相机操作失败';
      _status = CameraConnectionStatus.failed;
    } catch (error) {
      _errorMessage = error.toString();
      _status = CameraConnectionStatus.failed;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  void _handleNativeEvent(Map<dynamic, dynamic> event) {
    final type = event['type']?.toString();
    final payload = event['payload'];
    switch (type) {
      case 'deviceAttached':
        if (payload is Map<dynamic, dynamic>) {
          final device = UsbCameraDevice.fromMap(payload);
          _devices = [
            device,
            ..._devices.where((item) => item.deviceName != device.deviceName)
          ];
        }
        break;
      case 'deviceDetached':
        if (payload is Map<dynamic, dynamic>) {
          final device = UsbCameraDevice.fromMap(payload);
          _devices = _devices
              .where((item) => item.deviceName != device.deviceName)
              .toList();
          if (_connectedDevice?.deviceName == device.deviceName) {
            _connectedDevice = null;
            _photos = [];
            _status = CameraConnectionStatus.disconnected;
          }
        }
        break;
      case 'connected':
        if (payload is Map<dynamic, dynamic>) {
          _connectedDevice = UsbCameraDevice.fromMap(payload);
          _status = CameraConnectionStatus.connected;
        }
        break;
      case 'photoAdded':
        if (payload is Map<dynamic, dynamic>) {
          final photo = UsbCameraPhoto.fromMap(payload);
          if (_isRawFileName(photo.fileName)) {
            _addedPhotoController.add(photo);
            break;
          }
          if (!_isJpegFileName(photo.fileName)) break;
          _addedPhotoController.add(photo);
          final exists = _photos.any((item) =>
              item.id == photo.id ||
              (item.folder == photo.folder && item.fileName == photo.fileName));
          if (!exists) {
            _photos = [photo, ..._photos];
          }
        }
        break;
      case 'disconnected':
        _connectedDevice = null;
        _photos = [];
        _status = CameraConnectionStatus.disconnected;
        break;
      case 'permissionDenied':
        _status = CameraConnectionStatus.permissionRequired;
        _errorMessage = 'USB 相机授权被拒绝';
        break;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    unawaited(stopPhotoEventListening());
    _eventSubscription?.cancel();
    _addedPhotoController.close();
    super.dispose();
  }
}
