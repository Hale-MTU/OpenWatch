# Permissions

OpenWatch asks for permissions by purpose. The app removes inherited network permissions
during manifest merge and ships no `INTERNET`, network-state or Wi-Fi-state permission.

This page mirrors `app/src/main/AndroidManifest.xml`.

## Health Connect Write Permissions

What a watch sync writes: `WRITE_STEPS`, `WRITE_ELEVATION_GAINED`,
`WRITE_ACTIVE_CALORIES_BURNED`, `WRITE_HEART_RATE`, `WRITE_RESTING_HEART_RATE`,
`WRITE_FLOORS_CLIMBED`, `WRITE_STEPS_CADENCE`, `WRITE_SLEEP`.

A record whose write is not granted stays on the device; the rest of a sync goes on.

## Health Connect Read Permissions

What the estimates read: `READ_WEIGHT` and `READ_HEIGHT` (the walking energy), `READ_EXERCISE` (a minute inside a workout gets no walking-energy estimate),
`READ_SLEEP` (a night that already holds a sleep session is left alone),
`READ_RESTING_HEART_RATE`, `READ_HEART_RATE` and `READ_STEPS` (the sync cursors and the
nightly resting heart rate), `READ_HEALTH_DATA_HISTORY`.

`READ_HEALTH_DATA_IN_BACKGROUND`: a scheduled sync with the app closed can only see other
apps' weights, workouts and sleep with this grant. Without it the sync still writes the
samples, but no estimate that depends on another app's data.

## Bluetooth Permissions

`BLUETOOTH_SCAN` (never for location) and `BLUETOOTH_CONNECT` on Android 12 and later;
`BLUETOOTH`, `BLUETOOTH_ADMIN` and `ACCESS_FINE_LOCATION` on Android 11 and older, where the
scan that finds a watch to add needs them.

## Companion Device Permissions

`REQUEST_COMPANION_RUN_IN_BACKGROUND` and `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`:
install-time grants with no prompt of their own. The user's consent is the system "Allow
OpenWatch to access <device>?" dialog. The association keeps the app alive while a watch is
in range, so a sync that runs for minutes is not killed. Declining is supported: the watch
syncs regardless.

## Removed Network Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and the `android.hardware.wifi`
feature are removed with `tools:node="remove"`, so no dependency can bring them back.
