# USB Camera SDK

Reusable Flutter USB camera SDK for Android USB Host devices. Canon cameras use
the SDK-owned EOS PTP backend with a libgphoto2 fallback; other supported cameras
use libgphoto2.

The SDK discovers camera files, listens for newly created files, and transfers
them to the Android application cache. It supports JPEG/RAW image ingestion and
common camera video formats: MP4, MOV, M4V, AVI, MTS/M2TS, and WebM.

Use the generic media API when videos are required:

```dart
final controller = UsbCameraController(repository: UsbCameraRepository());
await controller.connectFirstAvailable();
await controller.startMediaEventListening();

controller.addedMedia.listen((media) async {
  final localPath = await controller.downloadMedia(media);
  // Pass localPath, media.fileName, and media.mimeType to your uploader.
});
```

Existing photo-only integrations remain supported through `photos`,
`addedPhotos`, `listPhotos`, and `downloadPhoto`. Video files are exposed only
through `mediaFiles`, `videos`, `addedMedia`/`addedVideos`, `listMedia`, and
`downloadMedia`, so they cannot be mistaken for JPEG photos by legacy callers.

The platform channels are:

- `flyphoto/camera_usb`
- `flyphoto/camera_usb_events`
