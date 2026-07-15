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
        case 'downloadPhoto':
          return '/cache/IMG_1.JPG';
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

    expect(devices.single.displayName, 'Test Camera');
    expect(granted, isTrue);
    expect(connected.deviceName, 'camera-1');
    expect(photos.single.fileName, 'IMG_1.JPG');
    expect(newPhotos.single.fileName, 'IMG_1.JPG');
    expect(downloadedPath, '/cache/IMG_1.JPG');
    expect(calls.map((call) => call.method), [
      'listDevices',
      'requestPermission',
      'connect',
      'listPhotos',
      'listNewPhotos',
      'downloadPhoto',
    ]);
    expect(calls[1].arguments, {'deviceName': 'camera-1'});
    expect(calls[5].arguments, {
      'id': 'p1',
      'folder': '/',
      'name': 'IMG_1.JPG',
    });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
}
