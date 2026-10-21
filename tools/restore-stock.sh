#!/bin/sh
# Put the Fire TV back to how it was before. Asks before doing anything.
#   1. turn off the Home service (accessibility)   2. restore the previous screensaver
#   3. uninstall the launcher                       4. re-enable every debloated package, reset animation scales
. "$(dirname "$0")/common.sh"
cd "$ROOT"
N=0; [ -f backup/applied.log ] && N=$(cut -f3 backup/applied.log | sort -u | wc -l | tr -d ' ')
printf "Restore the Fire TV at %s to stock (Home service off, previous screensaver, uninstall launcher, re-enable %s packages)? [y/N] " "$TV" "$N"
read ans; [ "$ans" = y ] || { echo "nothing changed"; exit 0; }
# 1. accessibility, as saved by tools/backup.sh (on a stock stick: unset / 0)
A11Y_SERVICES=null; A11Y_ENABLED=0
[ -f backup/before/values.sh ] && . ./backup/before/values.sh
if [ -z "$A11Y_SERVICES" ] || [ "$A11Y_SERVICES" = null ]; then
  $ADB shell settings delete secure enabled_accessibility_services
else
  $ADB shell settings put secure enabled_accessibility_services "$(echo "$A11Y_SERVICES" | sed "s#$PKG/$PKG.HomeKeyService:\{0,1\}##")"
fi
$ADB shell settings put secure accessibility_enabled "${A11Y_ENABLED:-0}"
# 2. screensaver
tools/screensaver.sh off
# 3. launcher (its photo, snapshot, config and caches go with it)
$ADB uninstall $PKG
# 4. packages + animation scales
tools/restore.sh
$ADB shell input keyevent KEYCODE_HOME
echo "stock restored. Compare:  $ADB shell pm list packages -d   with backup/before/packages-disabled.txt"
