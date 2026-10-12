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

This fork uses explicit GitHub releases. **`v1.1` is valid**: version tags may
be `vMAJOR.MINOR` or `vMAJOR.MINOR.PATCH` (for example `v1.1`, `v1.1.0`).
Release tags must point to an existing commit in the reviewed `main` history.
Merging a pull request does not publish an Android release.

GitHub's release version is the source of truth for the **signed release
APK/AAB's Android `versionName`**. The workflow checks out the exact existing
tag and adjusts only `versionName` in its temporary build directory; it never
rewrites or moves the Git tag. Android's numeric `versionCode` is read from
that tagged commit and remains unchanged. Increment `versionCode` in the
source for every subsequent installable update, even when using minor versions.

### One-time repository setup

Create an Actions environment named `release` in **0cwa/day-meter** and
configure the four environment secrets:

- `ANDROID_KEYSTORE_BASE64` — Base64-encoded release `.jks`/keystore
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Encode the keystore without line wrapping:

```bash
base64 < release-keystore.jks | tr -d '\n'
```

Keep the keystore outside Git. Existing installs can only update if the
application ID and signing-certificate lineage remain compatible and Android's
`versionCode` is acceptable. The release job fails closed if signing secrets
are missing. A debug-signed or unsigned APK is never substituted.

Protect `main` with required Android CI/review checks and protect `v*` tags
from rewriting or deletion. Restrict the `release` environment to intended
version-tag workflows and **approved manual dispatches from `main`**, since
recovering a release created before this workflow requires a dispatch from
`main`. If approvals are enabled, approve only a reviewed tag/commit.

### Complete the existing v1.1 release

The `v1.1` tag and [GitHub Release](https://github.com/0cwa/day-meter/releases/tag/v1.1)
already exist, but the prior workflow failed without uploading any binaries.
**Do not delete, move, or recreate that tag.** After merging the updated
pipeline and configuring signing, dispatch the **current workflow from
`main`** with the existing tag as an input:

```bash
gh workflow run release.yml --repo 0cwa/day-meter --ref main -f tag=v1.1
```

Alternatively, open **Actions → Release Android app → Run workflow**, select
`main` as the workflow branch, and enter `v1.1` in the `tag` field.
The trusted release workflow uses the tagged source commit and builds signed
artifacts with Android `versionName = "1.1"`, preserving that commit's
`versionCode = 36`. It then uploads assets to the existing `v1.1` release
without overwriting any conflicting assets.

A manual workflow dispatch from the old `v1.1` ref itself may run the old
workflow snapshot. Selecting `main` with `tag=v1.1` intentionally uses the
updated workflow while leaving the release source commit unchanged.

### Publish future versions

1. Bump Android `versionCode` in `app/build.gradle` for each installable
   update, and optionally update the source's default `versionName` for
   local/debug builds. Merge reviewed changes to `main`, with passing CI.
2. Confirm that the same compatible release-signing certificate is configured.
3. Create a **new** `vMAJOR.MINOR[.PATCH]` tag on that tested commit and push it
   to start the release workflow (or dispatch the workflow from `main` and
   supply the new tag explicitly).

```bash
git switch main
git pull --ff-only
bash tools/check_release_tag.sh v1.2
git tag -a v1.2 -m "Day Meter 1.2"
git push origin v1.2
```

A signed annotated tag is preferable when a registered signing key is
available. Never move an existing release tag.

The pipeline validates tag syntax and ancestry, tests/lints the tagged
source, signs the APK/AAB, checks the embedded APK versionName, generates
portable SHA-256 checksums, and creates GitHub artifact attestations. For new
tags, it uploads to a **draft** GitHub Release and publishes only after
matching the GitHub-computed asset digests. For an already-published empty
release (like `v1.1`), it fills the missing assets, validates them, and refuses
to replace mismatched binaries.

The separate **Release health** workflow checks failed runs and published
release assets daily. It raises errors and an Actions run summary when a
release is missing or incomplete. It can also open a deduplicated incident
issue if GitHub Issues are enabled (currently disabled for this fork).

Verify a downloaded v1.1 release and its provenance with:

```bash
sha256sum -c day-meter-v1.1-SHA256SUMS.txt
gh attestation verify day-meter-v1.1.apk --repo 0cwa/day-meter
```

GitHub's **Verified** badge on commits/tags is separate from Android signing
and artifact attestations.

## Project status
This is the actively maintained `0cwa` fork. We integrated settings clarity, completion-gated habit chains, custom reminder/snooze controls, and their UX evidence as fork pull requests [#1](https://github.com/0cwa/day-meter/pull/1), [#2](https://github.com/0cwa/day-meter/pull/2), [#3](https://github.com/0cwa/day-meter/pull/3), and [#4](https://github.com/0cwa/day-meter/pull/4). Future changes should target **this fork's** `main` branch; upstream changes are considered selectively, not merged automatically.

**Install identity:** The Android `applicationId` is still `com.example.dayprogress`. This is intentional for existing-install compatibility; a separately installable/rebranded variant would need an explicit migration plan before changing that ID or the signing key. See [FORK.md](FORK.md).
