#!/bin/sh
# Press remote keys through a virtual HID remote (uhid), so they travel the same path as the real remote:
# EventHub -> InputReader -> accessibility InputFilter -> PhoneWindowManager. `input keyevent` skips the filter.
#   tools/vkey.sh home | back | longhome | menu | longmenu | menuhome | up | down | left | right | ok
# Consumer-page usages: AC Home 0x0223 -> KEY_HOMEPAGE, AC Back 0x0224 -> KEY_BACK, Menu 0x0040 -> KEY_MENU,
# Menu Pick 0x0041 -> KEY_SELECT (DPAD_CENTER), Menu Up/Down/Left/Right 0x0042-0x0045 -> KEY_UP/DOWN/LEFT/RIGHT (Generic.kl).
. "$(dirname "$0")/common.sh"
DESC='[5,12,9,1,161,1,21,0,38,255,3,25,0,42,255,3,117,16,149,1,129,0,192]'
reg='{"id":7,"command":"register","name":"Test remote","vid":4660,"pid":22136,"descriptor":'"$DESC"'}'
press(){ printf '{"id":7,"command":"report","report":[%s,%s]}\n{"id":7,"command":"delay","duration":%s}\n{"id":7,"command":"report","report":[0,0]}\n' "$1" "$2" "$3"; }
d(){ printf '{"id":7,"command":"delay","duration":%s}\n' "$1"; }
case "$1" in
  home)     body="$(press 35 2 80)";;
  back)     body="$(press 36 2 80)";;
  longhome) body="$(press 35 2 1200)";;
  menu)     body="$(press 64 0 80)";;
  longmenu) body="$(press 64 0 1200)";;
  up)       body="$(press 66 0 80)";;
  down)     body="$(press 67 0 80)";;
  left)     body="$(press 68 0 80)";;
  right)    body="$(press 69 0 80)";;
  ok)       body="$(press 65 0 80)";;
  menuhome) body='{"id":7,"command":"report","report":[64,0]}
{"id":7,"command":"delay","duration":200}
{"id":7,"command":"report","report":[35,2]}
{"id":7,"command":"delay","duration":100}
{"id":7,"command":"report","report":[0,0]}';;
  *) echo "usage: $0 home|back|longhome|menu|longmenu|menuhome|up|down|left|right|ok"; exit 1;;
esac
# give InputReader time to add the device before the first report, and to deliver before unregistering
printf '%s\n%s\n%s\n%s\n' "$reg" "$(d 700)" "$body" "$(d 400)" | $ADB shell 'cat > /data/local/tmp/vkey.json && hid /data/local/tmp/vkey.json' >/dev/null 2>&1
