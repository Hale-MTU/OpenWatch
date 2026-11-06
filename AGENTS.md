# AGENTS.md

The implementation guide for anyone, agent or person, working in this repository.

## What this is

OpenWatch is the devices half of OpenVitals: the Wear OS watch app (`:wear`), the
phone-to-watch link (`:wearlink`), and a phone app (`:app`) that pairs Garmin and Wear OS
watches and a Xiaomi S400 scale, writes what they record to Health Connect, and manages
them. OpenVitals is the dashboard. The two share no code at run time; this repository's
device code was copied from OpenVitals 2.16.0 and adapted at a few seams
(`docs/engineering/architecture.md` lists them).

Health Connect's data origin is the writing app's package, so a record OpenVitals wrote
cannot be edited here, nor the reverse. Both apps may be installed side by side.

## Map

- `app/src/main/kotlin/tech/mmarca/openwatch/`
  - `devices/` — the device layer: `core` (ports, the radio lease, companion pairing, the
    sync port), `garmin` (the GFDI protocol stack and its wellness import), `wearos` (the
    phone side of the watch app), `xiaomi` (the scale), `notifications`, `media`, `weather`.
    No Compose, no navigation, no Hilt module.
  - `features/` — the screens: `home`, `watches`, `scales`, `healthconnect`, `settings`.
  - `healthconnect/` — `HealthConnectGateway`, the one class that touches the client, and
    `ActivityRecordWriter`, the activity write path copied from OpenVitals.
  - `activityimport/` — FIT activity files into activity write requests.
  - `data/` — the Room database (three tables), the repositories and the preference stores.
  - `comaps/`, `sensors/ble/` — the CoMaps guidance feed and the BLE discovery scan.
  - `di/` — the Hilt modules. `DevicesModule` is the only place a `devices/` class is bound.
- `domain/` — pure code with the same package names: `core/fit`, `core/geo`, `domain/model`,
  `domain/insights` (the sleep and walking-energy estimators), `domain/preferences`.
- `wear/` — the watch app, no DI. `wearlink/` — the protocol, pure JVM.

## Invariants

Do not break these without an explicit decision.

- **No `INTERNET` permission.** The manifest removes `INTERNET`, `ACCESS_NETWORK_STATE` and
  `ACCESS_WIFI_STATE`. Never add a dependency that needs a socket.
- **One foreground service, and only for the scale.** The 45-second `ScaleListeningService`
  is the one service the app starts without a tap. A watch sync runs with no foreground
  service; its process-priority story is the companion association.
- **One BLE radio, leased per address.** Everything that opens a BLE link takes a lease from
  `devices/core/RadioLease.kt` under one of the four owner tags: `SYNC`, `FIND`, `SETTINGS`,
  `NOTIFICATIONS`. A scan opens no link and takes no lease.
- **One companion device service.** `OpenWatchCompanionDeviceService` is the primary one; it
  fans out to every `CompanionPresenceObserver` bound `@IntoSet` in `DevicesModule`.
- **Health Connect is reached through `HealthConnectGateway` only.** Every device write goes
  through `ImportedRecordRepository.insertImportedRecords` with a deterministic
  `clientRecordId`, so a re-sync upserts; the gateway's reads throw on failure, they never
  return an empty answer that would pass for "no data". There is no rate-limit backoff: the
  devices issue a handful of reads per sync. Add one only once a refusal is seen.
- **A missing permission is `ScreenError.PermissionDenied`.** Use `isPermissionFailure()` /
  `toScreenError()`; never pattern-match exception messages.
- **Nothing waits on the main thread.** No `runBlocking` in `src/main`
  (`NoRunBlockingRatchetTest`); Health Connect and Room calls are `suspend` on
  `dispatchers.io`; a receiver never holds a broadcast for a Health Connect read.
- **Text that leaves the app is structure, not data.** A log line logs a count or passes the
  throwable, never a value (`LogMessageInterpolationRatchetTest` holds the count).
- **Every locale ships complete for the copied strings.** A key that comes from OpenVitals is
  re-extracted with `scripts/extract-strings.py`; a key OpenWatch adds lives in `values/`
  until it is translated. `python3 scripts/verify-translations.py` checks the files.
- **Room is at version 1.** A new entity means a `MIGRATION_1_2` and a bump, not
  `fallbackToDestructiveMigration`. Table and column names match OpenVitals'.
- **The ratchets hold.** `DevicesLayeringTest` (no `@Module` under `devices/`),
  `CompanionPairingWiringTest` (an Activity calls `attachToActivity`),
  `GarminSessionConfinementTest`, `FileSizeRatchetTest` (800 lines, with a ceiling list) and
  `NoRunBlockingRatchetTest`. When one fails, fix the code, not the test.

## Rules

- Protocol changes for the Wear OS link go in `:wearlink`, never into a copy.
- A new device integration implements the `devices/core` ports, takes a radio lease around
  every BLE link, keeps its protocol code transport-free, and is bound in `di/DevicesModule`.
- Keep the copied code close to OpenVitals where it can be: a fix that applies to both
  repositories is easier to carry across when the files still read the same.
- Prefer one ViewModel per screen; ViewModels own loading state and call repositories.

## Verification

`./gradlew verifyCi` (phone app and domain) and `./gradlew verifyWearCi` (watch app), or
`scripts/ci-android-gradle.sh <task>` on a machine without the SDK pieces set up. See
`docs/engineering/development.md`.
