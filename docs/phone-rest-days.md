# Phone-free rest days

This optional mode helps compare when a day ends even if the person's schedule
is late, rotating, longer than 24 hours, or otherwise unrelated to midnight.
Clock-based mode remains the default. Enabling this mode does not change its
saved clock times or manual-start settings.

## Detection and day boundaries

Android screen-off and keyguard-shown events provide explicit put-down evidence.
The absence of foreground app events alone never starts a rest. Unlocks and
foreground activity while the screen is not known to be locked or off provide
use evidence, including launcher use. Showing a lock screen or a full-screen
activity over a known lock does not count as a sustained wake.

1. An uninterrupted use session reaches the configured wake threshold and
   establishes the day start at the beginning of that session.
2. A lock/screen-off after the configured minimum awake duration begins a
   candidate rest. The awake-duration check uses the put-down instant, so a
   low-phone period cannot qualify simply by growing longer.
3. At five minutes, the candidate end is put-down plus five minutes. It remains
   provisional until the configured minimum rest duration has elapsed.
4. Confirmation retains the original five-minute timestamp. It does not move
   the end to the later confirmation or refresh time.
5. After confirmation, phone checks shorter than the wake threshold stay in
   the same rest. A sustained use session closes the rest record and begins
   the next day at that session's start.

Before confirmation, a phone-use session cancels the candidate; the next
put-down begins a fresh candidate. In particular, interrupted periods shorter
than the minimum rest are not added together. The detector does not choose a
global longest interval from a calendar day: that would require a fixed day
boundary and could move already recorded ends. Instead it uses elapsed guards,
optional excluded waking hours, and an explicit correction action.

The duration defaults (3 hours of rest, 8 hours awake, 5 minutes of wake use) are
detection settings, not target sleep or waking durations. No clock hours are
excluded unless the user enables and chooses them. Protection checks the entire
candidate/confirmed rest, through the start of its sustained wake; a rest that
later reaches a protected interval is discarded. Begin/end boundaries are
half-open, and overnight ranges are supported.

## Comparison and presentation

The last confirmed end's local clock time is projected to its next occurrence
after the current day starts. This is a comparison reference, not a cutoff. A
day remains active past that reference and past midnight, with text such as
“30m past last end.” The progress rail stays below 100% until an observed rest
confirms an end. Without an earlier rest, text shows elapsed time and the rail
has no reference.

Recent ends include dates. Their earlier/later labels use the shortest signed
local-clock difference, from -12 to +12 hours: 23:55 to 00:05 is 10 minutes later.
Dates remain visible because clock differences cannot represent skipped days or
unambiguously describe shifts greater than 12 hours. Both directions use neutral
language and the same presentation. Display times use the current device time
zone. Duration thresholds use elapsed epoch milliseconds, and reference clock
times use calendar arithmetic through daylight-saving changes.

## Mistaken estimates and settings changes

Phone inactivity cannot tell sleep apart from reading, working, or leaving the
phone elsewhere. Minimum awake/rest durations and optional excluded waking hours
reduce false positives but cannot eliminate them.

**Ignore latest rest estimate** discards the current pending/confirmed estimate
first. Otherwise it removes the latest completed estimate and restores its
preceding day start. Previously retained history remains available. Detection
waits for fresh observed use/put-down evidence rather than immediately finding
the same rejected interval again. The dated settings summary identifies the
estimate the action affects.

Changing detection sensitivity starts fresh observation, retaining the current
day start and completed history. Turning the mode off and back on retains
history, but does not revive a pending rest from before the mode was disabled.
Resetting the app's settings also clears this history.

## Usage Access, persistence, and gaps

- Android 9+ is required for device-level screen/keyguard events. Earlier Android
  versions retain the existing clock/manual mode.
- Usage Access is required only for automatic activity detection. Missing or
  revoked access is displayed explicitly; it does not fall back to a fixed end
  or confirm a rest from a failed query.
- The first opt-in can reconstruct up to 48 hours of available observed events.
  An unknown query prefix is never treated as inactivity. The app keeps only
  timestamps/state and the last 30 completed rest records in its existing local
  preferences; package names and raw event history are not retained. The app's
  existing backup exclusions apply.
- Refresh alarms remain passive, every five minutes, including when no widget
  is installed. Android can defer them during sleep/Doze. On the next refresh or
  app opening, available events reconstruct the recorded timestamps; this is
  not an exact five-minute background alarm guarantee.
- Replay is bounded to 48 hours because Android retains usage events for only
  a few days. Longer gaps discard the unfinished cycle and reconstruct from
  unknown state inside that lookback, preserving completed history.
- Reboot/shutdown events, observed permission/query failures, and substantial
  wall-clock discontinuities break unfinished evidence. The app does not label
  powered-off or otherwise unobserved time as sleep. Completed timestamps remain
  records of the clock at which they were observed.

## Checkpoints

Percentage checkpoints pause while this mode is enabled. Their schedules and
snoozes cannot deliver, old notification actions cannot revive them, and active
percentage notifications are removed when switching modes. Percentage settings
are preserved for returning to clock mode. A clock checkpoint linked to a paused
percentage predecessor remains blocked.

Clock reminders keep their existing calendar-date scheduling and habit-chain
reset behavior. All checkpoint markers are hidden on the comparison rail because
it does not represent a known final day duration.

## Validation

The pure analyzer tests cover the five-minute boundary, longer confirmation,
brief checks, minimum-awake gating, optional/overnight protected hours, multi-day
cycles, unknown evidence, duplicate/cursor handling, history bounds, midnight
comparison, and DST. Robolectric tests exercise usage-event adaptation,
persistence/repository integration, permission handling, settings, widget text,
and paused percentage reminders alongside retained clock reminders.

Run the repository's normal checks:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Device validation should exercise a real lock/unlock overnight sequence, one
brief check, a low-phone period rejected through the settings action, Usage Access
revocation/regrant, and delayed refreshes. Unit tests cannot guarantee every
manufacturer's event reporting or battery behavior.

Android API references:

- [UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
- [UsageStatsManager.queryEvents](https://developer.android.com/reference/android/app/usage/UsageStatsManager#queryEvents(long,%20long))
