import 'package:flutter_test/flutter_test.dart';
import 'package:usb_camera_sdk/usb_camera_sdk.dart';

void main() {
  group('UsbCameraDevice', () {
    test('parses device values from a platform map', () {
      final device = UsbCameraDevice.fromMap({
        'deviceName': '/dev/bus/usb/001/002',
        'vendorId': '1200',
        'productId': 45.8,
        'deviceClass': 6,
        'deviceSubclass': '1',
        'deviceProtocol': null,
        'manufacturerName': 'Canon',
        'productName': 'EOS R6',
        'serialNumber': 12345,
      });

      expect(device.deviceName, '/dev/bus/usb/001/002');
      expect(device.vendorId, 1200);
      expect(device.productId, 45);
      expect(device.deviceClass, 6);
      expect(device.deviceSubclass, 1);
      expect(device.deviceProtocol, isNull);
      expect(device.manufacturerName, 'Canon');
      expect(device.productName, 'EOS R6');
      expect(device.serialNumber, '12345');
      expect(device.displayName, 'EOS R6');
    });
  });

  group('UsbCameraPhoto', () {
    test('parses photo values from a platform map', () {
      final photo = UsbCameraPhoto.fromMap({
        'folder': '/store_00010001/DCIM/100CANON',
        'fileName': 'IMG_0001.cr3',
        'sizeMb': '42',
        'shotAt': '12:30:05',
        'localPath': '/tmp/IMG_0001.cr3',
      });

      expect(photo.id, '/store_00010001/DCIM/100CANON/IMG_0001.cr3');
      expect(photo.fileName, 'IMG_0001.cr3');
      expect(photo.sizeMb, 42);
      expect(photo.shotAt, '12:30:05');
      expect(photo.format, 'CR3');
      expect(photo.folder, '/store_00010001/DCIM/100CANON');
      expect(photo.localPath, '/tmp/IMG_0001.cr3');
    });
  });
}
