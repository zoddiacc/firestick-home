package com.zoddiacc.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Keeps this home screen on top on Fire OS, which gives HOME only to com.amazon.tv.launcher (filter priority 950).
 *
 * What Fire OS 7.7.1.7 actually allows (see docs/home-key.md):
 *  - HOME never reaches onKeyEvent: AmzPhoneWindowManager consumes it before the accessibility InputFilter.
 *    MENU, BACK and the D-pad do reach it.
 *  - Every HOME press calls stopAppSwitches(): for 5 s our activity starts are held.
 *
 * So:
 *  - Fallback: when Amazon's HomeActivity comes to the front (not as Settings), start our home. Debounced 500 ms,
 *    re-checked every 2 s until it lands (max 5), kill switch after 3 fallbacks in 10 s.
 *  - HomeOverlay: right when that happens, cover Amazon's home with a still of ours (TYPE_ACCESSIBILITY_OVERLAY is
 *    not held by the lock) until our home has window focus, 7 s at most. Remote keys are swallowed only while the
 *    overlay is up (volume/mute/power always pass), and a key release is swallowed only if its press was.
 *  - Settings: Fire OS shows Settings inside Amazon's HomeActivity; while in that "settings session" we leave it
 *    alone and show no overlay. Back out of it returns to our home.
 *  - MENU long press (or MENU held + HOME, on firmware that delivers HOME) opens Amazon Settings.
 * Reads no window content.
 *
 * Test hook: if <external files dir>/test_block_home exists, goHome() does not start our home (forced-failure test
 * for the overlay timeout). tools/overlay-tests.sh creates and always deletes it.
 *
 * Rollback: settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0
 */
public class HomeKeyService extends AccessibilityService {
    private static final String AMAZON_HOME_PKG = "com.amazon.tv.launcher";
    private static final String AMAZON_SETTINGS_PKG = "com.amazon.tv.settings.v2";
    private static final long SETTINGS_GRACE_MS = 4000;
    private static final long LONG_PRESS_MS = 600, DEBOUNCE_MS = 500, TRIP_WINDOW_MS = 10_000;
    private static final int TRIP_COUNT = 3;
    /** Retry interval after a goHome: a held start only runs when the next allowed start happens. */
    private static final long APP_SWITCH_LOCK_MS = 2000;
    private static final int MAX_LOCK_RETRIES = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Long> fallbackTimes = new ArrayDeque<>();
    private HomeOverlay overlay;
    private int lockRetries;
    private long lastLaunch;
    private boolean tripped;
    private boolean homeHandled, menuDown, menuHandled;
    /** Keys whose DOWN we swallowed (their UP is swallowed too) / whose DOWN reached the app (their UP must too). */
    private final HashSet<Integer> swallowedDown = new HashSet<>(), passedDown = new HashSet<>();
    /**
     * Fire OS shows Settings as a section of Amazon's HomeActivity (android.settings.SETTINGS ->
     * CTSDummySettingsHandler -> HomeActivity_vNext with extras; the quick panel's Settings button does the same).
     * While this is true the fallback leaves Amazon's home alone. Ends on a window from any other app, or HOME.
     */
    private boolean inAmazonSettings;
    private String lastPkg = "", lastCls = "";
    private long lastPkgAt;

    /** A debounced Amazon-home event is re-checked once the debounce ends instead of being dropped. */
    private final Runnable recheck = () -> {
        if (AMAZON_HOME_PKG.equals(lastPkg) && lastCls.contains("HomeActivity") && !inAmazonSettings && !tripped) {
            Log.i(App.TAG, "recheck: Amazon home still in front");
            fallback(lastCls, SystemClock.elapsedRealtime());
        }
    };
    private final Runnable homeLong = () -> { homeHandled = true; openSettings("long-press HOME"); };
    private final Runnable menuLong = () -> { if (menuDown) { menuHandled = true; openSettings("long-press MENU"); } };

    @Override
    protected void onServiceConnected() {
        log("HomeKeyService connected");
        App.homeService = this;
        overlay = new HomeOverlay(this);
        // Connected after boot, a reinstall or a crash: Amazon's home may already be in front with no event coming.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isInteractive()) handler.postDelayed(() -> goHome("service connected"), 1500);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (overlay != null) overlay.remove("service unbinding");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.shutdown();
        if (App.homeService == this) App.homeService = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- keys

    /** Keys that never click anything on screen: always delivered, even under the overlay. */
    private static boolean alwaysPasses(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE: case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_HOME:   // never blocked by the overlay (Fire OS does not deliver it here anyway)
                return true;
            default:
                return false;
        }
    }

