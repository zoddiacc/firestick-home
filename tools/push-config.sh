#!/bin/sh
# Push config.txt (home name, town, favourites, hidden apps) to the launcher and restart it.
#   tools/push-config.sh [path/to/config.txt]
# Start from config.example.txt:  cp config.example.txt config.txt   (config.txt is gitignored)
. "$(dirname "$0")/common.sh"
SRC="${1:-$ROOT/config.txt}"
[ -f "$SRC" ] || { echo "missing $SRC (copy config.example.txt to config.txt and edit it)"; exit 1; }
D=/sdcard/Android/data/$PKG/files
$ADB shell mkdir -p "$D" && $ADB push "$SRC" "$D/config.txt" >/dev/null && echo "pushed $SRC"
# weather is cached for 30 min per town; a new town is fetched on the next resume
"$ROOT/tools/restart-launcher.sh"
