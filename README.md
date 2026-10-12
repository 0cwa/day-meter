# day-meter

**Maintained fork:** [0cwa/day-meter](https://github.com/0cwa/day-meter), based on [Kybernetria/day-meter](https://github.com/Kybernetria/day-meter). This fork develops and releases its own improvements while retaining upstream attribution and the AGPL-3.0 license. See [FORK.md](FORK.md) for our branch, upgrade, and release policy.

A small Android widget app that tracks how much of your configured day has passed.

## What it does
- Detects your day start automatically from on-device usage, or works entirely with manual starts
- Handles day end times that go past midnight and daylight-saving transitions
- Shows progress, time remaining, and clear waiting/completed states
- Supports bar, text, and combined widgets with customizable colors, borders, fonts, and gradients
- Adds arbitrary daily checkpoints at a clock time or percentage of the day
- Links checkpoints into habit chains that wait for the previous step to be Done, with an optional delay
- Lets you complete or skip today’s checkpoint in its editor, even without notifications
- Shows checkpoint markers on the progress bar and sends gentle, silent, vibration-only, or prominent reminders
- Supports Done, Snooze with editable shortcuts and custom minutes (1–1440), and Skip today notification actions
- Offers lock-screen privacy and reminder persistence controls, with direct Android channel settings
- Uses battery-friendly passive widget refreshes rather than frequent exact wake-up alarms

## Notes
- Usage Access is optional and is only used for automatic day-start detection
- Android 13 and newer ask for notification permission when an enabled checkpoint is saved
- Checkpoint reminders are best-effort and can be delayed by Android battery restrictions
- Tap the widget once to open settings

## Habit chains
In a checkpoint editor, choose **Wait for checkpoint** and optionally a delay after completion. The clock time or day percentage is the earliest reminder time; a linked checkpoint waits until its predecessor is Done too. Skip and Snooze do not unlock later steps. Dependent steps must repeat on days covered by their predecessor.

Chains reset at midnight and match steps on the same scheduled calendar date, even when your day window extends overnight. A delay extending past midnight expires for that date. Remove dependent links before deleting a predecessor. Disabled predecessors keep later steps waiting.

Open a saved checkpoint to mark it Done or Skip today. Completing it in the app also works when you have dismissed its reminder or disabled notification permission.

## Build
```bash
./gradlew assembleDebug
```

APK output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Releases

Releases are **explicit and tag-driven**; merging a pull request does not
publish an Android build. A release tag must be **vMAJOR.MINOR.PATCH** and match
the `versionName` in `app/build.gradle` exactly (for example,
`v1.0.36` for `versionName = "1.0.36"`). Increase `versionCode` whenever
shipping an update to Android users.

### One-time repository setup

Create a GitHub Actions environment named `release` in **0cwa/day-meter**
and configure these environment secrets:

- `ANDROID_KEYSTORE_BASE64` — the Base64-encoded release `.jks`/`.keystore`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Encode the keystore without line wrapping:
```bash
base64 < release-keystore.jks | tr -d '\n'
```

Store the keystore securely outside the repository. Reuse the correct signing
certificate for app upgrades; changing the key can prevent existing installs
from updating. The release job **fails closed** if any signing secret is missing.
Do not publish debug-signed or unsigned binaries instead.

Repository maintainers should require passing Android CI and review before
merging to `main`, protect `v*` tags from deletion/rewrites with a tag
ruleset, and restrict the `release` environment to intended release refs.

### Prepare and publish a version

1. Bump `versionName` and `versionCode` in `app/build.gradle`, merge
   the change into `main`, and confirm Android CI passed.
2. Ensure the release keystore and four environment secrets are configured.
3. Tag the tested commit on `main` and push the tag:

```bash
git switch main
git pull --ff-only
version="$(bash tools/read_version_name.sh)"
bash tools/check_release_tag.sh "v$version"
git tag -a "v$version" -m "Day Meter $version"
git push origin "v$version"
```

Use a signed annotated tag when a GitHub-registered signing key is available.
Do **not** create a release manually in the GitHub UI ahead of the pipeline.
A manual GitHub Release does trigger the release workflow too, but may
temporarily appear empty and will fail if its version is invalid.

The release workflow validates tag/version consistency and main-branch
ancestry, runs tests and lint, builds a **signed APK and AAB**, generates
portable SHA-256 checksums, and attests the assets. It creates a **draft**
GitHub Release, uploads and verifies GitHub-computed asset digests, and only
then publishes the complete release. A rerun can fill missing assets in an
existing release without overwriting different binaries.

To rerun a failed release manually, use **Actions → Release Android app →
Run workflow** and **select the existing version tag**, not `main`:

```bash
gh workflow run release.yml --repo 0cwa/day-meter --ref "v$version"
```

If an earlier published release has the wrong tag, **do not move or reuse its
tag**: retire the invalid release and create a new tag from a tested commit
with matching Android metadata. The separate **Release health** workflow
checks for failed release runs and missing assets and scans published releases
daily. Failures produce red Actions runs with error annotations and a run
summary. The monitor also opens one actionable issue **if GitHub Issues are
enabled**; this fork currently has Issues disabled, so enable them under
Settings → General → Features to receive issue alerts.
Release-environment approval and signing secrets still require maintainer setup.

Verify downloaded artifacts using the published checksum file:

```bash
sha256sum -c day-meter-v1.0.36-SHA256SUMS.txt
gh attestation verify day-meter-v1.0.36.apk --repo 0cwa/day-meter
```

GitHub's **Verified** badge on commits/tags is distinct from APK signing and
artifact attestations.

## Project status
This is the actively maintained `0cwa` fork. We integrated settings clarity, completion-gated habit chains, custom reminder/snooze controls, and their UX evidence as fork pull requests [#1](https://github.com/0cwa/day-meter/pull/1), [#2](https://github.com/0cwa/day-meter/pull/2), [#3](https://github.com/0cwa/day-meter/pull/3), and [#4](https://github.com/0cwa/day-meter/pull/4). Future changes should target **this fork's** `main` branch; upstream changes are considered selectively, not merged automatically.

**Install identity:** The Android `applicationId` is still `com.example.dayprogress`. This is intentional for existing-install compatibility; a separately installable/rebranded variant would need an explicit migration plan before changing that ID or the signing key. See [FORK.md](FORK.md).
