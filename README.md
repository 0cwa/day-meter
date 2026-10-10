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

Pushing a tag matching the Android version (for example, `v1.0.36`) runs the
release workflow. It builds an APK and Android App Bundle, signs both with the
release key, publishes them as a GitHub Release, and creates a GitHub artifact
attestation for each release file.

Before the first release, create a GitHub environment named `release` and add
these environment secrets:

- `ANDROID_KEYSTORE_BASE64` — the Base64-encoded release `.jks`/`.keystore` file
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

For example, encode the keystore without line wrapping:

```bash
base64 < release-keystore.jks | tr -d '\n'
```

The release Gradle tasks intentionally fail unless all four signing values are
provided. Debug builds remain unsigned by the release key.

Anyone can verify a downloaded release asset's provenance with GitHub CLI:

```bash
gh attestation verify day-meter-v1.0.36.apk --repo 0cwa/day-meter
```

GitHub's **Verified** badge on commits and tags is separate from Android APK
signing and artifact attestations. To receive that badge, create the release tag
with a GPG, SSH, or S/MIME key added to the GitHub account that creates it.

## Project status
This is the actively maintained `0cwa` fork. We integrated settings clarity, completion-gated habit chains, custom reminder/snooze controls, and their UX evidence as fork pull requests [#1](https://github.com/0cwa/day-meter/pull/1), [#2](https://github.com/0cwa/day-meter/pull/2), [#3](https://github.com/0cwa/day-meter/pull/3), and [#4](https://github.com/0cwa/day-meter/pull/4). Future changes should target **this fork's** `main` branch; upstream changes are considered selectively, not merged automatically.

**Install identity:** The Android `applicationId` is still `com.example.dayprogress`. This is intentional for existing-install compatibility; a separately installable/rebranded variant would need an explicit migration plan before changing that ID or the signing key. See [FORK.md](FORK.md).
