package com.zoddiacc.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Covers Amazon's home with a still of ours while Android's 5 s app-switch lock holds our real home back.
 *
 *  - TYPE_ACCESSIBILITY_OVERLAY window: an accessibility service may add it without any extra permission, and
 *    window adds are not subject to the app-switch lock.
 *  - Software-rendered (no FLAG_HARDWARE_ACCELERATED) into a 16-bit RGB_565 surface: no GPU texture, ~4 MB per
 *    surface buffer. The snapshot bitmap (~4 MB) exists only while the overlay is up.
 *  - Not focusable, not touchable: our real home can take focus underneath. HomeKeyService swallows keys while
 *    swallowsKeys() is true, which is never longer than TIMEOUT_MS after the overlay appeared.
 *  - The time + AM/PM are drawn live (same layout as the home screen) over a patch, so it never looks frozen.
 *
 * Main thread only.
 */
final class HomeOverlay {
    static final long TIMEOUT_MS = 7000;
    private static final long POLL_MS = 200, LANDED_DELAY_MS = 60;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private View root;
    private ImageView image;
    private Bitmap bitmap;
    private long shownAt;
    private boolean loading, cancelLoad;

    private final Runnable timeout = () -> remove("TIMEOUT: home did not land within " + TIMEOUT_MS + " ms");
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (App.homeFocused) remove("home landed (poll)");
            else main.postDelayed(this, POLL_MS);
        }
    };

    HomeOverlay(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isUp() { return root != null; }

    /** Keys may be swallowed only while the overlay is up, and never longer than TIMEOUT_MS after it appeared. */
    boolean swallowsKeys() { return root != null && SystemClock.elapsedRealtime() - shownAt < TIMEOUT_MS; }

    /** Load the snapshot off the main thread, then add the window. No-op if already up or loading. */
    void show(String why) {
        if (root != null) return;
        if (loading) { cancelLoad = false; return; }   // a load is already running: want its result again
        loading = true;
        cancelLoad = false;
        final long t0 = SystemClock.elapsedRealtime();
        io.execute(() -> {
            Snapshot.Loaded s = Snapshot.read(svc);
            main.post(() -> {
                loading = false;
                if (s == null) { Log.i(App.TAG, "overlay skipped: no snapshot yet"); return; }
                if (cancelLoad || App.homeFocused) {
                    s.bitmap.recycle();
                    Log.i(App.TAG, "overlay not needed any more (" + why + ")");
                    return;
                }
                attach(s, why, t0);
            });
        });
    }

    private void attach(Snapshot.Loaded s, String why, long t0) {
        Snapshot.Meta m = s.meta;
        FrameLayout r = new FrameLayout(svc);
        ImageView iv = new ImageView(svc);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        iv.setImageBitmap(s.bitmap);
        r.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (m.patchR > m.patchL && m.patchB > m.patchT && m.pillH > 0) {
            View patch = new View(svc);
            patch.setBackgroundColor(m.patchColor | 0xFF000000);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(m.patchR - m.patchL, m.patchB - m.patchT);
            pl.leftMargin = m.patchL;
            pl.topMargin = m.patchT;
            r.addView(patch, pl);
            LinearLayout clock = new LinearLayout(svc);
            clock.setOrientation(LinearLayout.HORIZONTAL);
            clock.setGravity(Gravity.CENTER_VERTICAL);
            LayoutInflater.from(svc).inflate(R.layout.clock_time_ampm, clock, true);
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, m.pillH);
            cl.leftMargin = m.clockX;
            cl.topMargin = m.pillY;
            r.addView(clock, cl);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.RGB_565);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.windowAnimations = 0;
        lp.setTitle("MyHomeOverlay");
        try {
            wm.addView(r, lp);
        } catch (RuntimeException e) {
            Log.i(App.TAG, "overlay addView failed: " + e);
            iv.setImageDrawable(null);
            s.bitmap.recycle();
            return;
        }
        root = r;
        image = iv;
        bitmap = s.bitmap;
        shownAt = SystemClock.elapsedRealtime();
        Log.i(App.TAG, "overlay shown (" + why + "), " + (shownAt - t0) + " ms after trigger");
        main.postDelayed(timeout, TIMEOUT_MS);
        main.postDelayed(poll, POLL_MS);
    }

    /** Our real home has window focus: give it one frame on screen, then take the still away. */
    void onHomeLanded() {
        if (root == null && !loading) return;
        if (loading) cancelLoad = true;
        main.postDelayed(() -> { if (App.homeFocused) remove("home landed"); }, LANDED_DELAY_MS);
    }

    /** Service going away: drop the overlay and stop the loader thread. */
    void shutdown() {
        remove("shutdown");
        io.shutdownNow();
    }

    /** Remove the window and free the bitmap. Safe to call any time. */
    void remove(String why) {
        main.removeCallbacks(timeout);
        main.removeCallbacks(poll);
        if (loading) cancelLoad = true;
        if (root == null) return;
        long upMs = SystemClock.elapsedRealtime() - shownAt;
        try {
            wm.removeViewImmediate(root);
        } catch (RuntimeException e) {
            Log.i(App.TAG, "overlay removeView failed: " + e);
        }
        if (image != null) image.setImageDrawable(null);
        if (bitmap != null) bitmap.recycle();
        root = null;
        image = null;
        bitmap = null;
        String msg = "overlay removed after " + upMs + " ms: " + why;
        Log.i(App.TAG, msg);
        if (why.startsWith("TIMEOUT")) ((App) svc.getApplication()).record(msg);
    }
}
