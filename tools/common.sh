# Sourced by the other tools. Set TV_HOST to your Fire TV's IP address first:
#   export TV_HOST=10.0.0.50        (Settings > My Fire TV > About > Network)
[ -n "$TV_HOST" ] || { echo "Set TV_HOST to your Fire TV's IP address first, e.g.  export TV_HOST=10.0.0.50" >&2; exit 1; }
TV="$TV_HOST:${TV_PORT:-5555}"
ADB_BIN="${ADB_BIN:-adb}"
command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "adb not found. Install Android platform-tools (e.g. brew install android-platform-tools)." >&2; exit 1; }
ADB="$ADB_BIN -s $TV"
PKG=com.zoddiacc.tvhome
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ADB_BIN" connect "$TV" >/dev/null 2>&1
