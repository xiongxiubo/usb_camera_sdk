import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'usb_camera_models.dart';

class UsbCameraRepository {
  UsbCameraRepository({
    MethodChannel? methodChannel,
    EventChannel? eventChannel,
  })  : _methodChannel =
            methodChannel ?? const MethodChannel('flyphoto/camera_usb'),
        _eventChannel =
            eventChannel ?? const EventChannel('flyphoto/camera_usb_events');

  final MethodChannel _methodChannel;
  final EventChannel _eventChannel;

  Stream<Map<dynamic, dynamic>>? _events;

  bool get isSupported =>
      !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  Stream<Map<dynamic, dynamic>> get events {
    if (!isSupported) return const Stream.empty();
    return _events ??= _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map<dynamic, dynamic>)
        .cast<Map<dynamic, dynamic>>();
  }

  Future<List<UsbCameraDevice>> listDevices() async {
    if (!isSupported) return const [];
    final result =
        await _methodChannel.invokeListMethod<dynamic>('listDevices');
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraDevice.fromMap)
        .toList();
  }

  Future<bool> requestPermission(UsbCameraDevice device) async {
    if (!isSupported) return false;
    final result = await _methodChannel.invokeMethod<bool>(
      'requestPermission',
      {'deviceName': device.deviceName},
    );
    return result ?? false;
  }

  Future<UsbCameraDevice> connect(UsbCameraDevice device) async {
    if (!isSupported)
      throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    final result = await _methodChannel.invokeMapMethod<dynamic, dynamic>(
      'connect',
      {'deviceName': device.deviceName},
    );
    if (result == null)
      throw const UsbCameraException('connect_failed', '相机连接失败');
    return UsbCameraDevice.fromMap(result);
  }

  Future<void> disconnect() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('disconnect');
  }

  Future<void> releaseCameraControl() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('releaseCameraControl');
  }

  Future<String> capture() async {
    if (!isSupported)
      throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    return await _methodChannel.invokeMethod<String>('capture') ?? '';
  }

  Future<List<UsbCameraPhoto>> listPhotos({String folder = '/'}) async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'listPhotos',
      {'folder': folder},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .toList();
  }

  Future<List<UsbCameraMediaFile>> listMedia({String folder = '/'}) async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'listMedia',
      {'folder': folder},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .where((file) => file.isSupportedMedia)
        .toList();
  }

  Future<List<UsbCameraPhoto>> listNewPhotos({String folder = '/'}) async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'listNewPhotos',
      {'folder': folder},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .toList();
  }

  Future<List<UsbCameraMediaFile>> listNewMedia({String folder = '/'}) async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'listNewMedia',
      {'folder': folder},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .where((file) => file.isSupportedMedia)
        .toList();
  }

  Future<List<UsbCameraPhoto>> drainPhotoEvents() async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'drainPhotoEvents',
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .toList();
  }

  Future<List<UsbCameraMediaFile>> drainMediaEvents() async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>(
      'drainMediaEvents',
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(UsbCameraPhoto.fromMap)
        .where((file) => file.isSupportedMedia)
        .toList();
  }

  Future<void> startPhotoEventListening() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('startPhotoEventListening');
  }

  Future<void> startMediaEventListening() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('startMediaEventListening');
  }

  Future<void> stopPhotoEventListening() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('stopPhotoEventListening');
  }

  Future<void> stopMediaEventListening() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('stopMediaEventListening');
  }

  Future<void> appendCameraLog(String message) async {
    if (!isSupported || message.trim().isEmpty) return;
    await _methodChannel.invokeMethod<void>('appendCameraLog', {
      'message': message.trim(),
    });
  }

  Future<String> downloadPhoto(UsbCameraPhoto photo) async {
    if (!isSupported)
      throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    return await _methodChannel.invokeMethod<String>('downloadPhoto', {
          'id': photo.id,
          'folder': photo.folder,
          'name': photo.fileName,
        }) ??
        '';
  }

  Future<String> downloadMedia(UsbCameraMediaFile media) async {
    if (!isSupported)
      throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    return await _methodChannel.invokeMethod<String>('downloadMedia', {
          'id': media.id,
          'folder': media.folder,
          'name': media.fileName,
        }) ??
        '';
  }
}

class UsbCameraException implements Exception {
  const UsbCameraException(this.code, this.message);

  final String code;
  final String message;

  @override
  String toString() => message;
}
