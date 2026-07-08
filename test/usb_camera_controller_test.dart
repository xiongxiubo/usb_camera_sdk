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
  var startPhotoEventListeningCalls = 0;
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
  Future<List<UsbCameraPhoto>> listPhotos({String folder = '/'}) async =>
      photos;

  @override
  Future<void> disconnect() async {}

  @override
  Future<void> startPhotoEventListening() async {
    startPhotoEventListeningCalls += 1;
  }

  @override
  Future<void> stopPhotoEventListening() async {}

  @override
  Future<void> appendCameraLog(String message) async {}

  @override
  Future<List<UsbCameraPhoto>> drainPhotoEvents() async => const [];

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

  const canonDevice = UsbCameraDevice(
    deviceName: 'canon-1',
    vendorId: 0x04a9,
    productId: 2,
    productName: 'Canon Digital Camera',
  );

  UsbCameraPhoto photo(String id, String fileName, {String format = 'JPG'}) {
    return UsbCameraPhoto(
      id: id,
      fileName: fileName,
      shotAt: '10:00:00',
      sizeMb: 5,
      format: format,
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

  test('camera capabilities map automatic and manual sync ingestion', () async {
    expect(
        device.capabilities.ingestionMode, CameraIngestionMode.eventAndPolling);
    expect(device.capabilities.supportsAutomaticIngestion, isTrue);
    expect(
        canonDevice.capabilities.ingestionMode, CameraIngestionMode.manualSync);
    expect(canonDevice.capabilities.supportsAutomaticIngestion, isFalse);
  });

  test('manual sync connect skips initial photo refresh and event listening',
      () async {
    final repository = FakeUsbCameraRepository(
      devices: const [canonDevice],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);

    final connected = await controller.connectFirstAvailable();

    expect(connected, isTrue);
    expect(
        controller.capabilities.ingestionMode, CameraIngestionMode.manualSync);
    expect(controller.supportsAutomaticPhotoIngestion, isFalse);
    expect(controller.photos, isEmpty);

    await controller.startPhotoEventListening();

    expect(repository.startPhotoEventListeningCalls, 0);

    await controller.startPassivePhotoEventExperiment();

    expect(repository.startPhotoEventListeningCalls, 1);
    expect(controller.isPhotoEventListening, isTrue);
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

  test('refreshPhotosIncremental returns only new photos and de-duplicates',
      () async {
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

  test('automatic ingestion starts native photo event listening', () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    expect(controller.capabilities.ingestionMode,
        CameraIngestionMode.eventAndPolling);
    expect(controller.supportsAutomaticPhotoIngestion, isTrue);

    await controller.startPhotoEventListening();

    expect(repository.startPhotoEventListeningCalls, 1);
  });

  test('photoAdded native event updates photos and emits added photo',
      () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    final added = controller.addedPhotos.first;
    repository.emit({
      'type': 'photoAdded',
      'payload': {
        'id': 'p2',
        'folder': '/',
        'fileName': 'IMG_2.JPG',
        'shotAt': '10:01:00',
        'sizeMb': 6,
        'format': 'JPG',
      },
    });

    expect((await added).id, 'p2');
    expect(controller.photos.map((item) => item.id), ['p2', 'p1']);
  });

  test('resolveAddedPhoto prefers JPEG companion for RAW capture events',
      () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    repository.photos = [
      photo('p2', 'DSC00770.ARW', format: 'RAW'),
      photo('p3', 'DSC00770.JPG'),
      photo('p1', 'IMG_1.JPG'),
    ];
    final resolved = await controller.resolveAddedPhoto(
      photo('capt', 'capt_DSC00770.ARW', format: 'RAW'),
    );

    expect(resolved.map((item) => item.fileName), ['DSC00770.JPG']);
    expect(controller.photos.map((item) => item.id), ['p3', 'p1']);
  });

  test('resolveAddedPhoto ignores RAW events without JPEG companion', () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    repository.photos = [
      photo('p2', 'DSC00770.ARW', format: 'RAW'),
      photo('p1', 'IMG_1.JPG'),
    ];
    final resolved = await controller.resolveAddedPhoto(
      photo('capt', 'capt_DSC00770.ARW', format: 'RAW'),
    );

    expect(resolved, isEmpty);
    expect(controller.photos.map((item) => item.id), ['p1']);
  });

  test('resolveAddedPhoto falls back to newly listed JPEG for RAW event',
      () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    repository.photos = [
      photo('p2', 'DSC00888.JPG'),
      photo('p1', 'IMG_1.JPG'),
    ];
    final resolved = await controller.resolveAddedPhoto(
      photo('capt', 'capt_DSC00770.ARW', format: 'RAW'),
    );

    expect(resolved.map((item) => item.fileName), ['DSC00888.JPG']);
    expect(controller.photos.map((item) => item.id), ['p2', 'p1']);
  });

  test('raw-only scan waits until JPEG appears in incremental refresh',
      () async {
    final repository = FakeUsbCameraRepository(
      devices: const [device],
      photos: [photo('p1', 'IMG_1.JPG')],
    );
    addTearDown(repository.close);
    final controller = UsbCameraController(repository: repository);
    addTearDown(controller.dispose);
    await controller.connectFirstAvailable();

    repository.photos = [
      photo('p2', 'DSC00999.ARW', format: 'RAW'),
      photo('p1', 'IMG_1.JPG'),
    ];
    final rawOnlyFresh = await controller.refreshPhotosIncremental();

    expect(rawOnlyFresh, isEmpty);
    expect(controller.photos.map((item) => item.id), ['p1']);

    repository.photos = [
      photo('p2', 'DSC00999.ARW', format: 'RAW'),
      photo('p3', 'DSC00999.JPG'),
      photo('p1', 'IMG_1.JPG'),
    ];
    final jpegFresh = await controller.refreshPhotosIncremental();

    expect(jpegFresh.map((item) => item.fileName), ['DSC00999.JPG']);
    expect(controller.photos.map((item) => item.id), ['p3', 'p1']);
  });
}
