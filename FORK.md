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

- This fork has its **own** guarded GitHub Actions release pipeline. Configure the `release` environment and its `ANDROID_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` secrets **in this repository**. Never ship an unsigned fallback.
- Tags like **`v1.1` and `v1.1.0` are both valid**. The release workflow builds the commit identified by the existing tag, deriving the packaged APK/AAB `versionName` from that tag. Android `versionCode` is a separate, monotonically increasing integer recorded in the tagged source; increase it for subsequent installed updates.
- **Repair the existing empty v1.1 release instead of replacing its tag**: once the pipeline is merged to `main`, run `gh workflow run release.yml --ref main -f tag=v1.1 --repo 0cwa/day-meter`. This uses the updated pipeline from `main` while checking out the exact existing `v1.1` source commit. Its release build adjusts only `versionName` in a temporary build checkout, producing version `1.1` without moving the tag.
- Signed APK/AAB builds must pass tests, lint, version checks and checksums; GitHub attestations and asset digest verification happen before publishing a new release. Existing releases can be safely repaired without overwriting conflicting files. The release-health monitor reports failed/incomplete releases in Actions and can open an incident issue when Issues are enabled (currently disabled).
- Protect `main` with required CI/reviews and protect `v*` tags with a no-rewrites ruleset. If restricting the `release` environment, explicitly permit reviewed manual dispatches from `main` for recovery, as well as tag-triggered releases.
- Verify `gh attestation verify day-meter-v1.1.apk --repo 0cwa/day-meter` and the published SHA-256 checksum file. See the main README for the complete runbook.
- The Android application ID remains `com.example.dayprogress`, matching the original. This preserves the package identity for existing users but does **not** guarantee update compatibility if signing certificates differ. Android apps with the same ID cannot be installed side-by-side for a given user.
- Do not change application ID, signing certificate, or persistent storage schema casually: these affect app upgrades and user data. If a separate installable product is needed, decide on a new application ID and migration/backup strategy first.
- Retain upstream attribution and comply with AGPL-3.0 when distributing binaries or modified source.

## Next engineering checks

Before publishing the first independent release, verify Android CI on the integrated `main`, exercise habits and notifications on real devices (including OEM battery restrictions), check signing and package identity, and review the release notes.
