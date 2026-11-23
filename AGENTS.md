# AGENTS.md

The implementation guide for anyone, agent or person, working in this repository.

## What this is

OpenWatch is the devices half of OpenVitals: the Wear OS watch app (`:wear`), the
phone-to-watch link (`:wearlink`), and a phone app (`:app`) that pairs Garmin and Wear OS
watches, a Xiaomi S400 scale and devices of the Bluetooth standard health profiles (a heart
rate strap), writes what they record to Health Connect, and manages them. OpenVitals is the dashboard. The two share no code at run time; this repository's
device code was copied from OpenVitals 2.16.0 and adapted at a few seams
(`docs/engineering/architecture.md` lists them).

Health Connect's data origin is the writing app's package, so a record OpenVitals wrote
cannot be edited here, nor the reverse. Both apps may be installed side by side.

## Map

- `app/src/main/kotlin/tech/mmarca/openwatch/`
  - `features/` — the screens: `home`, `device` (the device route and the generic add flow), `watches`, `scales`,
    `healthconnect`, `settings`.
  - `data/repository` — what joins a device family's own contract to `:core:data`: the Garmin
    and scale tables over their DAOs, the Garmin and Wear OS preferences over
    `OpenWatchPreferences`.
  - `sensors/ble/` — the BLE discovery scan the add-a-device flows share.
  - `di/` — the Hilt modules. `DevicesModule` binds what every family shares and the rules
    across families (the classifiers' order); each family binds itself in its own module.
    The device layer itself is the modules under `devices/` (below); the app holds none of it.
- `core/model` (`:core:model`) and `core/fit` (`:core:fit`) — pure Kotlin on the JDK, with
  the app's package names: `domain/model`, `domain/preferences` and `core/geo` in the first, the
  FIT codec `core/fit` in the second. The unit-system provider that reads the platform's locale
  data is the app's (`core/presentation/LocaleUnitSystemProvider`). `:core:model` also holds the
  plain values the device protocols read from the platform sources (`WeatherSnapshot`,
  `NotificationMsg`), `runSuspendCatching`, the injected `DispatcherProvider` and the
  `@ApplicationScope` qualifier.
- `core/devices-api` (`:core:devices-api`) — the device contracts in `devices/core`, pure
  Kotlin: `DeviceFamily` and `DeviceFamilies`, `DeviceFeature`, live readings, the classifiers,
  `BleUuids`, `WatchPairingPort`, `CompanionPresenceObserver`, the sync port and its outcome and
  failure types, the auto-sync interval, `SyncWakeLock`, the record id salt, and the registry as
  a device sees it (`data/repository/contract/DeviceRegistry`, which `BleDeviceRepository`
  implements). Its test fixtures are the shared fakes (`FakeDeviceSyncPort`, `FakeWatchPairing`,
  `FakeDeviceFamily`, `FakeDeviceRegistry`).
- `devices/core` (`:devices:core`) — the platform half every device family shares, an Android
  library in `devices/core`'s packages: the radio lease, `DeviceLog`, the GATT link (`GattLink`:
  the dial and its redial, the MTU, discovery, notifications, writes) and the BLE scanner
  (`BleScanner`), companion pairing and its service (`OpenWatchCompanionDeviceService`, in the
  module's manifest), and the sync controller, its schedule, worker, budget and wake lock; and
  the phone's sources a watch relays, each in its own package: the notification listener and
  its filter (`devices/notifications`, with `WatchNotificationPrefsStore`), the media sessions
  (`devices/media`), the weather broadcasts (`devices/weather`) and the CoMaps guidance feed
  (`comaps`); the FIT activity file import (`activityimport`) and the app's foreground gate
  (`core/performance/AppForegroundGate`). Its test fixtures are `FakeSharedPreferences`,
  `testSeal()` and `activityimport/FitTestFiles`, which the device modules' tests and the app's
  share.
- `devices/garmin` (`:devices:garmin`) — the Garmin watches' platform half, a device module: the
  GATT client over `GattLink`, the radio, notification and settings links, the relays, the
  stores, the file import into Health Connect, the onboarding, the family, the find-phone
  notification's strings and receiver, and `GarminModule`, which binds its family, sync port,
  radio, notification bridge and stores. Its tables' contracts (`GarminWellnessRepository`,
  `GarminSleepMinuteRepository`) and its one preference (`GarminActivityPreferences`) are its
  own; the app implements them.
- `devices/wearos` (`:devices:wearos`) — the phone side of the watch app, a device module: the
  RFCOMM link and its session cache, the series sync and its Health Connect mapping, the
  onboarding, the family, the link states' strings, and `WearOsModule`, which binds its family,
  sync port and node port. The one preference it reads is its own contract
  (`WearOsSyncPreferences`); `OpenWatchPreferences` implements it.
- `devices/xiaomi` (`:devices:xiaomi`) — the S400 scale's platform half, a device module: the
  scan, the receiver and the listening service (in its manifest), the listener and its worker,
  the store, the pipeline to Room and Health Connect, the strings its notification shows, and
  `XiaomiScaleModule`, which binds its family, ports and store. The weigh-in table's contract
  (`ScaleWeighInRepository`) is its own; the app implements it over `:core:data`'s DAO.
