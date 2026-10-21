#!/bin/sh
# Build and install the launcher (com.zoddiacc.tvhome), optionally enable the Home service with auto-rollback.
#   tools/build-launcher.sh                 build + install + launch once + screenshot  (no settings touched)
#   tools/build-launcher.sh --enable-home   also enable the accessibility Home service, press Home,
#                                           and roll back if the launcher is not in front afterwards
#   tools/build-launcher.sh --rollback      put the accessibility settings back to backup/before/values.sh
# Needs JDK 17 and the Android SDK. Set JAVA_HOME / ANDROID_HOME if they are not found automatically.
set -e
. "$(dirname "$0")/common.sh"
SVC=$PKG/$PKG.HomeKeyService
VALUES="$ROOT/backup/before/values.sh"
[ -z "$JAVA_HOME" ] && [ -d /opt/homebrew/opt/openjdk@17 ] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17
[ -z "$ANDROID_HOME" ] && [ -d /opt/homebrew/share/android-commandlinetools ] && export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

shot() {
  f="$ROOT/screenshots/$(date +%Y%m%d-%H%M%S)-$1.png"; mkdir -p "$ROOT/screenshots"
  $ADB exec-out screencap -p > "$f"; echo "screenshot: $f"
}
focus() { $ADB shell dumpsys window | grep mCurrentFocus | sed 's/.* u0 //; s/[/}].*//'; }

rollback() {
  echo "rolling back accessibility settings"
  [ -f "$VALUES" ] && . "$VALUES"
  if [ -z "$A11Y_SERVICES" ] || [ "$A11Y_SERVICES" = null ]; then
    $ADB shell settings delete secure enabled_accessibility_services
  else
    $ADB shell settings put secure enabled_accessibility_services "$A11Y_SERVICES"
  fi
  $ADB shell settings put secure accessibility_enabled "${A11Y_ENABLED:-0}"
  $ADB shell input keyevent KEYCODE_HOME
}

case "$1" in --rollback) rollback; exit 0;; esac
if [ "$1" = "--enable-home" ] && [ ! -f "$VALUES" ]; then echo "run tools/backup.sh first"; exit 1; fi

cd "$ROOT/launcher"
./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
ls -l "$APK"
$ADB install -r "$APK"
$ADB shell am start -n $PKG/.MainActivity >/dev/null
sleep 6
echo "focus: $(focus)"
$ADB shell dumpsys meminfo $PKG | grep -E "TOTAL( PSS)?:|TOTAL +[0-9]" | head -1
shot launcher

if [ "$1" = "--enable-home" ]; then
  cur=$($ADB shell settings get secure enabled_accessibility_services | tr -d '\r')
  case "$cur" in *"$SVC"*) new="$cur";; null|"") new="$SVC";; *) new="$cur:$SVC";; esac
  $ADB shell settings put secure enabled_accessibility_services "$new"
  $ADB shell settings put secure accessibility_enabled 1
  sleep 3
  # leave the launcher, then press Home and see where we land
  $ADB shell am start -a android.settings.SETTINGS >/dev/null; sleep 4
  $ADB shell input keyevent KEYCODE_HOME; sleep 4
  f=$(focus); echo "after HOME: $f"
  if [ "$f" = "$PKG" ]; then shot home-landed; echo "HOME OK"; else shot home-failed; rollback; exit 1; fi
fi
