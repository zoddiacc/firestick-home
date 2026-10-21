#!/bin/sh
# Watch for the launcher being killed or restarted. Ctrl-C to stop.
#   LAUNCHER_START lines come from the app itself (cause=...); the rest are lmkd / ActivityManager.
. "$(dirname "$0")/common.sh"
$ADB logcat -v time -b main -b system -b events \
  | grep --line-buffered -E "LAUNCHER_START|lowmemorykiller.*zoddiacc|lmkd.*zoddiacc|am_kill.*zoddiacc|am_proc_died.*zoddiacc|Process com.zoddiacc.tvhome .*died|Killing .*zoddiacc"