    /**
     * Overlay input guard. Returns TRUE to swallow, FALSE to let the normal handling below decide.
     * Invariants: nothing is swallowed unless the overlay is up and younger than its timeout; a release is swallowed
     * only if its press was (so an app never sees a press without its release, which would leave it auto-repeating).
     */
    private boolean overlayGuard(KeyEvent e) {
        int code = e.getKeyCode(), action = e.getAction();
        if (alwaysPasses(code)) return false;
        boolean guarding = overlay != null && overlay.swallowsKeys();
        if (!guarding) {
            swallowedDown.clear();   // overlay gone or timed out: nothing stays swallowed
            if (action == KeyEvent.ACTION_DOWN) passedDown.add(code); else if (action == KeyEvent.ACTION_UP) passedDown.remove(code);
            return false;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            if (passedDown.contains(code)) return false;          // press started before the overlay: keep it whole
            if (swallowedDown.add(code)) Log.i(App.TAG, "overlay swallowed " + KeyEvent.keyCodeToString(code));
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (swallowedDown.remove(code)) return true;
            passedDown.remove(code);
        }
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (overlayGuard(e)) return true;
        int code = e.getKeyCode(), action = e.getAction();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP && inAmazonSettings && AMAZON_HOME_PKG.equals(lastPkg)) {
                handler.removeCallbacks(backOutOfSettings);
                handler.postDelayed(backOutOfSettings, 350);   // let Amazon handle Back first, then take over
            }
            return false;   // Back always reaches the app
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            // Long press opens Amazon Settings. MENU itself is never consumed: swallowing the release of a press the
            // app already received would leave InputDispatcher auto-repeating MENU into whatever is in front.
            if (action == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0 && !menuDown) {
                    menuDown = true; menuHandled = false;
                    handler.postDelayed(menuLong, LONG_PRESS_MS);
                }
                return false;
            }
            handler.removeCallbacks(menuLong);
            menuDown = false;
            menuHandled = false;
            return false;
        }
        if (code != KeyEvent.KEYCODE_HOME) return false;
        // Only on firmware that delivers HOME to the InputFilter (not Fire OS 7.7.1.7).
        if (action == KeyEvent.ACTION_DOWN) {
            if (e.getRepeatCount() == 0) {
                homeHandled = false;
                if (menuDown) {                     // MENU held + HOME
                    handler.removeCallbacks(menuLong);
                    menuHandled = true; homeHandled = true;
                    openSettings("MENU+HOME");
                } else {
                    handler.postDelayed(homeLong, LONG_PRESS_MS);
                }
            }
            return true;
        }
        handler.removeCallbacks(homeLong);
        if (!homeHandled && !e.isCanceled()) {
            inAmazonSettings = false;
            tripped = false;            // a real HOME press re-arms the fallback
            fallbackTimes.clear();
            goHome("key");
        }
        homeHandled = false;
        return true;
    }

    /**
     * Back while Amazon's HomeActivity is showing its Settings section leaves Settings (sub-pages are separate
     * com.amazon.tv.settings.v2 activities, so Back there does not reach this). End the session and go home.
     */
    private final Runnable backOutOfSettings = () -> {
        if (inAmazonSettings && AMAZON_HOME_PKG.equals(lastPkg) && lastCls.contains("HomeActivity")) {
            inAmazonSettings = false;
            goHome("back out of Settings");
        }
    };

    // ---------------------------------------------------------------- windows

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = e.getPackageName(), clsCs = e.getClassName();
        if (pkgCs == null || clsCs == null) return;
        String pkg = pkgCs.toString(), cls = clsCs.toString();
        long now = SystemClock.elapsedRealtime();
        String prevPkg = lastPkg;
        long prevAt = lastPkgAt;
        lastPkg = pkg; lastCls = cls; lastPkgAt = now;
        boolean ours = getPackageName().equals(pkg);
        if (ours) lockRetries = 0;
        boolean amazonHome = AMAZON_HOME_PKG.equals(pkg) && cls.contains("HomeActivity");

        // Anything else taking the screen (quick panel, Settings pages, an app, a system dialog) must be visible.
        if (!ours && !amazonHome && overlay != null && overlay.isUp()) overlay.remove("other window: " + pkg + "/" + cls);

        if (!AMAZON_HOME_PKG.equals(pkg)) {
            // Any real app (or us) ends a settings session. Amazon settings screens and system dialogs do not.
            if (!AMAZON_SETTINGS_PKG.equals(pkg) && !"android".equals(pkg) && !pkg.startsWith("com.android.systemui")) {
                if (inAmazonSettings) Log.i(App.TAG, "settings session ended by " + pkg);
                inAmazonSettings = false;
            }
            return;
        }
        // Only Amazon's home activity. Everything else in that package (e.g. ui.SettingsActivity) is left alone.
        if (!amazonHome) return;

        boolean fromSettings = now - App.settingsOpenedAt < SETTINGS_GRACE_MS
                || (AMAZON_SETTINGS_PKG.equals(prevPkg) && now - prevAt < SETTINGS_GRACE_MS);
        if (fromSettings && !inAmazonSettings) {
            inAmazonSettings = true;
            Log.i(App.TAG, "settings session started (Amazon home opened for Settings, prev=" + prevPkg + ")");
        }
        if (inAmazonSettings) {
            if (overlay != null) overlay.remove("settings session");
            return;
        }
        if (tripped) { Log.i(App.TAG, "fallback ignored (kill switch tripped): " + cls); return; }
        showOverlay("Amazon home in front");
        if (now - lastLaunch < DEBOUNCE_MS) {
            Log.i(App.TAG, "fallback debounced: " + cls);
            handler.removeCallbacks(recheck);
            handler.postDelayed(recheck, DEBOUNCE_MS - (now - lastLaunch) + 50);
            return;
        }
        fallback(cls, now);
    }

    private void fallback(String cls, long now) {
        while (!fallbackTimes.isEmpty() && now - fallbackTimes.peekFirst() > TRIP_WINDOW_MS) fallbackTimes.pollFirst();
        if (fallbackTimes.size() >= TRIP_COUNT) {
            tripped = true;
            if (overlay != null) overlay.remove("kill switch");
            log("KILL SWITCH: " + TRIP_COUNT + "+ fallback relaunches in " + TRIP_WINDOW_MS / 1000
                    + " s, fallback stopped until next HOME press");
            return;
        }
        fallbackTimes.addLast(now);
        showOverlay("fallback");
        goHome("fallback " + cls);
    }

    /** Home was detected (Amazon's home in front, not as Settings): cover it with our still. Idempotent. */
    private void showOverlay(String why) {
        if (overlay == null || overlay.isUp() || App.homeFocused || App.dreaming || inAmazonSettings || tripped) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;
        overlay.show(why);
    }

    /** MainActivity got window focus. */
    void onHomeLanded() {
        if (overlay != null) overlay.onHomeLanded();
    }

    /** PhotoDream started: the screensaver must not sit under our overlay. */
    void onDreamStarted() {
        if (overlay != null) overlay.remove("screensaver started");
    }

    @Override
    public void onInterrupt() { }

    // ---------------------------------------------------------------- covered / retries

    /**
     * True when Amazon's home is (very likely) covering us: none of our activities is resumed, and the last window
     * we saw was Amazon's home or our own (Amazon can come back over us via onNewIntent without a window event).
     * A real app or Settings opening produces its own window event, so it never matches.
     */
    private boolean amazonCoveringUs() {
        if (App.ownResumed > 0 || App.dreaming || inAmazonSettings || tripped) return false;
        boolean lastAmazonHome = AMAZON_HOME_PKG.equals(lastPkg) && lastCls.contains("HomeActivity");
        if (lastAmazonHome) return true;
        // Our window was last. Right after we opened something, the new app may simply not have drawn yet.
        boolean weJustLaunched = SystemClock.elapsedRealtime() - App.ownLaunchAt < 8000;
        return getPackageName().equals(lastPkg) && !weJustLaunched;
    }

    private final Runnable checkCovered = () -> {
        if (amazonCoveringUs()) {
            showOverlay("home covered");
            goHome("home covered (no app opened)");
        }
    };

    /** Called by our activities from onPause. If nothing else takes the screen within 800 ms, Amazon did. */
    void onOwnActivityPaused() {
        handler.removeCallbacks(checkCovered);
        handler.postDelayed(checkCovered, 800);
    }

    private final Runnable verifyAfterLock = () -> {
        Log.i(App.TAG, "verify: ownResumed=" + App.ownResumed + " last=" + lastPkg + "/" + lastCls + " settings=" + inAmazonSettings);
        if (!amazonCoveringUs()) { lockRetries = 0; return; }
        if (lockRetries >= MAX_LOCK_RETRIES) {
            log("gave up after " + MAX_LOCK_RETRIES + " retries: Amazon home still in front");
            lockRetries = 0;
            return;
        }
        lockRetries++;
        goHome("retry after app-switch lock (" + lockRetries + ")");
    };

    /** Test switch for the forced-failure test. Ignored if older than 10 min, so a leftover can never lock us out. */
    private boolean testBlocksHome() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return false;
        File f = new File(dir, "test_block_home");
        return f.exists() && System.currentTimeMillis() - f.lastModified() < 10 * 60 * 1000L;
    }

    private void goHome(String why) {
        lastLaunch = SystemClock.elapsedRealtime();
        if (testBlocksHome()) { Log.i(App.TAG, "TEST: home start blocked (" + why + ")"); return; }
        Log.i(App.TAG, "goHome via " + why);
        handler.removeCallbacks(verifyAfterLock);
        handler.postDelayed(verifyAfterLock, APP_SWITCH_LOCK_MS);
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ex) {
            log("goHome failed: " + ex);
        }
    }

    private void openSettings(String why) {
        Log.i(App.TAG, "settings via " + why);
        MainActivity.openSettings(this);
    }

    private void log(String msg) {
        Log.i(App.TAG, msg);
        ((App) getApplication()).record(msg);
    }
}
