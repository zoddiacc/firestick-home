#!/bin/sh
# Restart the launcher process WITHOUT `am force-stop`.
# Never force-stop com.zoddiacc.tvhome: Android 9's AccessibilityManagerService removes a force-stopped package's
# services from enabled_accessibility_services (Home interception silently turns off), and the app is left in the
# "stopped" state so BOOT_COMPLETED is not delivered. Reinstalling the same APK restarts the process and keeps both.
. "$(dirname "$0")/common.sh"
$ADB install -r "$ROOT/launcher/app/build/outputs/apk/release/app-release.apk" | tail -1
sleep 6
echo "a11y: $($ADB shell settings get secure enabled_accessibility_services)"
