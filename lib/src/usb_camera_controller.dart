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

  var _status = CameraConnectionStatus.disconnected;
  var _devices = <UsbCameraDevice>[];
  var _photos = <UsbCameraPhoto>[];
  UsbCameraDevice? _connectedDevice;
  String? _errorMessage;
  bool _isLoading = false;

  CameraConnectionStatus get status => _status;
  List<UsbCameraDevice> get devices => List.unmodifiable(_devices);
  List<UsbCameraPhoto> get photos => List.unmodifiable(_photos);
  UsbCameraDevice? get connectedDevice => _connectedDevice;
  String? get errorMessage => _errorMessage;
  bool get isLoading => _isLoading;
  bool get isConnected => _status == CameraConnectionStatus.connected;
  bool get isFailed => _status == CameraConnectionStatus.failed;
  String get cameraModel => _connectedDevice?.displayName ?? 'USB 相机';

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
      await refreshPhotos();
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

  Future<void> disconnect() async {
    await _repository.disconnect();
    _connectedDevice = null;
    _photos = [];
    _status = _repository.isSupported
        ? CameraConnectionStatus.disconnected
        : CameraConnectionStatus.unsupported;
    notifyListeners();
  }

  Future<void> refreshPhotos() async {
    if (!isConnected) return;
    await _run(() async {
      _photos = await _repository.listPhotos();
    });
  }

  Future<List<UsbCameraPhoto>> refreshPhotosIncremental() async {
    if (!isConnected) return const [];
    try {
      final currentIds = _photos.map((photo) => photo.id).toSet();
      final latest = await _repository.listPhotos();
      final fresh =
          latest.where((photo) => !currentIds.contains(photo.id)).toList();
      if (fresh.isNotEmpty || latest.length != _photos.length) {
        _photos = [
          ...fresh,
          for (final photo in _photos)
            latest.firstWhere(
              (item) => item.id == photo.id,
              orElse: () => photo,
            ),
        ];
        final seen = <String>{};
        _photos = [
          for (final photo in _photos)
            if (seen.add(photo.id)) photo,
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

  Future<String?> captureAndDownload() async {
    if (!isConnected) return null;
    try {
      final beforeIds = _photos.map((photo) => photo.id).toSet();
      final capturedPath = await _repository.capture();
      final latest = await _repository.listPhotos();
      _photos = latest;
      notifyListeners();

      UsbCameraPhoto? capturedPhoto = _findCapturedPhoto(capturedPath, latest);
      if (capturedPhoto == null) {
        for (final photo in latest) {
          if (!beforeIds.contains(photo.id)) {
            capturedPhoto = photo;
            break;
          }
        }
      }
      if (capturedPhoto == null) return null;
      return _repository.downloadPhoto(capturedPhoto);
    } catch (error) {
      _errorMessage = error.toString();
      notifyListeners();
      return null;
    }
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

  Future<String?> downloadPhoto(UsbCameraPhoto photo) async {
    try {
      return await _repository.downloadPhoto(photo);
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
    _eventSubscription?.cancel();
    super.dispose();
  }
}
