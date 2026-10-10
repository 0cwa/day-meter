# Maintaining the 0cwa Day Meter fork

This is the development and release home for our Day Meter variant: **[0cwa/day-meter](https://github.com/0cwa/day-meter)**. It is a GitHub fork of [Kybernetria/day-meter](https://github.com/Kybernetria/day-meter), which remains our upstream reference. We are free to build and ship our own improvements without waiting for upstream to accept them.

## Improvements integrated into our main branch

- [#1 Settings clarity](https://github.com/0cwa/day-meter/pull/1) — distinguish today's override from a recurring daily start and group settings by behavior.
- [#2 Habit chains](https://github.com/0cwa/day-meter/pull/2) — dependency-based checkpoints, Done gating, optional delays, and same-day completion.
- [#3 Reminder customisation](https://github.com/0cwa/day-meter/pull/3) — gentle/silent/vibration/prominent alerts, editable snooze choices, privacy/persistence controls, and a custom-duration picker.
- [#4 UX evidence](https://github.com/0cwa/day-meter/pull/4) — screenshots and reproduction notes under `screenshots/checkpoint-ux/`.

These changes were integrated here independently of upstream's related review requests. The existing Kotlin/Android app, tests, and AGPL-3.0 license remain in place.

## Working on our fork

1. Base development branches on `0cwa/day-meter:main` and open pull requests **against this fork's `main`**, not `Kybernetria/day-meter:main`.
2. Run `./gradlew testDebugUnitTest lintDebug assembleDebug`. GitHub Actions runs the same checks on pushes and pull requests.
3. Review changes for persistence/migration compatibility, day transitions, timezone/DST logic, battery limitations, and Android notification behavior. Local tests do not prove OEM-specific battery and notification delivery.
4. Merge only reviewed, passing work. Keep the default branch usable; do not publish new Android releases as a side effect of ordinary merges.
5. Review useful upstream commits individually and merge or cherry-pick them after testing in our fork. Do **not** reset `main` to upstream; doing so would discard our changes.

Optional local remotes:

```bash
git clone https://github.com/0cwa/day-meter.git
cd day-meter
git remote add upstream https://github.com/Kybernetria/day-meter.git
git fetch upstream
# Compare before selectively incorporating upstream changes:
git log --oneline main..upstream/main
```

## Release and package identity

- This fork has its **own** GitHub Actions release workflow. It needs a `release` environment and the `ANDROID_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` secrets configured **in this repository** before any signed release.
- Only push a `v<versionName>` tag when the tested app version and signing setup are ready. See the main README for details.
- Check the release artifacts with `gh attestation verify <apk-file> --repo 0cwa/day-meter`.
- The current Android application ID remains `com.example.dayprogress`, matching the original. This preserves the expected package identity for existing users but does **not** guarantee update compatibility if the signing certificate differs. Android apps with the same ID cannot be installed side-by-side for a given user.
- Do not change the application ID, signing certificate, or persistent preference/storage schema casually: these affect app upgrades and user data. If we want a distinct installable product, first decide on a new application ID and a data migration/backup approach.
- Retain upstream attribution and comply with the existing AGPL-3.0 license when distributing binaries or modified source.

## Next engineering checks

Before publishing the first independent release, verify Android CI on the integrated `main`, exercise habits and notifications on real devices (including OEM battery restrictions), check signing and package identity, and review the release notes.
