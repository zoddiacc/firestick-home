# My Home: a calm home screen for Fire TV

A small, ad-free home screen for **Amazon Fire TV sticks running Fire OS 7**, plus an optional, reversible
debloat list. Your own photo fills the background, your streaming apps sit in one row, and the same photo becomes
the screensaver. No account, no tracking, no ads; the only network call is the weather (and you can turn that off).

| Home, Netflix focused | Home, YouTube focused |
|---|---|
| ![Home screen with Netflix focused](docs/screenshots/home-netflix.png) | ![Home screen with YouTube focused](docs/screenshots/home-youtube.png) |
| **All apps** | **Screensaver** |
| ![All apps grid](docs/screenshots/all-apps.png) | ![Photo screensaver](docs/screenshots/screensaver.png) |

## What it does
- **Home screen**: clock and date, weather for your town, RAM use, a big title for the focused app, and a
  favourites row using each app's own TV banner. **All apps** and **Settings** tiles at the end of the row.
- **Your photo** on the right, the left side fades to dark so text stays readable. No photo set: a neutral dark gradient.
- **Remote**: OK opens, Menu on a tile opens its App info, long-press Menu opens Fire TV Settings.
  Long-press Home still opens Amazon's quick panel.
- **Screensaver**: your photo full screen with a slow drift and a small clock that moves now and then (no burn-in).
- **Small**: plain Java and Android Views, ~175 KB APK, ~50 MB RAM.
- **Debloat** (optional, separate): disables Amazon telemetry, ads, sponsored rows and preloaded apps in small
  batches, each followed by a reboot and a check. Nothing is uninstalled.

## Supported devices
- **Tested**: Fire TV Stick Lite (AFTSS), Fire OS 7.7.1.7 (Android 9).
- **Probably works**: other Fire OS 7 (Android 9 based) sticks and cubes. Untested; the Home button workaround
  depends on Fire OS internals, so check with the steps below and use the rollback if it misbehaves.
- **Not supported**: **Vega OS** devices (e.g. Fire TV Stick 4K Select): they do not run Android apps.
  Fire OS 8 is untested.

## Before you start
You need a computer with:
- `adb` (Android platform-tools), **JDK 17** and the **Android SDK** command-line tools.
  On macOS with Homebrew: `brew install android-platform-tools openjdk@17 android-commandlinetools`.
- Python 3, plus Pillow for the photo step (`python3 -m pip install Pillow`).

On the Fire TV: **Settings > My Fire TV > Developer options > ADB debugging: ON**. If Developer options is missing,
open **Settings > My Fire TV > About** and press OK on the device name 7 times. The IP address is under
**Settings > My Fire TV > About > Network**.

## Setup
```sh
export TV_HOST=10.0.0.50          # your Fire TV's IP address
adb connect $TV_HOST:5555            # accept the "Allow USB debugging?" prompt on the TV

tools/backup.sh                      # 1. save the stick's current state (backup/, gitignored). Do this first.

cp config.example.txt config.txt     # 2. your settings: home name, town + coordinates, photo, favourites
$EDITOR config.txt

tools/build-launcher.sh              # 3. build and install; opens once so you can look at it
tools/push-config.sh                 # 4. send config.txt to the launcher
tools/push-photo.sh                  # 5. optional: your photo (metadata stripped, cropped to 1440x1080)

tools/build-launcher.sh --enable-home  # 6. turn on the Home button service; rolls back by itself if Home does not land
tools/screensaver.sh on              # 7. optional: photo screensaver
```
Then turn **ADB debugging off** again on the TV, unless you plan more changes.

If the build cannot find Java or the SDK, set `JAVA_HOME` and `ANDROID_HOME`. The APK is signed with your local
Android debug key; it is meant for sideloading over ADB, not for an app store.

## Home button: how it works and its limits
Fire OS sends the Home button only to Amazon's own launcher, and no app setting can change that. So the launcher
includes an accessibility service ("My Home: Home button", reads no screen content) that notices when Amazon's home
comes to the front and opens this one instead. Details and measurements: [docs/home-key.md](docs/home-key.md).

What that means in practice:
- **Amazon's home flashes for about a second.** After every Home press Android blocks app launches for ~5 s.
  To hide that, the service covers Amazon's home with a still picture of this home (with a live clock) about
  0.1-0.35 s after the press, until the real one is ready. You briefly see Amazon's home before the cover appears.
  Remote presses during the cover (up to 7 s) are ignored on purpose; volume and power still work.
- **Home from inside Fire TV Settings stays on Amazon's home.** Fire OS draws Settings inside Amazon's home screen and
  gives no signal an app can see. Press **Back** instead (or open any app and press Home).
- **Home within ~4 s of opening the quick panel** may be taken as "opened Settings" and stay on Amazon's home. Back fixes it.
- **Pressing Home 2-3 times quickly**: the cover is limited to 7 s, so Amazon's home can show for a few seconds.
- **Netflix** asks before exiting on Back; use Home.
- A **Fire OS update** may change any of this. If Home stops landing here, check `adb logcat -s TvHome`.
- Never run `adb shell am force-stop com.zoddiacc.tvhome`: Android then silently turns the Home service off. To restart
  the launcher use `tools/restart-launcher.sh`.

