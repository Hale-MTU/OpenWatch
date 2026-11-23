# Development

## Build

The phone app, the watch app and its pure core, the pure-Kotlin link, the phone app's
pure-Kotlin modules (`:core:model`, `:core:fit`, `:core:devices-api`, `:devices:garmin:protocol`,
`:devices:xiaomi:protocol`) and its Android libraries (`:core:health-api`, `:devices:core`,
`:devices:wearos`, `:devices:xiaomi`) build with the Gradle wrapper. JDK 17 or 21, the Android platform
37 and build-tools 37.0.0.

```bash
./gradlew :app:assembleDebug
./gradlew :wear:assembleDebug
```

`scripts/ci-android-gradle.sh` wraps `./gradlew` with the JDK and SDK checks the CI image
needs; it works locally too.

## The JDK

The Gradle daemon runs on JDK 17, pinned in `gradle/gradle-daemon-jvm.properties`: the JDK CI
and F-Droid build with. Where none is installed, Gradle downloads one (Temurin, through foojay)
into `~/.gradle/jdks`; `JAVA_HOME` only starts the launcher. It matters for the bytes: the same
commit built on JDK 21 ships a different `assets/dexopt/baseline.prof` (measured 2026-11-21,
classes identical), so a local release build matches CI's and F-Droid's only on 17. The
plugins' classpath is locked in `buildscript-gradle.lockfile`.

## The Gates

Two gates, as in OpenVitals: the phone gate and the watch gate. Both run the link
module's tests, since both apps compile it.

```bash
./gradlew verifyCi        # the phone app and its modules: unit tests, translations, duplicate classes, android-test compile, lint
./gradlew verifyWearCi    # the watch app and its pure core: unit tests, lint, build, android-test compile, and :app's architecture tests
```

`verifyCi` is three tasks that CI runs as separate jobs: `verifyCiUnitTest`,
`verifyCiPreflight` and `verifyCiLint`. The phone app's tests run on the `ci` build type. So do
the watch app's unit tests and lint, on the watch's own `ci` type; its build and the
instrumented tests' compile stay on `debug`, the type a run on a watch installs.

The verify workflow (`.github/workflows/verify.yml`) diffs a pull request or push against
its base and runs the phone jobs for anything outside `wear/` and the watch job for `wear/`,
`wearlink/` and the shared build files. A change to the root Gradle files, to
`gradle.properties` or to the health-calculations pointer runs both: the watch app compiles
that library too.

## Instrumented Tests

The tests in `app/src/androidTest`, `wear/src/androidTest` and `wear/src/sharedTest` (which
also runs on Robolectric in the watch gate) need Android itself: Compose on a real window,
the Keystore, the platform's Health Connect, SQLite, `Parcel`. The gates only compile them.
They run in three places.

- **CI, on emulators.** `.github/workflows/android-test.yml` runs the phone app's tests
  (`verifyAndroidTest`) on an Android 16 phone image with Google APIs (API 36), and the
  watch app's (`:wear:connectedAndroidTest`) on Wear OS 5 (API 34), one job each, side by
  side, in about a quarter of an hour. The verify workflow calls it on every push to `main`,
  for the sides the push changed, and on no pull request. The release reads that push run,
  so a failing emulator job holds the nightly and any tag cut from that commit until it
  passes. When the emulator itself failed (a boot that timed out), "Re-run failed jobs" on
  the verify run, then re-run the release. A manual run of the `android-test` workflow tries
  any branch before it merges, and gates nothing. Each job keeps the HTML report, the JUnit
  results with each test's logcat, and the logcat of the whole run, as the run's
  `android-test-phone-<attempt>` and `android-test-wear-<attempt>` artifacts.
- **A phone, or an emulator of your own.** `ANDROID_SERIAL=<serial> ./gradlew verifyAndroidTest`
  installs the `ci` build (`tech.mmarca.openwatch.ci`, beside the app you use), runs the
  tests and removes it again. Without `ANDROID_SERIAL` the task does nothing.
  `scripts/ci-android-test.sh verifyAndroidTest` is what CI runs: the same task, after a
  check that the animations are off, and followed by one that tests ran at all.
