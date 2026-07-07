# USB Camera SDK

Reusable Flutter USB camera SDK for Android USB Host devices backed by the existing FlyPhoto libgphoto2 bridge.

This package currently provides the Dart API boundary only. The first migration stage keeps the host app's existing Android platform-channel implementation in place and uses the same channel names:

- `flyphoto/camera_usb`
- `flyphoto/camera_usb_events`

The Android native implementation will be moved into this package in the next stage.
