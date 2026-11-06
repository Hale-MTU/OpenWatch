# OpenWatch

The devices half of [OpenVitals](https://github.com/OpenVitals-MTU/android-app): a Wear OS
watch app, the phone-to-watch link, and a phone app that pairs a Garmin or Wear OS watch and
a Xiaomi S400 scale, copies what they record into Health Connect, and manages them. OpenVitals
stays the dashboard that shows the data; OpenWatch is where it comes from.

No account, no server: the phone app declares no `INTERNET` permission, and everything
travels over Bluetooth between the device and the phone. Open source under AGPL-3.0-or-later.

## What it does

- **Garmin watches**, over Garmin's own Bluetooth protocol: sync of activity, sleep and
  wellness files, written to Health Connect where it has a record type; notification
  forwarding, the watch's settings tree and alarms, find my watch, music controls, calendar
  and weather on the watch, CoMaps guidance on the wrist, a GPS ephemeris upload, saved
  locations. The watch-only measurements Health Connect cannot hold (stress, Body Battery,
  sleep score and the like) stay in OpenWatch's own database and its Watch Data screen.
- **Wear OS watches**, through the OpenWatch watch app in `wear/`: heart rate, steps and
  floors recorded on the watch, synced to Health Connect on a tap or a schedule, with an
  estimated sleep session per night and the night's resting heart rate. The watch app also
  offers three tiles.
- **Xiaomi Body Composition Scale S400 and S400 Pro**: every weigh-in saved to Health Connect
  as it happens, with the app open or closed, decoded from the scale's own broadcast with the
  key from your Xiaomi account. Weight and heart rate as measured; body fat, lean mass and
  body water estimated on the phone.

The device code, its screens and its tests came from OpenVitals 2.16.0 unchanged where the
seams allowed; [docs/features/watches.md](docs/features/watches.md) describes the behaviour.

## Layout

| Module | What |
| --- | --- |
| `app/` | The phone app: `devices/` (the Garmin stack, the Wear OS phone side, the scale listener), `features/` (the screens), `healthconnect/` (the one gateway to Health Connect), `activityimport/` (FIT activity files), `data/` (Room and the preference stores) |
| `domain/` | The pure code the app depends on: FIT decoding, the sleep and walking-energy estimators, the device models, the body profile |
| `wear/` | The Wear OS watch app |
| `wearlink/` | The line protocol between the two, pure Kotlin, compiled into both apps |

[AGENTS.md](AGENTS.md) holds the rules the code follows and
[docs/engineering/architecture.md](docs/engineering/architecture.md) the map.

## Build

JDK 17 or 21 and the Android SDK (platform 37, build-tools 37.0.0).

```bash
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

The gates: `./gradlew verifyCi` for the phone app and `./gradlew verifyWearCi` for the watch
app; see [docs/engineering/development.md](docs/engineering/development.md). The watch app is
installed over Wi-Fi adb: [docs/how-to/wear-os-sideload.md](docs/how-to/wear-os-sideload.md).

There is no store release yet. Both apps share the `applicationId` `tech.mmarca.openwatch`,
so a listing can carry both form factors.

## Migrating from OpenVitals

OpenVitals keeps its device code for now, so the two apps can be installed side by side.
Three things to know:

- **Health Connect records belong to the app that wrote them.** OpenWatch cannot update or
  delete a record OpenVitals wrote, and the reverse. The sync cursors and file watermarks
  start empty, so OpenWatch's first sync imports what the watch still holds, as new records
  under OpenWatch. Remove the watch and the scale from OpenVitals first, or pause its device
  syncs, so each record has one writer.
- **The Wear OS watch app is a different app.** It installs beside OpenVitals' watch app under
  its own id and answers only OpenWatch's phone app (a different link UUID). Uninstall the
  OpenVitals watch app so the two do not both record the sensors.
- **The watch-only Garmin data is not copied over.** Stress, Body Battery and the other
  measurements OpenVitals kept on the phone are not in Health Connect; OpenWatch fills its own
  store from the next syncs.

## Privacy

Nothing leaves the phone. Notifications forwarded to a watch are held in memory and sent to
the watch only. The scale's key is stored in the app's private preferences. The app has no
`INTERNET` permission, which the manifest enforces by removing the permission from every
dependency too.
