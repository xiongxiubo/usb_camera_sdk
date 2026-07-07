import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:usb_camera_sdk/usb_camera_sdk.dart';

class FakeUsbCameraRepository extends UsbCameraRepository {
  FakeUsbCameraRepository({
    this.supported = true,
    this.permissionGranted = true,
    List<UsbCameraDevice>? devices,
    List<UsbCameraPhoto>? photos,
  })  : devices = devices ?? const [],
        photos = photos ?? const [],
        super(
          methodChannel: const MethodChannel('fake_usb_camera'),
          eventChannel: const EventChannel('fake_usb_camera_events'),
        );

  final bool supported;
  bool permissionGranted;
  List<UsbCameraDevice> devices;
  List<UsbCameraPhoto> photos;
  final _events = StreamController<Map<dynamic, dynamic>>.broadcast();

  @override
  bool get isSupported => supported;

  @override
  Stream<Map<dynamic, dynamic>> get events => _events.stream;

  @override
  Future<List<UsbCameraDevice>> listDevices() async => devices;

  @override
  Future<bool> requestPermission(UsbCameraDevice device) async {
    return permissionGranted;
  }

  @override
  Future<UsbCameraDevice> connect(UsbCameraDevice device) async => device;

  @override
  Future<List<UsbCameraPhoto>> listPhotos({String folder = '/'}) async => photos;

  @override
  Future<void> disconnect() async {}

  void emit(Map<dynamic, dynamic> event) => _events.add(event);

  Future<void> close() => _events.close();
}

void main() {
  const device = UsbCameraDevice(
    deviceName: 'camera-1',
    vendorId: 1,
    productId: 2,
    productName: 'Test Camera',
  );

  UsbCameraPhoto photo(String id, String fileName) {
    return UsbCameraPhoto(
      id: id,
      fileName: fileName,
      shotAt: '10:00:00',
      sizeMb: 5,
      format: 'JPG',
      folder: '/',
    );
  }

  test('connectFirstAvailable connects and refreshes photos', () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);

    final connected = await controller.connectFirstAvailable();

    expect(connected, isTrue);
    expect(controller.status, CameraConnectionStatus.connected);
    expect(controller.connectedDevice?.deviceName, 'camera-1');
    expect(controller.photos.single.fileName, 'IMG_1.JPG');
  });

  test('connectFirstAvailable reports permission required', () async {
    final repository = FakeUsbCameraRepository(
      permissionGranted: false,
      devices: const [device],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);

    final connected = await controller.connectFirstAvailable();

    expect(connected, isFalse);
    expect(controller.status, CameraConnectionStatus.permissionRequired);
    expect(controller.errorMessage, '需要授权访问 USB 相机');
  });

  test('refreshPhotosIncremental returns only new photos and de-duplicates', () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    repository.photos = [photo('p2', 'IMG_2.JPG'), photo('p1', 'IMG_1.JPG')];
    final fresh = await controller.refreshPhotosIncremental();

    expect(fresh.map((item) => item.id), ['p2']);
    expect(controller.photos.map((item) => item.id), ['p2', 'p1']);
  });
}
