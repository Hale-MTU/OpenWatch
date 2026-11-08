#!/usr/bin/env sh
set -eu

git fetch --tags --force origin
mkdir -p build/release-ci

# Cron nightlies with nothing new to publish exit here so we never mint a
# versionCode or spend a runner-hour on a duplicate APK. Manual runs always
# build. See scripts/ci-should-skip-nightly.sh.
if [ "${OPENWATCH_RELEASE_EVENT:-}" = "cron" ] && sh scripts/ci-should-skip-nightly.sh; then
    {
        printf 'OPENWATCH_SKIP_NIGHTLY=true\n'
        printf 'OPENWATCH_RELEASE_CHANNEL=nightly\n'
        printf 'OPENWATCH_RELEASE_TAG=nightly\n'
    } > build/release-ci/release-context.env
    exit 0
fi

apk_abi_filters="${OPENWATCH_APK_ABI_FILTERS:-armeabi-v7a,arm64-v8a}"
release_tag=""

if [ "${OPENWATCH_RELEASE_EVENT:-}" = "cron" ] || [ "${OPENWATCH_RELEASE_EVENT:-}" = "manual" ]; then
    release_tag="nightly"
else
    case "${GITHUB_REF:-}" in
        refs/tags/*)
            release_tag="${GITHUB_REF#refs/tags/}"
            ;;
        *)
            # A production run started from a branch rather than the tag: the tag
            # the run names, else the vX.Y.Z tag on that commit, if there is one.
            if [ "${OPENWATCH_RELEASE_EVENT:-}" = "deployment" ]; then
                if [ -n "${OPENWATCH_RELEASE_TAG_INPUT:-}" ]; then
                    release_tag="$OPENWATCH_RELEASE_TAG_INPUT"
                else
                    release_tag="$(git tag --points-at "${GITHUB_SHA:?}" | grep -E '^[vV][0-9]+\.[0-9]+\.[0-9]+$' | tail -n 1 || true)"
                fi
            fi
            ;;
    esac
fi

# A production run ships a tag's build, so its version is the tag's
# gradle.properties, not this checkout's: started from main, the checkout may
# be any number of releases later.
gradle_properties="gradle.properties"
if [ "${OPENWATCH_RELEASE_EVENT:-}" = "deployment" ] && [ -n "$release_tag" ]; then
    if ! git rev-parse -q --verify "refs/tags/$release_tag^{commit}" >/dev/null; then
        echo "Release tag $release_tag does not exist." >&2
        exit 1
    fi
    gradle_properties="$(mktemp)"
    git show "refs/tags/$release_tag:gradle.properties" > "$gradle_properties"
fi

gradle_task=":app:assembleRelease"
bundle_task=""
apk_variant_dir="release"
aab_variant_dir=""
aab_basename=""
build_aab="false"
build_debug_apk="false"
play_track=""
# The testing track: open testing (beta) unless the repository variable
# OPENWATCH_PLAY_TESTING_TRACK says internal, or OPENWATCH_PLAY_INTERNAL_ONLY=true
# holds every upload short of Google's approval. The workflow picks the
# fastlane lane the same way.
testing_track="beta"
if [ "${OPENWATCH_PLAY_INTERNAL_ONLY:-}" = "true" ] || [ "${OPENWATCH_PLAY_TESTING_TRACK:-}" = "internal" ]; then
    testing_track="internal"
fi
version_code=""
version_name_override=""
debug_apk_basename=""
# The watch app: built beside the phone app on a nightly or a tag; a promote
# run takes both bundles from the prerelease.
build_wear="false"
wear_gradle_task=""
wear_bundle_task=""
wear_variant_dir=""
wear_apk_basename=""
wear_aab_basename=""
wear_version_code=""

# The release version lives in gradle.properties, read by the phone and the
# watch build alike (the watch adds 2000000000 to the code).
configured_version_code="$(sed -n 's/^openwatch\.versionCode=\([0-9][0-9]*\)[[:space:]]*$/\1/p' "$gradle_properties" | head -n 1)"
if [ -z "$configured_version_code" ]; then
    echo "Could not read openwatch.versionCode from gradle.properties." >&2
    exit 1
fi

version_name="$(sed -n 's/^openwatch\.versionName=\([^[:space:]]*\)[[:space:]]*$/\1/p' "$gradle_properties" | head -n 1)"
if ! printf '%s\n' "$version_name" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "Release builds require a numeric openwatch.versionName, found $version_name." >&2
    exit 1
fi

if [ "$release_tag" = "nightly" ]; then
    release_channel="nightly"
    gradle_task=":app:assembleNightly"
    apk_variant_dir="nightly"
    apk_basename="OpenWatch-nightly.apk"
    bundle_task=":app:bundleNightly"
    aab_variant_dir="nightly"
    aab_basename="OpenWatch-nightly.aab"
    build_wear="true"
    wear_gradle_task=":wear:assembleNightly"
    wear_bundle_task=":wear:bundleNightly"
    wear_variant_dir="nightly"
    wear_apk_basename="OpenWatch-wear-nightly.apk"
    wear_aab_basename="OpenWatch-wear-nightly.aab"
    release_title="OpenWatch Nightly"
    prerelease="true"
    build_aab="true"
    build_debug_apk="true"
    play_track="$testing_track"
    debug_apk_basename="OpenWatch-nightly-debug.apk"
    pipeline_number="${GITHUB_RUN_NUMBER:-1}"

    if ! printf '%s\n' "$pipeline_number" | grep -Eq '^[0-9][0-9]*$'; then
        echo "GITHUB_RUN_NUMBER must be numeric for nightly versionName generation, found $pipeline_number." >&2
        exit 1
    fi

    version_code="$(sh scripts/version-code.sh next --floor "$configured_version_code")"
    version_name_override="$version_name-nightly.$pipeline_number"
else
    release_channel="release"
    apk_basename="OpenWatch-$release_tag.apk"
    release_title="OpenWatch $release_tag"
    prerelease="true"
    wear_apk_basename="OpenWatch-wear-$release_tag.apk"
    wear_aab_basename="OpenWatch-wear-$release_tag.aab"

    if [ -z "$release_tag" ]; then
        echo "Release tag is required." >&2
        exit 1
    fi

    if ! printf '%s\n' "$release_tag" | grep -Eq '^[vV][0-9]+\.[0-9]+\.[0-9]+$'; then
        echo "Release tags must use vX.Y.Z semantic version format, for example v1.8.0 or V1.8.0." >&2
        exit 1
    fi

    if [ "$version_name" != "${release_tag#[vV]}" ]; then
        echo "Release tag $release_tag does not match app versionName $version_name." >&2
        exit 1
    fi

    if [ "${OPENWATCH_RELEASE_EVENT:-}" = "deployment" ]; then
        prerelease="false"
        bundle_task=":app:bundleRelease"
        aab_variant_dir="release"
        aab_basename="OpenWatch-$release_tag.aab"
        build_aab="true"
        play_track="production"
        version_code="$(sh scripts/version-code.sh for-tag "$release_tag" --floor "$configured_version_code")"
    else
        build_debug_apk="true"
        debug_apk_basename="OpenWatch-$release_tag-debug.apk"
        # A tag build also makes the app bundle, attaches it to the GitHub release
        # and uploads it to the testing track; production is a promotion from there.
        play_track="$testing_track"
        bundle_task=":app:bundleRelease"
        aab_variant_dir="release"
        aab_basename="OpenWatch-$release_tag.aab"
        build_aab="true"
        build_wear="true"
        wear_gradle_task=":wear:assembleRelease"
        wear_bundle_task=":wear:bundleRelease"
        wear_variant_dir="release"
        previous_configured_version_code="$((configured_version_code - 1))"
        expected_version_code="$(sh scripts/version-code.sh next --floor "$previous_configured_version_code")"
        if [ "$expected_version_code" != "$configured_version_code" ]; then
            # A re-run of this tag. Its first run already published the marker, so "next" has
            # moved past it. Without this a release whose upload failed could never be finished.
            published_version_code="$(sh scripts/version-code.sh for-tag "$release_tag" --floor 0)"
            if [ "$published_version_code" != "$configured_version_code" ]; then
                echo "openwatch.versionCode $configured_version_code is stale; next expected versionCode is $expected_version_code." >&2
                exit 1
            fi
            echo "Re-running $release_tag: its release already carries versionCode $configured_version_code."
        fi
        version_code="$configured_version_code"
    fi
fi

# The watch ships 2000000000 + the phone's code: one listing, one release,
# two form factors, and Play wants every artifact's code distinct. The range
# wear/build.gradle.kts accepts ends at 2099999999.
if [ -n "$version_code" ]; then
    if [ "$version_code" -gt 99999999 ]; then
        echo "versionCode $version_code leaves no room in the watch range 2000000001..2099999999." >&2
        exit 1
    fi
    wear_version_code="$((2000000000 + version_code))"
fi

printf '%s\n' "$release_title" > build/release-ci/release-title.txt

notes_file="build/release-ci/release-notes.md"
if [ "$release_channel" = "release" ]; then
    : > "$notes_file"
    if git rev-parse -q --verify "refs/tags/$release_tag" >/dev/null; then
        tag_object_type="$(git cat-file -t "$release_tag")"
        if [ "$tag_object_type" = "tag" ]; then
            git for-each-ref "refs/tags/$release_tag" --format='%(contents)' > "$notes_file"
        fi
        # A lightweight tag carries no message: the release notes file in the
        # tag's tree, without its title line, says what the annotation would.
        if [ ! -s "$notes_file" ]; then
            git show "refs/tags/$release_tag:docs/releases/${release_tag#[vV]}.md" 2>/dev/null | sed '1{/^# /d}' | sed '1{/^$/d}' > "$notes_file" || : > "$notes_file"
        fi
    fi
    if [ ! -s "$notes_file" ]; then
        printf '%s\n' \
            "$release_title" \
            "" \
            "Versioned prerelease build from commit ${GITHUB_SHA:?}." \
            "" \
            "Assets:" \
            "- Signed release APK ($apk_abi_filters)" \
            > "$notes_file"
        if [ "$build_debug_apk" = "true" ]; then
            printf '%s\n' "- Signed Debug APK ($apk_abi_filters)" >> "$notes_file"
        fi
        if [ "$build_aab" = "true" ]; then
            printf '%s\n' "- Signed app bundle (AAB), for a Google Play upload by hand" >> "$notes_file"
        fi
        if [ "$build_wear" = "true" ]; then
            printf '%s\n' "- Signed Wear OS APK and app bundle (versionCode 2000000000 + the phone's)" >> "$notes_file"
        fi
        printf '%s\n' "- SHA-256 checksums" >> "$notes_file"
    fi
else
    printf '%s\n' \
        "$release_title" \
        "" \
        "Mutable $release_channel build from commit ${GITHUB_SHA:?}." \
        "" \
        "This release keeps a stable download page. The APK and checksum assets are replaced by the next $release_channel build." \
        "" \
        "Assets:" \
        "- Signed $release_channel APK ($apk_abi_filters)" \
        > "$notes_file"
    if [ "$build_debug_apk" = "true" ]; then
        printf '%s\n' "- Signed Debug APK ($apk_abi_filters)" >> "$notes_file"
    fi
    if [ "$build_aab" = "true" ]; then
        printf '%s\n' "- Signed app bundle (AAB), for a Google Play upload by hand" >> "$notes_file"
    fi
    if [ "$build_wear" = "true" ]; then
        printf '%s\n' "- Signed Wear OS APK and app bundle (versionCode 2000000000 + the phone's)" >> "$notes_file"
    fi
    printf '%s\n' "- SHA-256 checksums" >> "$notes_file"
fi

if [ -n "$version_code" ]; then
    {
        printf '\n'
        sh scripts/version-code.sh marker "$version_code"
    } >> "$notes_file"
fi

{
    printf 'OPENWATCH_SKIP_NIGHTLY=false\n'
    printf 'OPENWATCH_RELEASE_CHANNEL=%s\n' "$release_channel"
    printf 'OPENWATCH_RELEASE_TAG=%s\n' "$release_tag"
    printf 'OPENWATCH_GRADLE_TASK=%s\n' "$gradle_task"
    printf 'OPENWATCH_BUNDLE_TASK=%s\n' "$bundle_task"
    printf 'OPENWATCH_APK_VARIANT_DIR=%s\n' "$apk_variant_dir"
    printf 'OPENWATCH_APK_BASENAME=%s\n' "$apk_basename"
    printf 'OPENWATCH_AAB_VARIANT_DIR=%s\n' "$aab_variant_dir"
    printf 'OPENWATCH_AAB_BASENAME=%s\n' "$aab_basename"
    printf 'OPENWATCH_APK_ABI_FILTERS=%s\n' "$apk_abi_filters"
    printf 'OPENWATCH_RELEASE_PRERELEASE=%s\n' "$prerelease"
    printf 'OPENWATCH_RELEASE_TARGET=%s\n' "${GITHUB_SHA:?}"
    printf 'OPENWATCH_BUILD_AAB=%s\n' "$build_aab"
    printf 'OPENWATCH_BUILD_DEBUG_APK=%s\n' "$build_debug_apk"
    printf 'OPENWATCH_PLAY_TRACK=%s\n' "$play_track"
    printf 'OPENWATCH_VERSION_CODE=%s\n' "$version_code"
    printf 'OPENWATCH_VERSION_NAME=%s\n' "$version_name_override"
    printf 'OPENWATCH_DEBUG_APK_BASENAME=%s\n' "$debug_apk_basename"
    printf 'OPENWATCH_BUILD_WEAR=%s\n' "$build_wear"
    printf 'OPENWATCH_WEAR_GRADLE_TASK=%s\n' "$wear_gradle_task"
    printf 'OPENWATCH_WEAR_BUNDLE_TASK=%s\n' "$wear_bundle_task"
    printf 'OPENWATCH_WEAR_VARIANT_DIR=%s\n' "$wear_variant_dir"
    printf 'OPENWATCH_WEAR_APK_BASENAME=%s\n' "$wear_apk_basename"
    printf 'OPENWATCH_WEAR_AAB_BASENAME=%s\n' "$wear_aab_basename"
    printf 'OPENWATCH_WEAR_VERSION_CODE=%s\n' "$wear_version_code"
    printf 'OPENWATCH_WEAR_VERSION_NAME=%s\n' "$version_name_override"
} > build/release-ci/release-context.env
