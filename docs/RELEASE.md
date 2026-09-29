# Releasing the Android client

The release workflow publishes an installable debug-signed APK and a SHA-256
checksum to an existing semantic-version tag. It never creates a tag itself.
This keeps source, version metadata and the GitHub Release on the same commit.

## Prepare

1. Increase both `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Use a `versionName` shaped like `major.minor.patch` (or a SemVer
   prerelease) and update release-facing documentation when behaviour changes.
3. Push the release commit to `main` and wait for Build & Test and the
   pre-publication audit to pass.
4. Run **Release APK** manually with the intended `v*` version. This is a dry
   run: it repeats the audit, ktlint and unit tests, builds the APK, and retains
   the APK/checksum as workflow artifacts without publishing.

## Publish

For version `0.1.2`:

```bash
git tag v0.1.2
git push origin v0.1.2
```

The tag must exactly match `versionName`; otherwise the workflow fails before
building. A stable tag creates a normal GitHub Release. A tag containing a
prerelease suffix, such as `v0.2.0-rc.1`, creates a GitHub prerelease.

Published assets are:

- `openonion-android-v<version>.apk`
- `openonion-android-v<version>.apk.sha256`

Verify with `sha256sum -c` before installing.

## Signing model

The public APK is intentionally debug-signed so it can be installed without a
private keystore in GitHub Actions. It is suitable for sideloading and manual
testing, not Play Store submission. A Play Store release needs an organisation-
controlled keystore or Play App Signing, protected Actions secrets, and a
separate signed AAB publishing path. Never commit a keystore or its password.

## If a release fails

A failed tag workflow does not publish a Release. Fix the release commit and
use the next patch version; do not move or reuse a tag after a Release has been
published.
