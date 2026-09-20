# Repeat Timer v0.0.7-alpha

First public release.

## Features
- Repeating interval timer: alarms at the end of each interval
- Configurable interval duration (default: 15 minutes)
- Configurable number of intervals (default: 4)
- Live countdown to the next alarm
- Remaining intervals counter
- Foreground service with a persistent notification
- Stop alarm / stop whole series buttons right in the notification
- Exact alarm delivery, even when the screen is off (AlarmManager + USE_EXACT_ALARM)
- Custom alarm sound: system ringtone or your own MP3 file
- Localization: English and Russian

## Tech
- Minimum Android: 7.0 (API 24)
- Target Android: 14 (API 34)
- Package: `io.bbs.seva.repeattimer`
