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
    sync port), `garmin` (the GATT client, the links, stores and wellness import around the
    protocol module), `wearos` (the
    phone side of the watch app), `xiaomi` (the scale's scan, store and writes around its codec
    module), `notifications`, `media`, `weather`.
    No Compose, no navigation, no Hilt module.
  - `features/` — the screens: `home`, `watches`, `scales`, `healthconnect`, `settings`.
  - `healthconnect/` — `HealthConnectGateway`, the one class that touches the client, and
    `ActivityRecordWriter`, the activity write path copied from OpenVitals.
  - `activityimport/` — FIT activity files into activity write requests.
  - `data/` — the Room database (three tables), the repositories and the preference stores.
  - `comaps/`, `sensors/ble/` — the CoMaps guidance feed and the BLE discovery scan.
  - `di/` — the Hilt modules. `DevicesModule` is the only place a `devices/` class is bound.
- `core/model` (`:core:model`) and `core/fit` (`:core:fit`) — pure Kotlin on the JDK, with
  the app's package names: `domain/model`, `domain/preferences` and `core/geo` in the first, the
  FIT codec `core/fit` in the second. The unit-system provider that reads the platform's locale
  data is the app's (`core/presentation/LocaleUnitSystemProvider`). `:core:model` also holds the
  plain values the device protocols read from the platform sources (`WeatherSnapshot`,
  `NotificationMsg`) and `runSuspendCatching`.
- `core/devices-api` (`:core:devices-api`) — the device contracts in `devices/core`, pure
  Kotlin: `DeviceFamily` and `DeviceFamilies`, `DeviceFeature`, live readings, the classifiers,
  `BleUuids`, `WatchPairingPort`, `CompanionPresenceObserver`, the sync port and its outcome and
  failure types, the auto-sync interval, `SyncWakeLock`, the record id salt. Its test fixtures
  are the shared fakes (`FakeDeviceSyncPort`, `FakeWatchPairing`, `FakeDeviceFamily`).
- `devices/garmin/protocol` (`:devices:garmin:protocol`) — the Garmin protocol, pure Kotlin with
  `explicitApi()` and the app's package names: bytes, COBS, GFDI, protobuf, the multi-link
  transport, the session, file sync, the uploads and the FIT wellness decoder. Its test
  fixtures hold the FIT test bytes the app's import tests use.
- `devices/xiaomi/protocol` (`:devices:xiaomi:protocol`) — the S400 scale's codec, the same
  way: MiBeacon v5 framing, AES-CCM under the bind key, the weigh-in object, the scan filters.
- `health-calculations/` — a git submodule and composite build, like `openlink/`: the health
  calculations OpenWatch and OpenVitals share (the Wear OS night and resting heart rate, body
  composition, workout and walking energy, the watch's HRV and respiratory rate from the raw
  PPG, the floors credit) and the models they read, as plain Kotlin under
  `tech.mmarca.healthcalculations`. `:core:model` (and through it `:app`), `:wear` and
  `:wear:recording-core` depend on it; its own `AGENTS.md` holds its rules.
- `wear/` — the watch app, no DI. `wear/recording-core/` — its pure core (`:wear:recording-core`):
  what the recorders decide and compute, with the watch app's packages, pure JVM. `wearlink/` —
  the protocol, pure JVM.
- `build-logic/` — the convention plugins (SDK levels, signing, build types, lint, locking) the
  modules apply by id; an included build.

## Invariants

Do not break these without an explicit decision.

- **No `INTERNET` permission.** The manifest removes `INTERNET`, `ACCESS_NETWORK_STATE` and
  `ACCESS_WIFI_STATE`. Never add a dependency that needs a socket.
- **The watch app is offline too.** Its manifest removes the same three network permissions.
  It reads the raw Android sensors; Health Services and the Samsung Health Sensor SDK are not
  dependencies, and the SDK's `.aar` never enters the repository or a build. The one Samsung
  source is the clean-room Binder client in `wear/.../samsung/`, opt-in and offered only on a
  watch that has the Samsung Health Platform. Why, and what
  a Samsung source would have to respect: `docs/engineering/samsung-health-and-privacy.md`.
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
- **Another app reaches OpenWatch through OpenLink only.** `link/OpenWatchLinkService` is
  the one exported entry for apps; every call is gated by `OpenLinkHostCore`'s trust store,
  answered from memory on the binder thread, and never carries a Health Connect record, a
  notification or the scale's key. The library is the `openlink/` submodule; its spec is
  `openlink/docs/spec.md`.
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
- **Room is at version 2.** A change means a `MIGRATION_2_3` in `data/local/OpenWatchMigrations`, a bump
  and its case in `OpenWatchDatabaseMigrationTest`, never `fallbackToDestructiveMigration`. The three
  device tables are named as OpenVitals' are, and also key their rows by the device's id salt.
- **The ratchets hold.** `DevicesLayeringTest` (no `@Module` under `devices/`),
  `CompanionPairingWiringTest` (an Activity calls `attachToActivity`),
  `GarminSessionConfinementTest`, `FileSizeRatchetTest` (800 lines, with a ceiling list),
  `FunctionLengthRatchetTest` (the count over 150 lines only falls), `NoRunBlockingRatchetTest`,
  `SuspendRunCatchingRatchetTest`, `GattConnectionRatchetTest`, `PackageDirectionTest` (which
  package may import which), `LogCallersRatchetTest` (which files may write to the log) and
  `HealthPermissionsTest`. The file-size, function-length, `runBlocking` and log ratchets cover
  the watch app and `:wear:recording-core` too, and `WearPackageDirectionTest` holds the watch
  app's package direction. When one fails, fix the code, not the test.
- **A health permission is in the catalog, or it is not declared.**
  `healthconnect/HealthDataCatalog` lists every health permission of both manifests by the
  device its data comes from; the app shows that list, and `HealthPermissionsTest` holds the
  manifests and the records the integrations build to it. A new type means its catalog row,
  its line in `PRIVACY.md` and `docs/app/permissions.md`, and Play's health declaration,
  before it ships.

## Rules

- Protocol changes for the Wear OS link go in `:wearlink`, never into a copy.
- A new device integration implements the `devices/core` ports, takes a radio lease around
  every BLE link, keeps its protocol code transport-free, and is bound in `di/DevicesModule`.
- A health calculation goes into health-calculations, not into `:core:model`, `:wear` or a
  feature: one copy, so both apps give the same number. Moving the submodule changes
  OpenVitals' numbers too, so it moves in both apps together.
- Keep the copied code close to OpenVitals where it can be: a fix that applies to both
  repositories is easier to carry across when the files still read the same.
- Prefer one ViewModel per screen; ViewModels own loading state and call repositories.

## Verification

`./gradlew verifyCi` (phone app and its modules) and `./gradlew verifyWearCi` (watch app), or
`scripts/ci-android-gradle.sh <task>` on a machine without the SDK pieces set up. The
instrumented tests run on emulators on every push to `main` (`.github/workflows/android-test.yml`);
a test that needs real hardware carries `@RequiresDevice`. See
`docs/engineering/development.md`.

## Releases

One release ships both form factors: the version lives in `gradle.properties`
(`openwatch.versionCode`, `openwatch.versionName`), the watch adds 2000000000 to the code,
and `.github/workflows/release.yml` signs both with one key and uploads the phone bundle to
the Play `beta`/`production` tracks and the watch bundle to `wear:beta`/`wear:production`.
`scripts/release.sh X.Y.Z` cuts a release; the procedure is `.claude/skills/release/SKILL.md`.
