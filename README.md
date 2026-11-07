# OpenWatch

The devices half of [OpenVitals](https://github.com/OpenVitals-MTU/android-app): a Wear OS
watch app, the phone-to-watch link, and a phone app that pairs a Wear OS watch, copies what it
records into Health Connect, and manages it. OpenVitals stays the dashboard that shows the
data; OpenWatch is where it comes from.

No account, no server: the phone app declares no `INTERNET` permission, and everything
travels over Bluetooth between the watch and the phone. Open source under AGPL-3.0-or-later.

## What it does

- **Wear OS watches**, through the OpenWatch watch app in `wear/`: heart rate, steps and
  floors recorded on the watch, synced to Health Connect on a tap or a schedule, with an
  estimated sleep session per night and the night's resting heart rate. The watch app also
  offers three tiles.

Garmin watches and the Xiaomi S400 scale, which OpenVitals 2.16.0 also handled, are not
supported. The Wear OS code, its screens and its tests came from OpenVitals 2.16.0 unchanged
where the seams allowed; [docs/features/watches.md](docs/features/watches.md) describes the behaviour.

## Layout

| Module | What |
| --- | --- |
| `app/` | The phone app: `devices/` (the Wear OS phone side, pairing and the sync port), `features/` (the screens), `healthconnect/` (the one gateway to Health Connect), `data/` (the device registry and the preference stores) |
| `domain/` | The pure code the app depends on: the sleep and walking-energy estimators, the device models, the body profile |
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
Two things to know:

- **Health Connect records belong to the app that wrote them.** OpenWatch cannot update or
  delete a record OpenVitals wrote, and the reverse. The sync cursors and file watermarks
  start empty, so OpenWatch's first sync imports what the watch still holds, as new records
  under OpenWatch. Remove the watch from OpenVitals first, or pause its device syncs, so each
  record has one writer.
- **The Wear OS watch app is a different app.** It installs beside OpenVitals' watch app under
  its own id and answers only OpenWatch's phone app (a different link UUID). Uninstall the
  OpenVitals watch app so the two do not both record the sensors.

## Privacy

Nothing leaves the phone. The app has no
`INTERNET` permission, which the manifest enforces by removing the permission from every
dependency too.
