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

## Android development

Open `app/android` for Android development with the Flutter embedding dependencies.
The SDK's `android` directory also supports standalone Gradle configuration and
IDE import with JDK 17 and Gradle 9.1.0. Set `ANDROID_HOME` or `sdk.dir` in a local,
untracked `android/local.properties` file to locate the Android SDK.

From the repository root:

```bash
# Check standalone configuration using the app's Gradle wrapper.
app/android/gradlew -p packages/usb_camera_sdk/android help

# Compile and test the Android plugin through its Flutter host.
app/android/gradlew -p app/android :usb_camera_sdk:testDebugUnitTest
```

Standalone plugin versions and AGP compatibility flags match `app/android`.
Standalone SDK defaults are compile SDK 36, minimum SDK 24, and NDK
28.2.13676358; when loaded by a Flutter app, the SDK uses Flutter's values instead.
