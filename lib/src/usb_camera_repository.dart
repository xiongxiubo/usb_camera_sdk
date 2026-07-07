import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'usb_camera_models.dart';

class UsbCameraRepository {
  UsbCameraRepository({
    MethodChannel? methodChannel,
    EventChannel? eventChannel,
  })  : _methodChannel = methodChannel ?? const MethodChannel('flyphoto/camera_usb'),
        _eventChannel = eventChannel ?? const EventChannel('flyphoto/camera_usb_events');

  final MethodChannel _methodChannel;
  final EventChannel _eventChannel;

  Stream<Map<dynamic, dynamic>>? _events;

  bool get isSupported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  Stream<Map<dynamic, dynamic>> get events {
    if (!isSupported) return const Stream.empty();
    return _events ??= _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is Map<dynamic, dynamic>)
        .cast<Map<dynamic, dynamic>>();
  }

  Future<List<UsbCameraDevice>> listDevices() async {
    if (!isSupported) return const [];
    final result = await _methodChannel.invokeListMethod<dynamic>('listDevices');
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
    if (!isSupported) throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    final result = await _methodChannel.invokeMapMethod<dynamic, dynamic>(
      'connect',
      {'deviceName': device.deviceName},
    );
    if (result == null) throw const UsbCameraException('connect_failed', '相机连接失败');
    return UsbCameraDevice.fromMap(result);
  }

  Future<void> disconnect() async {
    if (!isSupported) return;
    await _methodChannel.invokeMethod<void>('disconnect');
  }

  Future<String> capture() async {
    if (!isSupported) throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
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

  Future<String> downloadPhoto(UsbCameraPhoto photo) async {
    if (!isSupported) throw const UsbCameraException('unsupported', '当前平台不支持 USB 相机');
    return await _methodChannel.invokeMethod<String>('downloadPhoto', {
          'folder': photo.folder,
          'name': photo.fileName,
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
