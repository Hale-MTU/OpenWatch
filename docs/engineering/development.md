# Development

## Build

The phone app, the watch app, the pure-Kotlin link and the domain module build with the
Gradle wrapper. JDK 17 or 21, the Android platform 37 and build-tools 37.0.0.

```bash
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

`scripts/ci-android-gradle.sh` wraps `./gradlew` with the JDK and SDK checks the CI image
needs; it works locally too.

## The Gates

Two gates, as in OpenVitals: the phone gate and the watch gate. Both run the link
module's tests, since both apps compile it.

```bash
./gradlew verifyCi        # the phone app and the domain module: unit tests, translations, duplicate classes, android-test compile, lint
./gradlew verifyWearCi    # the watch app: unit tests, lint, build, android-test compile
```

`verifyCi` is three tasks that CI runs as separate jobs: `verifyCiUnitTest`,
`verifyCiPreflight` and `verifyCiLint`. The phone app's tests run on the `ci` build type.

The verify workflow (`.github/workflows/verify.yml`) diffs a pull request or push against
its base and runs the phone jobs for anything outside `wear/` and the watch job for `wear/`,
`wearlink/` and the shared build files. A change to the root Gradle files runs both.

## Dependency Locks

Every module locks its dependencies (`dependencyLocking` in the root build). After a
dependency change, regenerate the module's lockfile and commit it:

```bash
./gradlew :app:dependencies :domain:dependencies :wear:dependencies :wearlink:dependencies --write-locks
```

## Strings

`app/src/main/res/values*/strings.xml` came from OpenVitals, trimmed to the keys this code
references. `scripts/extract-strings.py --source <openvitals checkout>` redoes the trim after
more code is copied over; `scripts/verify-translations.py` checks every locale for extra keys,
placeholder and plural mismatches. The strings OpenWatch adds of its own live in `values/`
only, until a translation arrives.

## The Watch App

The watch app in `wear/` shares the phone app's `applicationId`, `tech.mmarca.openwatch`,
so a store lists it as a form factor of OpenWatch rather than a second app. The Kotlin
namespace is `tech.mmarca.openwatch.wear`. The debug build type adds the same `.debug` suffix
as the phone app, so a debug watch pairs with a debug phone.

One listing means one versionCode space. The watch owns the range `2000000001` to
`2099999999`; phone codes stay below it. `wear/build.gradle.kts` reads
`OPENWATCH_WEAR_VERSION_CODE` and `OPENWATCH_WEAR_VERSION_NAME` the way the phone build reads
`OPENWATCH_VERSION_CODE` and `OPENWATCH_VERSION_NAME`, and fails the build when the code is
outside the watch range.

The watch's instrumented tests (`wear/src/androidTest`, the metric store against real SQLite)
are compiled by the gate but run only on a device. Do not use `connectedDebugAndroidTest` on a
watch you wear: it uninstalls the app afterwards, which deletes the recorded data and the
trusted phones. Install the two APKs over the app instead and run the runner detached:

```sh
./gradlew :wear:assembleDebug :wear:assembleDebugAndroidTest
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch> install -r -t wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s <watch> shell 'nohup am instrument -w tech.mmarca.openwatch.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/ow-inst.txt 2>&1 &'
adb -s <watch> shell cat /data/local/tmp/ow-inst.txt   # until it ends in OK or FAILURES
```

There is no store client on a watch, so the watch app is sideloaded over Wi-Fi adb.
`scripts/wear-pair.sh` pairs the computer, `scripts/wear-install.sh` builds, installs and
grants the permissions, and `scripts/wear-skip-onboarding.sh` marks a never-onboarded watch
as set up. The walkthrough is [docs/how-to/wear-os-sideload.md](../how-to/wear-os-sideload.md).

## No Release Pipeline Yet

OpenWatch has no Play or F-Droid pipeline yet. A release build signs with the
`OPENWATCH_RELEASE_STORE_FILE`, `OPENWATCH_RELEASE_STORE_PASSWORD`, `OPENWATCH_RELEASE_KEY_ALIAS`
and `OPENWATCH_RELEASE_KEY_PASSWORD` environment variables, as OpenVitals' does.
