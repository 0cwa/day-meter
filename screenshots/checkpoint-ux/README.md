# Checkpoint UX review evidence

These PNGs render the app's actual Android activities, preference rows, and checkpoint dialogs using Robolectric API 35 native graphics. They are Android view captures, not emulator or physical-device screen grabs. Phone viewport: 390 × 844 dp; normal (1×) and enlarged (2×) fonts. Dialogs are measured within the available phone viewport.

- `before/`: unchanged upstream main at `656a215`.
- `settings/`: behavior-first categories, wrapping titles, current start mode, and a repeating daily start.
- `chains/`: actual in-app interactions move a checkpoint from blocked to available to Done, plus chain configuration and the checkpoint list.

`SettingsVisualTest` in the habit-chain PR reproduces the new captures and checks readable text, multiline choices, usable touch targets, and visible Save/Cancel controls. Screenshots contain sample habits and use the execution clock. Tests independently verify scheduler and action behavior. Device-specific notification delivery and battery restrictions require device testing.

Images live on a separate evidence branch so feature PR diffs remain focused on source, tests, and documentation.

- `notifications/`: default/customized shared reminder controls, Android channel-settings entry points, the actual four-style popup menu, and a saved Prominent selection. `NotificationVisualTest` in the notification PR reproduces these views at 1×/2× fonts and checks text readability and real toggle/save interactions. Popup captures include the actual native PopupWindow background.

- `notifications/snooze-*.png`: notification-launched duration picker, custom minutes and validation, editable shortcut settings, and six configured shortcuts at 1×/2× fonts. `SnoozeVisualTest` renders the floating destination at 351 dp wide (90% of the 390 dp phone) within 800 dp height; compact captures use 420 dp available height to check actual scrolling and reachable actions when space is constrained. The Android keyboard itself is not included in these native view captures.
