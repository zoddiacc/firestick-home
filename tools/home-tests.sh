#!/bin/bash
# Runs the HOME acceptance tests against the stick and screencaps each step.
. "$(dirname "$0")/common.sh"; cd "$ROOT"; mkdir -p screenshots
a(){ $ADB "$@"; }
focus(){ a shell dumpsys window | grep mCurrentFocus | sed 's/.* u0 //; s/}.*//'; }
shot(){ f="screenshots/$(date +%Y%m%d-%H%M%S)-test-$1.png"; a exec-out screencap -p > "$f"; echo "      $f"; }
PASS=0; FAIL=0
check(){ # name, expected-substring
  sleep "${3:-5}"; f=$(focus)
  if [[ "$f" == *"$2"* ]]; then echo "PASS  $1  -> $f"; PASS=$((PASS+1)); else echo "FAIL  $1  -> $f (want $2)"; FAIL=$((FAIL+1)); fi
  shot "$1"
}
V=$PKG
# NOTE: never `am force-stop` the launcher here; it disables the accessibility service (see restart-launcher.sh).
a logcat -c
a shell input keyevent KEYCODE_WAKEUP; sleep 1

echo "== 0. Home from an app"
a shell am start -n com.amazon.firetv.youtube/dev.cobalt.app.MainActivity >/dev/null; sleep 10
tools/vkey.sh home;                             check home-from-app $V
echo "== 1. Home press from Settings"
a shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
tools/vkey.sh home;                             check home-press-from-settings $V   # KNOWN GAP: Fire OS hides HOME here

echo "== 2. Settings tile / opening Settings must NOT bounce back"
a shell am start -a android.settings.SETTINGS >/dev/null
check settings-stays com.amazon.tv 8
tools/vkey.sh back;                             check settings-back $V

echo "== 3. Home out of Netflix (Netflix keeps Back for its exit dialog, which is fine)"
a shell am start -n com.netflix.ninja/.MainActivity >/dev/null; sleep 12
tools/vkey.sh home;                             check netflix-home $V

echo "== 4. Back out of YouTube"
a shell am start -n com.amazon.firetv.youtube/dev.cobalt.app.MainActivity >/dev/null; sleep 12
for i in 1 2 3; do tools/vkey.sh back; sleep 2; f=$(focus); [[ "$f" == *youtube* ]] || break; done
check youtube-back $V

echo "== 5. Long-press Home -> Amazon quick panel (native), Back -> our home"
tools/vkey.sh longhome;                         check longpress-home-panel HudActivity 3
tools/vkey.sh back;                             check panel-back $V
echo "== 5b. Long-press Menu -> Settings (our filter), Back -> our home"
tools/vkey.sh longmenu;                         check longpress-menu com.amazon.tv 4
tools/vkey.sh back;                             check menu-settings-back $V

echo "== 6. Sleep and wake"
a shell input keyevent KEYCODE_SLEEP; sleep 6
a shell input keyevent KEYCODE_WAKEUP;          check wake $V 8

echo "== 7. Voice button (injected; the real mic button needs a person)"
a shell input keyevent KEYCODE_SEARCH; sleep 3; shot voice-open
tools/vkey.sh back;                             check voice-dismiss $V 4

if [ "$1" = "--reboot" ]; then
  echo "== 8. Reboot"
  a reboot; sleep 40
  for i in $(seq 1 40); do "$ADB_BIN" connect "$TV" >/dev/null 2>&1; [ "$(a shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 5; done
  check reboot $V 40
fi
echo "== TvHome log"; a logcat -d -s TvHome | grep -vE "beginning of" | tail -40
echo "RESULT: $PASS pass, $FAIL fail"
