# HOME on Fire OS 7.7.1.7 (AFTSS sheldon, PS7717/5741)

## What the device shows
- `cmd package query-activities -a MAIN -c HOME`:
  - com.amazon.tv.launcher/.ui.HomeActivity_vNext  priority=950
  - com.amazon.firehomestarter/.HomeStarterActivity priority=1
  - com.amazon.tv.settings.v2/.system.FallbackHome  priority=-1000
- No preferred or persistent-preferred HOME activity exists (dumpsys package has no such section).
  HOME is won by intent-filter priority alone. Third-party apps get their filter priority clamped to 0,
  so a normal launcher can never beat 950. cleanyourtv (Toshiba, same build) also reports
  `cmd package set-home-activity` returns Success but is ignored, and launcher + firehomestarter refuse pm disable-user.
- `android.settings.SETTINGS` resolves into com.amazon.tv.launcher/.ui.SettingsActivity: the stock launcher also hosts
  the Settings entry point. Disabling it would take Settings with it.
- Remote: input device `WOBLE_INPUT_DEVICE`, KeyLayoutFile /system/usr/keylayout/Generic.kl, so Home arrives as a
  plain KEYCODE_HOME (3). com.amazon.tv.keypolicymanager is a small UI app (/system/app/KeyPolicyManagerUI), not
  in the key path as far as its components show.
- Accessibility: none enabled (accessibility_enabled=0, enabled_accessibility_services=null) at backup time.

## Options
| Option | How | Pros | Cons / Fire OS update risk |
|---|---|---|---|
| A. Accessibility key filter (chosen for proof) | AccessibilityService with flagRequestFilterKeyEvents; on API 28 the InputFilter sees keys after interceptKeyBeforeQueueing but before interceptKeyBeforeDispatching, where AOSP handles HOME. Swallow HOME, start our activity. | No root, instant, no flash of the Amazon UI | Long-press Home (quick settings panel) is swallowed too. Amazon could move HOME handling into beforeQueueing in an OTA, or block a11y enablement via adb. Service must stay enabled (Fire OS may kill it under memory pressure: 1 GB device). |
| B. Accessibility window fallback | Same service; on TYPE_WINDOW_STATE_CHANGED for Amazon HomeActivity, start ours | Catches boot and Back-to-home, survives an OTA that breaks A | Amazon home flashes ~200-400 ms first |
| C. Boot-completed autostart | BOOT_COMPLETED receiver launches ours | Simple | Only covers boot. Fire OS delays 3rd-party boot broadcasts; still need A/B for Home |
| D. Logcat watcher (Launcher Manager Mini style) | Needs READ_LOGS granted via adb, watch ActivityManager "START ... HOME" | No a11y | READ_LOGS is fragile across OTAs, polling cost, visible flash |
| E. Replace/disable Amazon launcher | pm disable-user | Real HOME | Refused on this build (protected); also kills Settings. Not an option. |

Plan: A + B in one service (HomeKeyService). Rollback: disable the service via secure settings (see README).

## Measured on the device (2026-10-20)
- `input keyevent` HOME/BACK bypass the accessibility InputFilter. A virtual uhid remote (`tools/vkey.sh`, shell is in
  group uhid, `/system/bin/hid`) goes through EventHub -> InputReader like the real remote, so tests use that.
- **HOME never reaches the accessibility key filter on this firmware.** AmzPhoneWindowManager consumes it earlier
  (queueing stage). MENU and BACK do reach the filter (long-press MENU handled by our service, Back-out-of-Settings works).
  => Option A (key filter) is dead for HOME on Fire OS 7.7.1.7. HOME works only through the window fallback (B):
  Amazon home flashes ~300 ms, then ours. Amazon's home sometimes re-fronts itself ~350 ms later; a re-check after the
  debounce handles it.
- PWM still sends CLOSE_SYSTEM_DIALOGS(reason=homekey) + amazon.intent.action.HOME_PRESSED (explicit to
  com.amazon.neodelegate) on every press, but `dumpsys activity broadcasts` shows third-party receivers as "Skipped"
  (ours, Netflix, com.amazon.aca). Not usable.
- Long-press HOME stays native: Amazon opens its quick panel (com.amazon.tv.settings.v2/.hud.HudActivity).
  Starting it ourselves is blocked: StartQSUIActivity needs com.amazon.tv.permission.LAUNCHER_SETTINGS
  (signature|privileged|amazon), StartBroadcastReceiver needs com.amazon.permission.RECEIVE_QS_LAUNCH_ACTION,
  HOME_LONGPRESSED is a protected broadcast.
- Fire OS Settings is a section of Amazon's HomeActivity: android.settings.SETTINGS -> settings.v2/.compliance.
  CTSDummySettingsHandler -> HomeActivity_vNext (has extras). The fallback must not fire there ("settings session").
- Remaining blind spot: HOME pressed while Amazon's HomeActivity is already in front (Settings section) produces no
  event we can see. Only fix found: READ_LOGS (pm grant) + watch ActivityManager "START ... cat=[HOME]" lines.

## Home overlay (2026-10-20)
- Measured with real-path keys (uhid): a HOME press makes our launch "held" for ~5 s
  (`W ActivityManager: Activity start request from 10040 stopped`). Our home lands ~4-5 s after a single press.
- HomeOverlay covers that gap: TYPE_ACCESSIBILITY_OVERLAY (not subject to the app-switch lock), software-rendered into
  an RGB_565 surface, showing a still of our home with a live clock. Appears 60-350 ms after Amazon's home event,
  comes off when MainActivity gets window focus, hard 7 s timeout. Keys swallowed only while it is up (volume/mute/power/
  Home pass), a release swallowed only if its press was.
- Snapshot: software draw of the window (decor.draw into an RGB_565 bitmap, ~75 ms) written row by row (~70 ms).
  PixelCopy was tried first and rejected: one GPU read-back left ~38 MB of render targets cached in the process
  for good (A/B: PSS 50 -> 90 MB, GL 17 -> 55 MB). For the software draw to match the screen, tiles no longer use
  clipToOutline; banners are RoundedBitmapDrawables.
- Our overlay window itself emits TYPE_WINDOW_STATE_CHANGED (pkg ours, class android.widget.FrameLayout). Harmless
  for the logic (counts as "ours").
- Triple HOME within ~3 s: lock keeps getting renewed, home lands ~10 s after the first press; the overlay times out at 7 s
  (by design) so Amazon's home shows for ~3 s at the end.
- Fixed while reviewing: long-press MENU used to pass MENU down and swallow MENU up, which leaves InputDispatcher
  auto-repeating MENU into the focused window. MENU is never consumed now.
