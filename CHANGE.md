# Repeat Timer 0.0.9-alpha

## What's new

- Request `READ_MEDIA_AUDIO` permission (Android 13+) / `READ_EXTERNAL_STORAGE` (Android 12 and below) so a custom MP3 alarm sound can be played reliably
- Minor fixes

## Known limitations

- Custom sound is chosen with the system ringtone picker, so the app needs audio read permission to keep access to the file between launches
- On some OEMs (Xiaomi/MIUI/HyperOS) the system may revoke this permission for idle apps — in that case the timer falls back to the default system alarm sound

## Tech

- Minimum Android: 7.0 (API 24)
- Target Android: 14 (API 34)
- Package: `io.github.vms2020.repeattimer`
