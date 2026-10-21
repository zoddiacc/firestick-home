#!/usr/bin/env python3
"""Apply debloat.txt one batch at a time, with verification. Needs `adb` on PATH and TV_HOST set.

  batch.py show <N>            list packages in batch N and their current state
  batch.py apply <N>           pm disable-user --user 0 each package, log, reboot, verify
  batch.py verify [--reboot]   boot_completed, Settings opens, Netflix/Prime/YouTube launch, HOME returns
  batch.py restore [N]         re-enable packages from backup/applied.log (all, or one batch)
"""
import datetime, os, re, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOST = os.environ.get("TV_HOST")
if not HOST:
    sys.exit("Set TV_HOST to your Fire TV's IP address first, e.g.  export TV_HOST=10.0.0.50")
SERIAL = f"{HOST}:{os.environ.get('TV_PORT', '5555')}"
ADB = os.environ.get("ADB_BIN", "adb")
LOG = os.path.join(ROOT, "backup", "applied.log")
SHOTS = os.path.join(ROOT, "screenshots")
CHECKS = [  # (label, am start args, packages allowed to hold focus)
    ("Settings", "-a android.settings.SETTINGS", ("com.amazon.tv.launcher", "com.amazon.tv.settings")),
    ("Netflix", "-n com.netflix.ninja/.MainActivity", ("com.netflix.ninja",)),
    ("Prime Video", "-n com.amazon.firebat/com.amazon.pyrocore.IgnitionActivity", ("com.amazon.firebat",)),
    ("YouTube", "-n com.amazon.firetv.youtube/dev.cobalt.app.MainActivity", ("com.amazon.firetv.youtube",)),
]


def batches():
    out, cur = {}, None
    for line in open(os.path.join(ROOT, "debloat.txt")):
        m = re.match(r"## batch (\d+):", line)
        if m:
            cur = int(m.group(1)); out[cur] = []; continue
        line = line.split("#", 1)[0].strip()
        if cur and line:
            out[cur].append(line)
    return out


def adb(*args, t=60):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=t).stdout


def sh(cmd, t=60):
    return adb("shell", cmd, t=t)


def connect(wait=0):
    end = time.time() + wait
    while True:
        subprocess.run([ADB, "connect", SERIAL], capture_output=True, timeout=30)
        if sh("echo ok", t=15).strip() == "ok":
            return
        if time.time() > end:
            sys.exit(f"cannot reach {SERIAL} (is ADB debugging on?)")
        time.sleep(5)


def focus():
    m = re.search(r"mCurrentFocus=Window\{\S+ u0 ([\w.]+)", sh("dumpsys window | grep mCurrentFocus"))
    return m.group(1) if m else "?"


def shot(tag):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, f"{datetime.datetime.now():%Y%m%d-%H%M%S}-{tag}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    return path


def verify(reboot=False):
    connect()
    if reboot:
        print("rebooting...")
        try:
            sh("reboot", t=10)
        except subprocess.TimeoutExpired:
            pass
        time.sleep(30)
        connect(wait=240)
        for _ in range(60):
            if sh("getprop sys.boot_completed").strip() == "1":
                break
            time.sleep(5)
        time.sleep(20)  # let the launcher settle
    ok = sh("getprop sys.boot_completed").strip() == "1"
    print(f"{'PASS' if ok else 'FAIL'}  boot_completed")
    home = sh("cmd package resolve-activity --brief -a android.intent.action.MAIN "
              "-c android.intent.category.HOME | tail -1").strip()
    print(f"{'PASS' if 'com.amazon.tv.launcher' in home else 'FAIL'}  HOME -> {home}")
    ok &= "com.amazon.tv.launcher" in home
    sh("input keyevent KEYCODE_HOME"); time.sleep(4)
    print(f"      home screen: {shot('home')}")
    for label, args, allowed in CHECKS:
        sh(f"am start {args}"); time.sleep(10)
        f = focus()
        good = f.startswith(allowed)
        ok &= good
        print(f"{'PASS' if good else 'FAIL'}  {label:12s} focus={f}")
        sh("input keyevent KEYCODE_HOME"); time.sleep(3)
    print("ALL PASS" if ok else "SOMETHING FAILED, stop and look")
    return ok


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "show":
        pkgs = batches()[int(args[0])]
        connect()
        dis = set(sh("pm list packages -d").replace("package:", "").split())
        allp = set(sh("pm list packages").replace("package:", "").split())
        for p in pkgs:
            print(f"{p:45s} {'disabled' if p in dis else 'enabled' if p in allp else 'NOT INSTALLED'}")
    elif cmd == "apply":
        n = int(args[0]); pkgs = batches()[n]
        connect()
        os.makedirs(os.path.dirname(LOG), exist_ok=True)
        with open(LOG, "a") as log:
            for p in pkgs:
                out = sh(f"pm disable-user --user 0 {p} 2>&1").strip().replace("\n", " ")
                log.write(f"{datetime.datetime.now():%F %T}\t{n}\t{p}\t{out}\n")
                print(f"{p:45s} {out}")
        sys.exit(0 if verify(reboot=True) else 1)
    elif cmd == "verify":
        sys.exit(0 if verify(reboot="--reboot" in args) else 1)
    elif cmd == "restore":
        want = args[0] if args else None
        rows = [l.rstrip("\n").split("\t") for l in open(LOG) if l.strip()]
        connect()
        for _, n, p, _ in reversed(rows):
            if want is None or n == want:
                print(f"{p:45s} {sh(f'pm enable --user 0 {p} 2>&1').strip()}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
