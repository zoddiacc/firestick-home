#!/bin/sh
# Undo the debloat.
#   tools/restore.sh          re-enable every package in backup/applied.log, put animation scales back
#   tools/restore.sh <N>      re-enable only batch N
set -e
. "$(dirname "$0")/common.sh"
cd "$ROOT"
if [ -f backup/applied.log ]; then python3 -I tools/batch.py restore "$@"; else echo "no backup/applied.log: nothing was debloated"; fi
if [ -z "$1" ]; then
  VALUES=backup/before/values.sh
  WINDOW_ANIM=1.0; TRANSITION_ANIM=1.0; ANIMATOR=null
  [ -f "$VALUES" ] && . "./$VALUES"
  for kv in "window_animation_scale=$WINDOW_ANIM" "transition_animation_scale=$TRANSITION_ANIM" "animator_duration_scale=$ANIMATOR"; do
    k=${kv%%=*}; v=${kv#*=}
    if [ -z "$v" ] || [ "$v" = null ]; then $ADB shell settings delete global "$k" >/dev/null; else $ADB shell settings put global "$k" "$v"; fi
  done
  echo "animation scales restored"
fi
