#!/bin/sh
# Save the stick's state BEFORE changing anything. Run once, first. Restore scripts read these files.
#   tools/backup.sh            writes backup/before/ (refuses to overwrite an existing backup)
# backup/ is gitignored: it holds your device's settings and package lists, keep it private.
. "$(dirname "$0")/common.sh"
OUT="$ROOT/backup/before"
[ -e "$OUT" ] && { echo "$OUT already exists; not overwriting the original backup"; exit 1; }
mkdir -p "$OUT"
save(){ $ADB shell "$2" > "$OUT/$1" 2>&1; echo "saved $1 ($(wc -l < "$OUT/$1" | tr -d ' ') lines)"; }
save packages-all.txt        "pm list packages"
save packages-disabled.txt   "pm list packages -d"
save packages-3rdparty.txt   "pm list packages -3"
save settings-secure.txt     "settings list secure"
save settings-global.txt     "settings list global"
save home-resolve.txt        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
save build.txt               "getprop ro.product.model; getprop ro.build.version.release; getprop ro.build.display.id"
# The few values the restore scripts put back:
{
  printf 'A11Y_SERVICES="%s"\n'   "$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')"
  printf 'A11Y_ENABLED="%s"\n'    "$($ADB shell settings get secure accessibility_enabled | tr -d '\r')"
  printf 'SCREENSAVER="%s"\n'     "$($ADB shell settings get secure screensaver_components | tr -d '\r')"
  printf 'WINDOW_ANIM="%s"\n'     "$($ADB shell settings get global window_animation_scale | tr -d '\r')"
  printf 'TRANSITION_ANIM="%s"\n' "$($ADB shell settings get global transition_animation_scale | tr -d '\r')"
  printf 'ANIMATOR="%s"\n'        "$($ADB shell settings get global animator_duration_scale | tr -d '\r')"
} > "$OUT/values.sh"
cat "$OUT/values.sh"
echo "backup in $OUT"
