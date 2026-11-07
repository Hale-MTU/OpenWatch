---
name: release
description: Release a new OpenWatch version - changelog, release notes, fastlane changelogs, version code, tag and push. Use for "release X.Y.Z" requests.
---

# OpenWatch release process

How a version ships. OpenVitals' process with one difference: one release carries the
phone app and the Wear OS app, which share the package name and the version. The order
matters: every document is written FIRST, because `scripts/release.sh` commits them, tags
the commit with the release notes, and pushes in one motion.

## 0. Pre-flight

- Everything for the release is committed and pushed on `main`; working tree clean. Work
  merged on GitHub by squash gets new hashes, so a local branch can show "ahead" while the
  trees are identical: check `git diff --stat HEAD origin/main` prints nothing, then
  `git checkout main && git merge --ff-only origin/main`. Release from `main`.
- Gates are green: `./gradlew verifyCi verifyWearCi` (or `scripts/ci-android-gradle.sh`
  with those tasks on a machine without the SDK pieces set up).
- Scope the release: `git log --oneline vLAST..HEAD` is what the notes must cover. Read the
  commit bodies, not only the subjects.
- Translations: the copied strings are complete in every locale; a key OpenWatch added may
  be English-only (`python3 scripts/verify-translations.py` reports coverage). Say so in the
  notes when a screen is English-only in a shipping locale.
- The release workflow is enabled: `state` is `active` in
  `https://api.github.com/repos/Hale-MTU/OpenWatch/actions/workflows/release.yml`.
  A tag pushed while it is disabled starts nothing, and enabling it afterwards does not
  replay the push.

## 1. Version code (read this before touching anything)

`versionCode` is a monotonic install counter, independent of `versionName`. Nightlies and
releases share one counter. It is computed, never chosen:

```bash
sh scripts/version-code.sh next --floor <current openwatch.versionCode from gradle.properties>
```

It consults the GitHub release markers (`<!-- OpenWatch-Version-Code: N -->`) and the
append-only `refs/version-code/*` refs. The phone ships N; the watch ships 2000000000 + N,
derived in `wear/build.gradle.kts` and `scripts/ci-release-context.sh`, so there is one
number to track. N must stay at or below 99999999. To see what the script sees:

```bash
git ls-remote --refs origin 'refs/version-code/*' | sed 's#.*/##' | sort -n | tail -3
```

**Race caveat:** a nightly can mint a code between your preview and the release run. Only
the release workflow's schedule (00:00 UTC) and manual runs with target `nightly` build
nightlies. Near midnight UTC, check the runs first. Either way, compute the code and name
the fastlane files immediately before running the release script, and verify the script's
printed `versionCode` afterwards.

## 2. Documents to write, all before running the script

1. **`CHANGELOG.md`**: prepend `## X.Y.Z - YYYY-MM-DD` with a `### English` section and
   the other language sections the entry has translations for. House style: ASCII only,
   bold-led bullets, one `**Fixes:**` bullet gathering the small ones.
2. **`docs/releases/X.Y.Z.md`**: the release notes; this file becomes the TAG MESSAGE and
   the GitHub release notes and the Zulip post. Shape: `# OpenWatch X.Y.Z`,
   `Released YYYY-MM-DD.`, one narrative paragraph, then `### Added` / `### Changed` /
   `### Fixed`, then the footer, which reads exactly:

   ```
   The phone app and the Wear OS app share the package name `tech.mmarca.openwatch` and the signing certificate; the watch's versionCode is 2000000000 plus the phone's.

   Distribution flow:

   - GitHub release with the signed phone APK, signed debug APK, signed Wear OS APK and both signed Android App Bundle assets.
   - Direct Google Play production upload, to the phone track and the Wear OS track, from the approved production run of the release workflow.
   ```

   While the repository variable `OPENWATCH_PLAY_PAUSED=true` holds the Play uploads, or
   `OPENWATCH_PLAY_INTERNAL_ONLY=true` keeps them on internal testing before Google's
   approval, the second line is false: say instead that the Google Play update follows later.
3. **`README.md`**: highlight bullets for headline features; update any claim the release
   changes.
4. **`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`**, one per Play
   listing locale, all 14: `en-US`, `cs-CZ`, `de-DE`, `es-ES`, `et`, `fi-FI`, `fr-FR`,
   `gl-ES`, `it-IT`, `ja-JP`, `pl-PL`, `pt-PT`, `ru-RU`, `zh-CN`. Named by the PHONE code;
   the watch has no changelog of its own (Play shows the listing's). Hard limit 500
   characters, single paragraph, in that locale's language and script, with the feature
   names from that locale's `strings.xml`. Play counts the trailing newline, so keep every
   note at 490 or under. Check before running the script:
   ```bash
   code=<versionCode>; for d in fastlane/metadata/android/*/; do f="${d}changelogs/$code.txt"; test -f "$f" || echo "missing $f"; python3 -c "import sys; n=len(open(sys.argv[1],encoding='utf-8').read()); print(sys.argv[1], n, 'TOO LONG' if n > 490 else '')" "$f" 2>/dev/null; done
   ```

## 3. Run the release

```bash
bash scripts/release.sh X.Y.Z
```

The script computes the version code, patches `openwatch.versionCode` and
`openwatch.versionName` in `gradle.properties` (both build files read it), commits
`chore: release X.Y.Z` (staging CHANGELOG, README, docs, fastlane and the release
machinery), tags `vX.Y.Z` with `docs/releases/X.Y.Z.md` as the annotation, and pushes
`main` plus the tag. The annotation drops the `# OpenWatch X.Y.Z` title line and keeps the
rest as written, `###` headings included.

Verify the printed `Released vX.Y.Z (versionCode N)` matches the fastlane changelog
filenames; if a nightly stole the code, rename all 14 files and amend before anyone pulls:

```bash
for f in fastlane/metadata/android/*/changelogs/OLD.txt; do git mv "$f" "${f%OLD.txt}NEW.txt"; done
```

CI takes it from the tag: the tag's release run builds the five signed artifacts once,
publishes them to the GitHub prerelease, uploads the phone AAB to Play open testing and the
watch AAB to the Wear OS open testing track, and waits for the `production` environment's
approval. Approving that run (Actions, the run, Review deployments) promotes both form
factors to production, marks the release stable and posts the notes to the Zulip
`releases` channel. A tag whose run is gone is shipped by a manual run of the release
workflow with target `production`, started from the tag: it takes both AABs from the
prerelease, no build.

While the repository variable `OPENWATCH_PLAY_INTERNAL_ONLY=true` is set (before Google
has approved the app), both AABs go to internal testing instead and the production job
uploads nothing; the rest of the run is unchanged. After the approval, delete the variable
and ship the waiting version with that same manual `production` run from its tag: the lane
promotes it from internal testing.

## 4. Afterwards

If the OpenVitals docs site (`../docs`) carries an OpenWatch page, update it; the landing
page copy only when a headline feature warrants it.

## 5. Gotchas

- The fastlane changelog is per-versionCode, not per-versionName: a release whose code raced
  a nightly ships the wrong changelog silently if step 3's verification is skipped.
- Release notes and changelog entries are user-facing: name what the user sees, not the
  implementation.
- Read facts in the notes against the code, not only the commit message.
- An unescaped `&` in a translated string breaks `verifyTranslations` (XML parse).
- The footer is part of the tag message and cannot be fixed afterwards. Read it, do not
  paste an older one.