- **A watch.** Not through a connected task: see [The Watch App](#the-watch-app).

Every test runs on the emulators; none needs hardware they lack. What the emulators do not
prove: `KeystoreSecretKeyTest` seals under a software key there, not one in the phone's
secure hardware; `SamsungRecordedParcelDeviceTest` reads and writes AOSP's `Parcel`, not a
Galaxy Watch's build of it; `ClientRecordVersionTest` measures the Health Connect module the
image ships, not the one Play keeps updated on a phone.

A test that does need real hardware (a Bluetooth link, a sensor the emulator lacks, Samsung's
health platform, a key in secure hardware) carries `@RequiresDevice`
(`androidx.test.filters`): the runner skips it on an emulator and runs it on a device. It is
listed in this section with what it needs; none is today. A test that needs a permission
grants it itself through `UiAutomation`, as `ClientRecordVersionTest` does: on CI's emulator
the app is a new install every run. The emulators run with the three animation scales at 0,
set by the emulator action.

## Lint

Lint runs on every pull request and push that touches the phone side, and in the watch
gate. A warning fails it (`warningsAsErrors`, set in `build-logic/` for every Android module
and every plain Kotlin one that applies `com.android.lint`). The pure-Kotlin modules that hold
code an app's lint used to check apply it, with the same rules: `:core:model`, `:core:fit`,
`:core:devices-api`, `:devices:garmin:protocol` and `:devices:xiaomi:protocol` in
`verifyCiLint`, `:wear:recording-core` in the watch gate. The Android libraries (`:core:health-api`,
`:devices:core`, `:devices:wearos`, `:devices:xiaomi`) run `lintCi` there too. A library's lint sees its own manifest only, not the
permissions the app declares, so a call that needs one carries `@SuppressLint("MissingPermission")`
and checks the grant at run time, as the app's own calls do. `:wearlink` has none. Lint knows no
minSdk in a plain Kotlin module, so its API-level check does not run there: a JDK method that
Android 8 lacks fails only on such a phone. Keep their code to the Java 8 library and
`java.time`.
The warnings that were there when that was switched on are in each module's
`lint-baseline.xml`; fix them from there, and never record a new one into it. A baseline
entry that was fixed is reported as such and can be dropped by regenerating:

```bash
./gradlew :app:updateLintBaselineCi :core:model:updateLintBaseline :core:fit:updateLintBaseline \
  :core:devices-api:updateLintBaseline :core:health-api:updateLintBaselineCi :devices:core:updateLintBaselineCi \
  :devices:wearos:updateLintBaselineCi :devices:xiaomi:updateLintBaselineCi \
  :devices:garmin:protocol:updateLintBaseline :devices:xiaomi:protocol:updateLintBaseline \
  :wear:updateLintBaselineCi :wear:recording-core:updateLintBaseline
```

Two kinds of check are off on purpose. The version checks (`GradleDependency`,
`NewerVersionAvailable` and the like) change with the calendar, not the code. The plural and
spelling checks over translations are informational: Weblate writes those files, and
`scripts/verify-translations.py` checks what can break at runtime.

## Dependency Locks

Every module locks its dependencies (`dependencyLocking`, in `build-logic/`). After a
dependency change, regenerate the module's lockfile and commit it:

```bash
./gradlew :app:dependencies :core:model:dependencies :core:fit:dependencies :core:devices-api:dependencies \
  :core:health-api:dependencies :devices:core:dependencies :devices:wearos:dependencies \
  :devices:xiaomi:dependencies \
  :devices:garmin:protocol:dependencies :devices:xiaomi:protocol:dependencies \
  :wear:dependencies :wear:recording-core:dependencies :wearlink:dependencies --write-locks
```

## Dependency Verification

`gradle/verification-metadata.xml` holds the SHA-256 of every artifact the build downloads,
the submodules' and `build-logic/`'s included; a jar or POM that does not match fails the
build. A lock pins the version, this pins the bytes. After a dependency or plugin change,
rerun the gates and the release builds with `--write-verification-metadata`, so the tasks
that resolve their own tools (lint, R8, aapt2) are covered too, and review the diff. Run it
from an empty Gradle home: a warm cache answers some lookups from what it already holds, and
the POMs it skipped then fail on a clean CI runner.

```bash
GRADLE_USER_HOME="$(mktemp -d)" ./gradlew --no-build-cache --write-verification-metadata sha256 \
  verifyCi verifyWearCi :app:assembleRelease :app:bundleRelease :app:assembleNightly \
  :app:bundleNightly :app:assembleDebug :wear:assembleRelease :wear:bundleRelease \
  :wear:assembleNightly :wear:bundleNightly
```

Gradle writes the aapt2 jar of the machine it runs on only. The macOS and Windows jars
are added by hand from Google's Maven, so a contributor on either can build; when AGP
moves, hash the new version's `aapt2-<version>-osx.jar` and `-windows.jar` again. Source
and javadoc jars, which only an IDE sync downloads, are trusted by name.

## Strings

`app/src/main/res/values*/strings.xml` came from OpenVitals, trimmed to the keys this code
references. `scripts/extract-strings.py --source <openvitals checkout>` redoes the trim after
more code is copied over. `scripts/verify-translations.py` (the `verifyTranslations` task)
checks every locale of the phone app and of the watch app (`wear/src/main/res`) against that
app's English file: extra keys, and placeholder and plural mismatches. Both apps ship the same
languages; a string added between releases shows in English until the release translates it.

A device module carries the strings its own code shows, in `devices/<vendor>/src/main/res`,
moved there with every translation (`:devices:xiaomi`: the weigh-in notification;
`:devices:wearos`: the watch link's states; `:devices:core`: what every family says, such as
Bluetooth being off). The
translation check reads every module's `res` beside the apps'. `extract-strings.py` trims and
refreshes the app's files only, so a key that moved keeps the translations it moved with, and
the translation platform needs each module's file as a component of its own.

## The Watch App

The watch app in `wear/` shares the phone app's `applicationId`, `tech.mmarca.openwatch`,
so a store lists it as a form factor of OpenWatch rather than a second app. The Kotlin
namespace is `tech.mmarca.openwatch.wear`. The debug build type adds the same `.debug` suffix
as the phone app, so a debug watch pairs with a debug phone. The `ci` type, the watch gate's,
adds `.ci` as the phone's does; nothing installs either app's `ci` build.

One listing means one versionCode space. The watch owns the range `2000000001` to
`2099999999`; phone codes stay below it. `wear/build.gradle.kts` reads
`OPENWATCH_WEAR_VERSION_CODE` and `OPENWATCH_WEAR_VERSION_NAME` the way the phone build reads
`OPENWATCH_VERSION_CODE` and `OPENWATCH_VERSION_NAME`, and fails the build when the code is
outside the watch range.

The watch app's code that needs no Android (the recorder plan, the sleep minutes and the
sleep hold, the step and ascent minutes, the retention warning, the workout tracker, the tiles'
day) is the module `:wear:recording-core`, in `wear/recording-core/`: plain Kotlin, so its tests
run on the JVM without Robolectric. A class goes there when it needs no Android API and no
sensor, preference or store; [architecture.md](architecture.md) lists what it holds.