## Change the photo, town or favourites
Edit `config.txt`, then:

| Change | Command |
|---|---|
| Photo (`photo=` or a path) | `tools/push-photo.sh` or `tools/push-photo.sh ~/Pictures/new.jpg` |
| Home name, town, latitude, longitude | `tools/push-config.sh` |
| Favourites, hidden apps | `tools/push-config.sh` |

- **Weather** uses [Open-Meteo](https://open-meteo.com) (no key), refreshed every 30 minutes. Find coordinates on
  any map (right-click > coordinates). Leave `town=` empty to turn weather off; the launcher then makes no network calls.
- **Favourites** are package names; list installed ones with `adb shell pm list packages -3`.
- The photo is cropped to 4:3 around the centre. Put faces in the upper half so they clear the top bar and the app row.

## Debloat (optional)
`debloat.txt` lists Amazon packages to **disable** (never uninstall), in 5 batches, each with a one-line reason:
1. telemetry and metrics, 2. ads, sponsored content, ad ID, ACR, 3. Live tab, channels, promos, tutorials,
4. Alexa extras and multi-room audio (voice search stays), 5. preloaded Amazon apps. Batch 6 is commented out (untested).

```sh
tools/batch.py show 1        # what is in batch 1 and whether it is installed / enabled
tools/batch.py apply 1       # disable it, log to backup/applied.log, reboot, check Settings/Netflix/Prime/YouTube open
tools/batch.py restore 1     # undo one batch
```

**Warnings**
- Use at your own risk. Apply **one batch at a time** and use the stick for a while before the next.
- Batch 3 removes the **Live** tab and free channels. Batch 4 removes **Alexa alarms, notifications and multi-room
  audio**. Batch 5 removes **Amazon Kids, Music, Photos and Silk**. Skip a batch if you use those.
- Never touched: Amazon's launcher, Settings, Prime Video / DRM, sign-in, the Appstore, **system updates** (they stay
  on), voice search, casting, Bluetooth and the remote.
- Debloating does **not** remove the sponsored banner from Amazon's home screen; that is drawn by Amazon's launcher,
  which is why this project has its own home screen.
- Once, during testing, all disabled packages came back enabled without a reboot or update (cause unknown). Check
  with `adb shell pm list packages -d` now and then and re-apply a batch if needed.

## Full restore to stock
```sh
tools/restore-stock.sh       # asks first
```
It turns the Home service off, puts the previous screensaver back, uninstalls the launcher (its photo, config and
caches go with it), re-enables every package in `backup/applied.log` and resets animation scales, using the values
`tools/backup.sh` saved. Each step also works on its own:
```sh
adb -s $TV_HOST:5555 shell settings delete secure enabled_accessibility_services
adb -s $TV_HOST:5555 shell settings put secure accessibility_enabled 0
tools/screensaver.sh off
adb -s $TV_HOST:5555 uninstall com.zoddiacc.tvhome
tools/restore.sh             # or: tools/restore.sh 3   (one batch only)
```
Afterwards `adb shell pm list packages -d` should match `backup/before/packages-disabled.txt`.

## For developers
- `tools/vkey.sh home|back|longhome|menu|longmenu|up|down|left|right|ok` presses keys through a virtual remote that
  takes the same input path as the real one. (`adb shell input keyevent` skips the accessibility filter and the
  app-switch lock, so it gives misleading results.)
- `tools/home-tests.sh [--reboot]` and `tools/overlay-tests.sh` run the Home button tests (screenshots go to
  `screenshots/` and `test-results/`, both gitignored).
- `tools/lmk-watch.sh` shows when Android's low-memory killer stops the launcher.

| Path | What |
|---|---|
| `launcher/` | the launcher (Gradle, Java, no Compose) |
| `config.example.txt` | settings template; copy to `config.txt` |
| `debloat.txt` | packages to disable, by batch, with reasons |
| `tools/` | build, install, config, photo, debloat, restore and test scripts |
| `docs/home-key.md` | how Fire OS routes the Home button and why the design is what it is |

## Credits
- Made by [Zoddiacc](https://github.com/zoddiacc).
- [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by Gathin (MIT): the Fire TV example debloat lists that
  `debloat.txt` started from, and the findings on Fire OS Home handling. No cleanyourtv code is included. Its
  licence notice is below.
- Weather data by [Open-Meteo](https://open-meteo.com) (CC BY 4.0).
- Demo photo based on a photo by Erik Torres on Pexels, edited.

<details><summary>cleanyourtv licence</summary>

```
MIT License

Copyright (c) 2026 Gathin

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
</details>

## Licence
MIT, Copyright (c) 2026 [Zoddiacc](https://github.com/zoddiacc), see [LICENSE](LICENSE). Not affiliated with Amazon. Fire TV and Fire OS are trademarks of Amazon.
