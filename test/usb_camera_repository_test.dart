import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:usb_camera_sdk/usb_camera_sdk.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('UsbCameraRepository parses method channel responses', () async {
    debugDefaultTargetPlatformOverride = TargetPlatform.android;
    addTearDown(() => debugDefaultTargetPlatformOverride = null);
    const channel = MethodChannel('test_usb_camera');
    final calls = <MethodCall>[];

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'listDevices':
          return [
            {
              'deviceName': 'camera-1',
              'vendorId': 1,
              'productId': 2,
              'productName': 'Test Camera',
            }
          ];
        case 'requestPermission':
          return true;
        case 'connect':
          return {
            'deviceName': 'camera-1',
            'vendorId': 1,
            'productId': 2,
            'productName': 'Test Camera',
          };
        case 'listPhotos':
        case 'listNewPhotos':
          return [
            {
              'id': 'p1',
              'folder': '/',
              'fileName': 'IMG_1.JPG',
              'sizeMb': 5,
              'format': 'JPG',
              'shotAt': '10:00:00',
            }
          ];
        case 'listMedia':
        case 'listNewMedia':
          return [
            {
              'id': 'v1',
              'folder': '/DCIM',
              'fileName': 'VID_1.MP4',
              'sizeMb': 80,
              'format': 'MP4',
              'mediaType': 'video',
              'mimeType': 'video/mp4',
              'shotAt': '10:01:00',
            }
          ];
        case 'downloadPhoto':
          return '/cache/IMG_1.JPG';
        case 'downloadMedia':
          return '/cache/VID_1.MP4';
      }
      return null;
    });

    final repository = UsbCameraRepository(methodChannel: channel);
    final devices = await repository.listDevices();
    final granted = await repository.requestPermission(devices.first);
    final connected = await repository.connect(devices.first);
    final photos = await repository.listPhotos();
    final newPhotos = await repository.listNewPhotos();
    final downloadedPath = await repository.downloadPhoto(photos.first);
    final media = await repository.listMedia();
    final newMedia = await repository.listNewMedia();
    final downloadedMediaPath = await repository.downloadMedia(media.first);

    expect(devices.single.displayName, 'Test Camera');
    expect(granted, isTrue);
    expect(connected.deviceName, 'camera-1');
    expect(photos.single.fileName, 'IMG_1.JPG');
    expect(newPhotos.single.fileName, 'IMG_1.JPG');
    expect(downloadedPath, '/cache/IMG_1.JPG');
    expect(media.single.isVideo, isTrue);
    expect(newMedia.single.mimeType, 'video/mp4');
    expect(downloadedMediaPath, '/cache/VID_1.MP4');
    expect(calls.map((call) => call.method), [
      'listDevices',
      'requestPermission',
      'connect',
      'listPhotos',
      'listNewPhotos',
      'downloadPhoto',
      'listMedia',
      'listNewMedia',
      'downloadMedia',
    ]);
    expect(calls[1].arguments, {'deviceName': 'camera-1'});
    expect(calls[5].arguments, {
      'id': 'p1',
      'folder': '/',
      'name': 'IMG_1.JPG',
    });
    expect(calls[8].arguments, {
      'id': 'v1',
      'folder': '/DCIM',
      'name': 'VID_1.MP4',
    });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
