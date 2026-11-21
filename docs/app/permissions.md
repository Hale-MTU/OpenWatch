# Permissions

OpenWatch asks for permissions by purpose. The app removes inherited network permissions
during manifest merge and ships no `INTERNET`, network-state or Wi-Fi-state permission.

This page mirrors `app/src/main/AndroidManifest.xml` and, for the watch app, `wear/src/main/AndroidManifest.xml`.

## Health Connect Write Permissions

What a watch sync writes: `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_EXERCISE`,
`WRITE_EXERCISE_ROUTE`, `WRITE_ELEVATION_GAINED`, `WRITE_ACTIVE_CALORIES_BURNED`,
`WRITE_TOTAL_CALORIES_BURNED`, `WRITE_HEART_RATE`, `WRITE_RESTING_HEART_RATE`,
`WRITE_HEART_RATE_VARIABILITY`, `WRITE_BASAL_METABOLIC_RATE`, `WRITE_FLOORS_CLIMBED`,
`WRITE_WHEELCHAIR_PUSHES`, `WRITE_SPEED`, `WRITE_POWER`,
`WRITE_OXYGEN_SATURATION`, `WRITE_RESPIRATORY_RATE`, `WRITE_SKIN_TEMPERATURE`,
`WRITE_VO2_MAX`, `WRITE_SLEEP`. Steps cadence falls under `WRITE_STEPS` and cycling cadence under `WRITE_EXERCISE`:
Health Connect has no separate cadence permission.

Three of these are written only for a watch that measures them and only when the user
switched the option on: `WRITE_RESPIRATORY_RATE` from a Wear OS watch (the "Breathing rate from
the watch" switch in Settings), and `WRITE_OXYGEN_SATURATION` and `WRITE_SKIN_TEMPERATURE` from a Samsung
watch (the "SpO2 hourly at night" and "Skin temperature at night" switches on the watch's
settings card).
Skin temperature is one record per night, the difference from the wearer's own usual night.
A Garmin watch's oxygen saturation and respiratory rate come with its files.

What a scale weigh-in writes: `WRITE_WEIGHT`, `WRITE_BODY_FAT`, `WRITE_LEAN_BODY_MASS`,
`WRITE_BODY_WATER_MASS`.

A record whose write is not granted stays on the device; the rest of a sync goes on.

## Health Connect Read Permissions

What the estimates read: `READ_WEIGHT` and `READ_HEIGHT` (the walking energy and the body
composition), `READ_EXERCISE` (a minute inside a workout gets no walking-energy estimate),
`READ_SLEEP` (a night that already holds a sleep session is left alone),
`READ_RESTING_HEART_RATE` (a day that already holds a resting heart rate is left alone, and
the heart rate zones the watch shows), `READ_HEART_RATE` (the highest heart rate, for those
zones, and a workout's energy). `READ_HEALTH_DATA_HISTORY`: the latest weight and height are
often older than the 30 days Health Connect shows without it. `READ_STEPS` is declared and
not used by this version.

`READ_HEALTH_DATA_IN_BACKGROUND`: a scheduled sync with the app closed can only see other
apps' weights, workouts and sleep with this grant. Without it the sync still writes the
samples, but no estimate that depends on another app's data.

## The Watch App's Permissions

The Wear OS app records with the watch's own sensors, so its permissions are the watch's,
asked on the wrist:

- `android.permission.health.READ_HEART_RATE` on Wear OS 6 (Android 16), `BODY_SENSORS`
  before it, with `READ_HEALTH_DATA_IN_BACKGROUND` or `BODY_SENSORS_BACKGROUND`: the heart
  rate sensor, also behind the night's HRV and the sleep estimate.
- `ACTIVITY_RECOGNITION`: the step counter.
- `android.permission.health.READ_SKIN_TEMPERATURE` and `READ_OXYGEN_SATURATION`: asked only
  when the user switches on "Skin temperature at night" or "SpO2 hourly at night" on a watch that has the
  Samsung Health Platform, which checks them per tracker.
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` and `FOREGROUND_SERVICE_LOCATION`: a
  workout recorded with GPS on the watch, and only while it runs.
- `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
  `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
  `VIBRATE`: the link to the phone and the recording service.

The watch app removes the same three network permissions as the phone app.

## Bluetooth Permissions

`BLUETOOTH_SCAN` (never for location) and `BLUETOOTH_CONNECT` on Android 12 and later;
`BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION` on Android 11 and older, where a
scan needs them.

## Companion Device Permissions

`REQUEST_COMPANION_RUN_IN_BACKGROUND`, `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`
and `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`: install-time grants with no prompt of their
own. The user's consent is the system "Allow OpenWatch to access <device>?" dialog. The
association keeps the app alive while a watch is in range, so a file sync that runs for
minutes is not killed; for the scale, it is what lets Android wake the app for the two seconds
a weigh-in is on the air. For a Garmin watch, declining is supported: the watch is bonded and
syncs regardless. A Wear OS watch is added only with the association, because OpenWatch may
start a foreground service from the background only for an associated device, and a workout
another app starts on the watch needs one. A Wear OS watch added before this rule, or whose
association the user removed in Android's settings, shows a "Link watch" button on its device
screen.

## Notification Access

`OpenWatchNotificationListenerService` reads the phone's notifications so they can be shown
on a paired Garmin watch. The user grants it from a system settings screen, after a disclosure
in the app. What it reads is held in memory and sent to the watch only.

## Calendar

`READ_CALENDAR`, for the Garmin calendar glance: read only while answering a watch that asked,
behind a per-watch toggle that is off by default.

## Foreground Service Permissions

`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`, for the scale's 45-second
listening service. `WAKE_LOCK` and `POST_NOTIFICATIONS` go with it.

## Overlay Protection

`HIDE_OVERLAY_WINDOWS`, a normal permission with no prompt. While the Connected apps screen
shows, OpenWatch hides other apps' overlays (API 31 and newer) and drops touches that pass
through another app's window, so no app can draw over Allow or a capability switch.

## Removed Network Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the `android.hardware.wifi`
feature are removed with `tools:node="remove"`, so no dependency can bring them back.
