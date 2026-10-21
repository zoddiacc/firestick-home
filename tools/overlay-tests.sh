#!/bin/bash
# Acceptance tests for the Home overlay (guardrails 1-6), all through the virtual uhid remote (tools/vkey.sh).
#   tools/overlay-tests.sh            all tests
#   tools/overlay-tests.sh failure    only the forced-failure test
# Never force-stops the launcher. The forced-failure switch file is always removed on exit.
. "$(dirname "$0")/common.sh"; cd "$ROOT"
a(){ $ADB "$@"; }
V=$PKG
FLAG=/sdcard/Android/data/$V/files/test_block_home
OUT=test-results/overlay-tests-$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT"
LOG=$OUT/logcat.txt
trap 'a shell rm -f $FLAG >/dev/null 2>&1; kill $LOGPID 2>/dev/null' EXIT
a logcat -v time -T 1 TvHome:I ActivityManager:I '*:S' > "$LOG" 2>&1 & LOGPID=$!
sleep 1

foc(){ a shell dumpsys window | grep mCurrentFocus | grep -oE "u0 [a-zA-Z0-9.]+/[^ }]*" | cut -c4-; }
overlay_up(){ a shell dumpsys window windows | grep -q "MyHomeOverlay" && echo yes || echo no; }
shot(){ f="$OUT/$(date +%H%M%S)-$1.png"; a exec-out screencap -p > "$f"; echo "      shot: $f"; }
pss(){ a shell dumpsys meminfo $V | awk '/^ +TOTAL /{t=$2} /Graphics:/{g=$2} /Native Heap:/{n=$3} END{print "PSS " t " KB (native " n ", graphics " g ")"}'; }
mark(){ m=$(printf '%s' "$1" | tr -c 'A-Za-z0-9 .,:_' '_'); a shell "log -t OVERLAY_TEST '$m'" >/dev/null; echo "== $1"; }
wait_overlay(){ for i in $(seq 1 30); do [ "$(overlay_up)" = yes ] && return 0; sleep 0.2; done; return 1; }
since(){ awk -v t="$1" '$2>=t' "$LOG" | grep -E "TvHome|ActivityManager: START.*(HOME|zoddiacc)" | cut -c7-170; }
open_app(){ a shell am start -n "$1" >/dev/null 2>&1; sleep 10; }
YT=com.amazon.firetv.youtube/dev.cobalt.app.MainActivity
NF=com.netflix.ninja/.MainActivity
home_now(){ for i in $(seq 1 30); do [[ "$(foc)" == $V/* ]] && return 0; sleep 0.5; done; return 1; }

run_single(){ # name, app component
  mark "$1: single Home from $2"; open_app "$2"; t=$(date +%H:%M:%S)
  tools/vkey.sh home; wait_overlay && echo "  overlay up" || echo "  overlay did NOT appear"
  tools/vkey.sh right; echo "  pressed RIGHT right after the overlay appeared"; echo "  focus now: $(foc)"; shot "$1-overlay"
  home_now && echo "  home landed" || echo "  home NOT landed within 15 s"
  sleep 1; echo "  overlay up after landing: $(overlay_up)"; shot "$1-landed"; since "$t"
}

if [ "$1" != "failure" ]; then
  a shell am start -n $V/.MainActivity >/dev/null 2>&1; sleep 6
  echo "RAM idle on home (before tests): $(pss)" | tee "$OUT/ram.txt"
  run_single "T1" "$YT"
  run_single "T3" "$NF"

  mark "T2: triple Home from YouTube"; open_app "$YT"; t=$(date +%H:%M:%S)
  tools/vkey.sh home; sleep 1.0; tools/vkey.sh home; sleep 0.3; tools/vkey.sh home
  echo "  overlay up: $(overlay_up)"; shot "T2-overlay"
  home_now && echo "  home landed" || echo "  home NOT landed within 15 s"; sleep 1; echo "  overlay up after: $(overlay_up)"; since "$t"

  mark "T5: long-press Home from YouTube -> Amazon quick panel must be visible"; open_app "$YT"; t=$(date +%H:%M:%S)
  tools/vkey.sh longhome; sleep 2.5; echo "  focus: $(foc)   overlay up: $(overlay_up)"; shot "T5-panel"
  tools/vkey.sh right; sleep 1; shot "T5-panel-after-right"; tools/vkey.sh back; sleep 3; echo "  after Back: $(foc)"; since "$t"

  mark "T7: long-press Menu on our home -> Settings; Back -> home (MENU is never consumed now)"; a shell am start -n $V/.MainActivity >/dev/null; sleep 4; t=$(date +%H:%M:%S)
  tools/vkey.sh longmenu; sleep 5; echo "  focus: $(foc)   overlay up: $(overlay_up)"; shot "T7-menu-settings"
  tools/vkey.sh back; sleep 4; echo "  after Back: $(foc)"; since "$t"

  mark "T6: Settings tile -> no overlay; Back -> home"; a shell am start -n $V/.MainActivity >/dev/null; sleep 4; t=$(date +%H:%M:%S)
  for i in 1 2 3 4 5 6 7; do tools/vkey.sh right; done; tools/vkey.sh ok; sleep 6; echo "  focus: $(foc)   overlay up: $(overlay_up)"; shot "T6-settings"
  tools/vkey.sh back; sleep 4; echo "  after Back: $(foc)"; since "$t"
fi

mark "T4: forced failure (home blocked) -> overlay must time out at 7 s and release input"
open_app "$YT"; a shell "mkdir -p /sdcard/Android/data/$V/files; touch $FLAG"; t=$(date +%H:%M:%S)
tools/vkey.sh home; sleep 2; echo "  +2s overlay up: $(overlay_up)"; echo "  RAM with overlay up: $(pss)" | tee -a "$OUT/ram.txt"; shot "T4-overlay"
tools/vkey.sh right; echo "  pressed RIGHT at ~+3s (must be swallowed)"
sleep 6; echo "  +9s overlay up: $(overlay_up)"; shot "T4-after-timeout-before-key"
tools/vkey.sh right; sleep 1; echo "  pressed RIGHT at ~+10s (must reach Amazon's home)"; shot "T4-after-timeout-after-key"
since "$t"
a shell rm -f $FLAG
a shell am start -n $V/.MainActivity >/dev/null 2>&1; sleep 6
echo "  RAM back on home after overlay: $(pss)" | tee -a "$OUT/ram.txt"
echo "results in $OUT"