- `core/data` (`:core:data`) — what the phone keeps: the Room database (`data/local`, its
  schemas in `core/data/schemas`), the registry (`BleDeviceRepository`), the per-device state,
  `OpenWatchPreferences` and the body profile. It depends on no device family. Its test
  fixtures are the registry and the body profile in memory (`FakeDeviceDao`,
  `testDeviceRepository()`, `FakeBodyProfilePreferences`).
- `core/ui` (`:core:ui`) — what every screen shares: the theme and the shared components
  (`ui/theme`, `ui/components`), the formatters and `ScreenError` (`core/presentation`) and the
  OS permission checks (`core/permissions`). It knows no device family.
- `core/health` (`:core:health`) — Health Connect as the app reaches it, in `healthconnect/`:
  `HealthConnectGateway`, the one class that touches the client, `ActivityRecordWriter`, the
  activity write path copied from OpenVitals, the sync gate, the intents and
  `HealthDataCatalog`; and in `data/repository` the record sink `ImportedRecordRepository` and
  `ActivityWriteRepository`. It implements `:core:health-api`.
- `core/health-api` (`:core:health-api`) — how the devices talk to Health Connect, as
  contracts: the record sink `HealthRecordStore` and its write helpers (`ImportedRecordWrites`),
  the strict reads `HealthConnectReads`, the paused-sync exception and the rate-limit check. An
  Android library, since the records are Health Connect's types. Its test fixture is
  `FakeHealthRecordStore`. The gateway and `ImportedRecordRepository` implement it in `:core:health`.
- `devices/garmin/protocol` (`:devices:garmin:protocol`) — the Garmin protocol, pure Kotlin with
  `explicitApi()` and the app's package names: bytes, COBS, GFDI, protobuf, the multi-link
  transport, the session, file sync, the uploads and the FIT wellness decoder. Its test
  fixtures hold the FIT test bytes the app's import tests use.
- `devices/xiaomi/protocol` (`:devices:xiaomi:protocol`) — the S400 scale's codec, the same
  way: MiBeacon v5 framing, AES-CCM under the bind key, the weigh-in object, the scan filters.
- `devices/ble-standard` (`:devices:ble-standard`, package `devices/blestandard`) — the devices
  of the Bluetooth standard health profiles as one family, `BLE_STANDARD`: a heart rate strap,
  a blood pressure monitor, a weight scale and a glucose meter.
  Its pairing for the app's add flow, its link over `GattLink`, its live stream and the Health
  Connect mapping of what it pushes through the `MeasurementSink`; `BleStandardModule` binds it.
  Its bytes are `:devices:ble-standard:protocol`, pure Kotlin: the measurement characteristics
  as the specifications lay them out, and the payloads it keeps a measurement in.
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
  `devices/core/RadioLease.kt` (`:devices:core`) under one of its owner tags: `SYNC`, `FIND`,
  `SETTINGS`, `NOTIFICATIONS`, `PROBE`, `LIVE`. A scan opens no link and takes no lease. A new GATT device opens
  its link through `GattLink`, never its own `connectGatt`.
- **One companion device service.** `OpenWatchCompanionDeviceService` is the primary one; it
  fans out to every `CompanionPresenceObserver` bound `@IntoSet`, by `DevicesModule` or a
  family's own Hilt module.
- **Health Connect is reached through `HealthConnectGateway` only.** A device takes the
  contracts of `:core:health-api`, never the gateway: it reads through `HealthConnectReads` and
  every device write goes through the record sink, `ImportedRecordRepository.insertImportedRecords`, with a deterministic
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
- **Room is at version 2.** The database is `:core:data`'s, its exported schemas in
  `core/data/schemas`. A change means a `MIGRATION_2_3` in `data/local/OpenWatchMigrations`, a bump
  and its case in `OpenWatchDatabaseMigrationTest`, never `fallbackToDestructiveMigration`. The three
  device tables are named as OpenVitals' are, and also key their rows by the device's id salt.
- **A device family is a module that binds itself** (decision 4, 2026-11). A family in a Gradle
  module of its own, `:devices:<vendor>`, declares its own Hilt module, which binds its family
  into the `DeviceFamily` map (`@StringKey` with its persisted name), its ports, stores and
  startup actors. Only `:app` and those modules declare Hilt modules (not `:devices:core`, a
  contract or a protocol module, nor the app's `devices/` packages), and a device module
  depends on no other family, on no feature and not on the app. `DevicesLayeringTest` holds it.
- **The ratchets hold.** `DevicesLayeringTest` (who declares Hilt modules, see above),
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
- A new device integration is a module `:devices:<vendor>` (its pure protocol in
  `:devices:<vendor>:protocol`): it implements the `devices/core` ports, opens a BLE link only
  through `GattLink` under a radio lease, keeps its protocol code transport-free, binds itself
  in its own Hilt module, and is one `implementation` line in the app's build file. A family
  whose devices need no flow of their own declares a `DevicePairing` and gets the app's add
  flow, Home's "Other devices" list and the generic device panel; one that pushes its data
  hands each measurement to the `MeasurementSink`, which keeps it before Health Connect has it.
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
