#!/bin/sh
# Use the launcher's photo screensaver, or put the previous one back.
#   tools/screensaver.sh on       Fire OS shows it after the idle time set in Settings (5 min by default)
#   tools/screensaver.sh off      back to the value saved by tools/backup.sh (Amazon's screensaver)
. "$(dirname "$0")/common.sh"
VALUES="$ROOT/backup/before/values.sh"
case "$1" in
  on)  $ADB shell settings put secure screensaver_components $PKG/$PKG.PhotoDream;;
  off) SCREENSAVER=""; [ -f "$VALUES" ] && . "$VALUES"
       if [ -z "$SCREENSAVER" ] || [ "$SCREENSAVER" = null ]; then
         SCREENSAVER=com.amazon.ftv.screensaver/.app.services.ScreensaverService
       fi
       $ADB shell settings put secure screensaver_components "$SCREENSAVER";;
  *) echo "usage: $0 on|off"; exit 1;;
esac
echo "screensaver: $($ADB shell settings get secure screensaver_components)"
