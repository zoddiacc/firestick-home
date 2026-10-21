package com.zoddiacc.tvhome;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Process lifecycle bookkeeping for the low-memory check. If the previous process was in the foreground
 * (MainActivity resumed) and never reached onPause, it was killed while visible: on this 1 GB stick that is
 * almost always lowmemorykiller. tools/lmk-watch.sh pairs this with the kernel/AM side of the story.
 */
public class App extends Application {
    static final String TAG = "TvHome";
    private static final String PREFS = "lifecycle";
    /** Shared with AllAppsActivity so it opens instantly. */
    volatile java.util.List<AppInfo> allApps;
    /** elapsedRealtime when we last opened Amazon Settings; HomeKeyService lets Amazon's home show settings after it. */
    static volatile long settingsOpenedAt;
    /** How many of our activities (home, All apps) are resumed right now. 0 = something else is in front. */
    static volatile int ownResumed;
    /** True while PhotoDream is showing; nothing should start activities then. */
    static volatile boolean dreaming;
    /** elapsedRealtime when we last opened something (an app, All apps, Settings, App info). */
    static volatile long ownLaunchAt;
    /** True while MainActivity has window focus, i.e. our real home is what the viewer sees. */
    static volatile boolean homeFocused;
    /** Set while HomeKeyService is connected, so activities can tell it when home gets covered. */
    static volatile HomeKeyService homeService;

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean wasForeground = p.getBoolean("foreground", false);
        int prevPid = p.getInt("pid", 0), starts = p.getInt("starts", 0) + 1;
        long lastSeen = p.getLong("lastSeen", 0);
        String cause;
        long updated = 0;
        try { updated = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; } catch (Exception ignored) { }
        if (prevPid == 0) cause = "first start";
        else if (updated != p.getLong("lastUpdate", 0)) cause = "app updated";
        else if (System.currentTimeMillis() - lastSeen > android.os.SystemClock.elapsedRealtime()) cause = "device rebooted";
        else cause = wasForeground ? "KILLED WHILE VISIBLE (likely lowmemorykiller)" : "killed in background (likely lowmemorykiller)";
        p.edit().putInt("starts", starts).putInt("pid", Process.myPid()).putLong("lastUpdate", updated)
                .putBoolean("foreground", false).putLong("lastSeen", System.currentTimeMillis()).apply();
        // One greppable line per process start:  adb logcat -s TvHome | grep LAUNCHER_START
        String msg = "LAUNCHER_START pid=" + Process.myPid() + " prevPid=" + prevPid + " starts=" + starts
                + " cause=" + cause + (lastSeen > 0 ? " lastSeenAgoS=" + (System.currentTimeMillis() - lastSeen) / 1000 : "");
        Log.i(TAG, msg);
        record(msg);
    }

    /** Last time we know we were alive; used to tell a kill from a reboot. */
    void heartbeat() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("lastSeen", System.currentTimeMillis()).apply();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.i(TAG, "onTrimMemory level=" + level);
        heartbeat();
        if (level >= TRIM_MEMORY_RUNNING_LOW) record("onTrimMemory level=" + level);
    }

    void setForeground(boolean fg) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("foreground", fg).apply();
    }

    /** Append to files/lifecycle.log, capped at 64 KB. */
    synchronized void record(String line) {
        try {
            File f = new File(getFilesDir(), "lifecycle.log");
            if (f.length() > 64 * 1024) f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + line + "\n");
            }
        } catch (Exception ignored) {
        }
    }
}
