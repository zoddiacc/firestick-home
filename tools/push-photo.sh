#!/bin/sh
# Prepare and push the home background photo (also used by the screensaver).
#   tools/push-photo.sh [path/to/photo]     default: photo= in config.txt, else photos/home.jpg
# Strips all metadata (EXIF/GPS/XMP/ICC), scales to cover 1440x1080 and centre-crops (4:3), JPEG 85%,
# then pushes to the launcher's external files dir; the launcher moves it to app-private storage on resume.
# A 4:3 photo with the faces in the upper half works best. Needs Python 3 with Pillow (pip install Pillow).
set -e
. "$(dirname "$0")/common.sh"
CFG_PHOTO=$( [ -f "$ROOT/config.txt" ] && sed -n 's/^photo=//p' "$ROOT/config.txt" | tail -1 || true )
SRC="${1:-${CFG_PHOTO:-photos/home.jpg}}"
case "$SRC" in /*) ;; *) [ -f "$SRC" ] || SRC="$ROOT/$SRC";; esac
OUT="$ROOT/photos/out"; mkdir -p "$OUT"
PY="${PYTHON:-python3}"; [ -x "$ROOT/.venv/bin/python" ] && PY="$ROOT/.venv/bin/python"
[ -f "$SRC" ] || { echo "missing $SRC"; exit 1; }
"$PY" -I - "$SRC" "$OUT/hero.jpg" <<'PYEOF'
import sys
from PIL import Image, ImageOps
src, dst = sys.argv[1], sys.argv[2]
im = ImageOps.exif_transpose(Image.open(src)).convert("RGB")      # honour orientation, then drop it
im = ImageOps.fit(im, (1440, 1080), Image.LANCZOS, centering=(0.5, 0.5))
clean = Image.new("RGB", im.size); clean.paste(im)                 # new image: no info dict carried over
clean.save(dst, "JPEG", quality=85, optimize=True, progressive=True)
PYEOF
if strings "$OUT/hero.jpg" | grep -qiE "Exif|GPS|xmpmeta|ICC_PROFILE"; then echo "metadata still present, aborting"; exit 1; fi
echo "prepared $OUT/hero.jpg: $(wc -c < "$OUT/hero.jpg" | tr -d ' ') bytes, no EXIF/GPS/XMP"
D=/sdcard/Android/data/$PKG/files
$ADB shell mkdir -p "$D"
$ADB push "$OUT/hero.jpg" "$D/hero.jpg" >/dev/null
$ADB shell am start -n $PKG/.MainActivity >/dev/null   # onResume imports it
sleep 4
$ADB logcat -d -s TvHome | grep -i photo | tail -2