The watch's instrumented tests (`wear/src/androidTest` and `wear/src/sharedTest`: the metric
store against real SQLite, the recorded Samsung parcels) run in CI on a Wear OS emulator
([Instrumented Tests](#instrumented-tests)). Do not use `connectedDebugAndroidTest`, or
`scripts/ci-android-test.sh`, on a watch you wear: a connected task uninstalls the app
afterwards, which deletes the recorded data and the trusted phones. Install the two APKs over
the app instead and run the runner detached:

```sh
./gradlew :wear:assembleDebug :wear:assembleDebugAndroidTest
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <watch> install -r -t wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s <watch> shell 'nohup am instrument -w tech.mmarca.openwatch.debug.test/androidx.test.runner.AndroidJUnitRunner > /data/local/tmp/ow-inst.txt 2>&1 &'
adb -s <watch> shell cat /data/local/tmp/ow-inst.txt   # until it ends in OK or FAILURES
```

Between releases, or on a watch without the Play Store, the watch app is sideloaded over Wi-Fi adb.
`scripts/wear-pair.sh` pairs the computer, `scripts/wear-install.sh` builds, installs and
grants the permissions, and `scripts/wear-skip-onboarding.sh` marks a never-onboarded watch
as set up. The walkthrough is [docs/how-to/wear-os-sideload.md](../how-to/wear-os-sideload.md).

## Docs and the Wiki

The [wiki](https://github.com/Hale-MTU/OpenWatch/wiki) is a mirror of the repository's Markdown, rebuilt by `.github/workflows/wiki.yml` on every push to `main` that touches a `.md` file: `README.md` is Home, every file under `docs/` and the other root Markdown files (changelog, privacy policy, agent notes) are one page each, named after their first heading, with a sidebar by folder. Edit the files here, never the wiki: the next run overwrites it.

`scripts/sync-wiki.py` rewrites each relative link to the page it points at, or to the file on GitHub for anything that is not a page. Its `--check` mode runs in the verify gate and fails on a relative link to a file that does not exist. To see the pages locally:

```bash
python3 scripts/sync-wiki.py --repo Hale-MTU/OpenWatch --out /tmp/openwatch-wiki
```

Two Markdown files with the same first heading would become one page; the script stops and names both. The same script runs in OpenVitals, OpenLink and OpenBridge; keep the copies alike.

## Releases

`.github/workflows/release.yml` is the release, on the schedule, a version tag and the manual
run; it is OpenVitals' pipeline with the watch app added. A tag or the nightly is cut from a
commit on `main`, whose push ran the verify workflow; the release's `context` job looks that
run up, waits for it if it is still going, and stops the release if there is none or it
failed. The push run includes the emulator jobs (`android-test`), so the release waits for
them too. The verify run's `lint`, `wear-test` and `android-test` jobs skip when their paths
did not change and still leave the run successful, which is what the gate reads.

Its `build` job signs everything once, with one key, in one Gradle invocation: the phone
APK, the phone debug APK, the phone AAB, the watch APK and the watch AAB. They are the run's
`release-build` artifact; every job after it, production included, ships those files as
they are. Two GitHub release outputs:

- The `00:00 UTC` schedule, or a manual run of the release workflow with target `nightly`,
  builds `:app:assembleNightly`, `:app:bundleNightly`, `:app:assembleDebug`,
  `:wear:assembleNightly` and `:wear:bundleNightly`, then publishes `OpenWatch-nightly.apk`,
  `OpenWatch-nightly-debug.apk`, `OpenWatch-nightly.aab`, `OpenWatch-wear-nightly.apk` and
  `OpenWatch-wear-nightly.aab` to the fixed GitHub `nightly` prerelease. The same run uploads
  the phone AAB to the Google Play open testing track (`beta` in the Play Developer API) and
  the watch AAB to the Wear OS form-factor track (`wear:beta`), or to internal testing
  (`internal`, `wear:internal`) under `OPENWATCH_PLAY_TESTING_TRACK=internal` or while
  `OPENWATCH_PLAY_INTERNAL_ONLY=true` holds.
- A pushed `vX.Y.Z` or `VX.Y.Z` tag builds the `release` variants and publishes the same
  five files under `OpenWatch-vX.Y.Z` and `OpenWatch-wear-vX.Y.Z` names to its own versioned
  GitHub prerelease, uploads both AABs to open testing (or internal testing, under the same
  variables), then waits for the `production` environment's approval. Approving the run
  promotes the phone from `beta` to `production` with the store listing, the watch from
  `wear:beta` to `wear:production`, marks the GitHub release stable, and posts the notes to
  the Zulip `releases` channel (`scripts/announce-zulip.sh`). The promotion reads the
  version from whichever testing track has it, open or internal.

Each AAB has a `.sha256` beside it. A manual run with target `production`, started from the
tag, downloads both AABs from the prerelease, checks them, and runs the production jobs on
them: the way to ship a tag whose own run is gone. Started from `main` with the `tag` input
naming the version instead, it does the same with `main`'s workflow, lanes and scripts, and
the tag's own `gradle.properties` for the version: the way to apply a fix to the release
machinery to a tag already cut.

### One listing, two form factors, one version

The phone app and the watch app share the `applicationId` `tech.mmarca.openwatch`, so Play
lists the watch as a form factor of the one app. The release version lives once, in
`gradle.properties`:

```
openwatch.versionCode=N
openwatch.versionName=X.Y.Z
```

The phone ships `versionCode` N and the watch `2000000000 + N` (`wear/build.gradle.kts`
accepts `2000000001..2099999999`, so N stays at or below `99999999`;
`scripts/ci-release-context.sh` checks). `versionName` and `versionCode` are detached:
`versionName` is the human name (`0.1.0`, `0.1.0-nightly.12`, the suffix being the run
number), `versionCode` a monotonic update counter shared by nightlies and releases. CI
reads `OpenWatch-Version-Code` markers from the GitHub release notes plus the append-only
`refs/version-code/*` refs, uses `max(markers, refs, openwatch.versionCode) + 1` for a new
build, writes the chosen code into the release notes, and records it as a ref before the
nightly tag moves (`scripts/move-nightly-tag.sh`). The marker holds the phone code; the
watch code is derived. The fastlane lanes compare the incoming code with the live Play track
(`internal`, `beta`, `production` and their `wear:` twins) before uploading, so a code Play
already serves fails with a message instead of an opaque API error. The nightly run also
prunes old versioned GitHub releases, keeping the newest nine.

The scheduled run skips the build when there is nothing new: the `nightly` tag already
points at HEAD, or HEAD has no commits after the latest `vX.Y.Z`. Manual runs always build.

### Secrets and variables

The workflow's `GITHUB_TOKEN` creates and edits releases and pushes the `nightly` tag and
the `refs/version-code/*` refs; a tag ruleset that blocks either breaks the nightly.
Repository Actions secrets:

- `OPENWATCH_RELEASE_KEYSTORE_BASE64`, `OPENWATCH_RELEASE_STORE_PASSWORD`,
  `OPENWATCH_RELEASE_KEY_ALIAS` and `OPENWATCH_RELEASE_KEY_PASSWORD`: a PKCS12 upload key,
  base64-encoded (`base64 -w0 openwatch-release.p12`). One key signs both form factors and
  the published debug APK, so every build updates the one before.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_BASE64`: the JSON key of a service account that can
  release `tech.mmarca.openwatch` to testing and production. If it may stage edits but not
  send them for review, set the variable `OPENWATCH_PLAY_CHANGES_NOT_SENT_FOR_REVIEW=true`.
- `ZULIP_BOT_EMAIL` and `ZULIP_BOT_API_KEY` for a bot on `openvitals.zulipchat.com`
  subscribed to the `releases` channel; only the `announce-zulip` job sees them.

The `production` environment needs required reviewers, or a production run never waits.

The variable `OPENWATCH_PLAY_TESTING_TRACK` names the testing track the nightly and a tag
upload to: `beta`, open testing, when unset, or `internal`, internal testing. Production is
promoted from whichever track has the version, so `internal` gives an internal testing,
then production, flow with no open testing in between. A version code Play already holds
is never uploaded twice: on a track, it is skipped by the upload and promoted from where it
sits; in Play's bundle library only (a hand upload left as a draft), the release is made from
that bundle, after its sha256 is checked against the run's.

Two more variables hold Play back, for two situations:

- Before Google has approved the app, only internal testing may take a build: Play reviews
  a closed or open testing release and a production release, and a new app's first review
  is also the gate on every later track. The variable `OPENWATCH_PLAY_INTERNAL_ONLY=true`
  sends the nightly's and a tag's uploads to internal testing (`internal`, `wear:internal`)
  instead of open testing, and makes the production job upload nothing; the run still waits
  for approval, marks the GitHub release stable and posts to Zulip. Open testing and
  production are not touched while it is set. Once Google has approved the app, delete the
  variable; the next tag goes to the testing track and then production as usual, and a
  version that is waiting on internal testing is promoted from there by a manual run of
  the workflow from its tag with target `production`.
- While a release is in Play review, a new upload to any track restarts the review. The
  variable `OPENWATCH_PLAY_PAUSED=true` holds every Play upload, phone and watch, internal
  testing included: the nightly skips its Play job, and a production run still waits for
  approval, marks the GitHub release stable and posts to Zulip, but uploads nothing.
  Delete the variable to resume.

### Before the first run

By hand, once, outside the repository:

1. The keystore: `keytool -genkeypair -storetype PKCS12 -keystore openwatch-release.p12
   -alias openwatch -keyalg RSA -keysize 4096 -validity 10000`; the four secrets above (the
   key password equals the store password for PKCS12). Keep the file offline.
2. Play Console: create the app `tech.mmarca.openwatch`, enrol it in Play App Signing with
   this key as the upload key, add the Wear OS form factor (Advanced settings, Form factors)
   so the `wear:*` tracks exist, create internal testing and open testing for both form
   factors, fill the store listing (`fastlane/metadata/android/` holds the text and the
   512 px icon; the feature graphic and screenshots are added in the Console), the content
   rating, the Data safety form and the Health Connect declaration, and set the privacy
   policy URL to
   `https://github.com/Hale-MTU/OpenWatch/blob/main/PRIVACY.md`. Play refuses an API
   upload to an app with no release, so the first bundle on each track goes up by hand, from
   the first run's GitHub prerelease.
3. The service account, invited in Users and permissions with release rights on this app.
4. The Zulip bot, the `production` environment, and the variables: `OPENWATCH_PLAY_PAUSED`
   for the first run, `OPENWATCH_PLAY_INTERNAL_ONLY=true` for every run until Google has
   approved the app.
5. A manual `nightly` run with `OPENWATCH_PLAY_PAUSED=true` proves the GitHub side; a second
   without it proves the Play side, the `wear:internal` upload included.

## Release Checklist

The procedure, written out step by step, is `.claude/skills/release/SKILL.md`. It is
OpenVitals' procedure, and the two skills are kept alike. In short:

1. Write the documents first: `CHANGELOG.md` (six language sections), `docs/releases/X.Y.Z.md` (it becomes the tag
   message and the release notes), the README, and one Play changelog per locale under
   `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, named by the phone's
   code (the watch has none), 490 characters or fewer.
2. `./gradlew verifyCi verifyWearCi` and `git diff --check`.
3. `bash scripts/release.sh X.Y.Z`: it computes the code with `scripts/version-code.sh
   next`, patches `gradle.properties`, commits `chore: release X.Y.Z`, tags `vX.Y.Z` with the
   release notes and pushes `main` and the tag together. Check the printed code against the
   changelog file names.
4. The tag's run builds, publishes the prerelease and waits. Approve it (Actions, the run,
   Review deployments). If the run is gone, run the workflow by hand from the tag with target
   `production`.
5. The companion repositories: the docs site ports the release notes page
   (`scripts/app-docs.json` there) and the landing page's OpenWatch copy follows a headline
   feature.
