package com.zoddiacc.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MainActivity extends Activity implements AppAdapter.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private View ambientA, ambientB, accentLine;
    private boolean frontIsB;
    private ImageView heroPhoto;
    private Bitmap photo;   // RGB_565, ~3 MB, kept for the activity's lifetime
    private TextView heroTitle, heroSub, greeting, rowLabel, focusHint, ramPct;
    private ProgressBar ramBar;
    private RecyclerView row;
    private AppAdapter adapter;
    private AppInfo pending, focused;
    private int shownColor = Color.TRANSPARENT;
    /** config.txt home_name, else the app label. Shown next to the greeting. */
    private String homeName;
    private final Runnable applyHero = () -> { if (pending != null) showHero(pending); };
    /** Still of this screen for HomeOverlay, refreshed 1.5 s after each resume / focus / load. */
    private final Runnable capture = this::captureSnapshot;
    private final Runnable ramTick = new Runnable() {
        @Override public void run() { updateRam(); main.postDelayed(this, 5000); }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        ambientA = findViewById(R.id.ambient_a);
        ambientB = findViewById(R.id.ambient_b);
        accentLine = findViewById(R.id.accent_line);
        heroPhoto = findViewById(R.id.hero_photo);
        heroTitle = findViewById(R.id.hero_title);
        heroSub = findViewById(R.id.hero_sub);
        greeting = findViewById(R.id.greeting);
        rowLabel = findViewById(R.id.row_label);
        focusHint = findViewById(R.id.focus_hint);
        ramPct = findViewById(R.id.ram_pct);
        ramBar = findViewById(R.id.ram_bar);
        row = findViewById(R.id.app_row);

        adapter = new AppAdapter(this);
        row.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        row.setHasFixedSize(true);
        row.setItemAnimator(null);
        row.setAdapter(adapter);
        homeName = getString(R.string.app_name);
        heroTitle.setText(homeName);
        load();
    }

    private void load() {
        io.execute(() -> {
            Config cfg = Config.load(this);
            List<AppInfo> all = AppInfo.loadAll(this, cfg);
            ((App) getApplication()).allApps = all;
            List<AppInfo> fav = AppInfo.favourites(all, cfg);
            main.post(() -> {
                if (cfg.homeName != null) homeName = cfg.homeName;
                greeting.setText(greetingText());
                adapter.set(fav);
                rowLabel.setText("Favourites   " + (fav.size() - 2));
                focusFirst();
                scheduleCapture();
            });
        });
    }

    private void focusFirst() {
        row.scrollToPosition(0);
        row.post(() -> {
            RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((App) getApplication()).setForeground(true);
        ((App) getApplication()).heartbeat();
        App.ownResumed++;
        Log.i(App.TAG, "MainActivity resumed, ownResumed=" + App.ownResumed);
        greeting.setText(greetingText());
        main.post(ramTick);
        loadPhoto();
        Weather.refresh(this, io, main, this::showWeather);
        scheduleCapture();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        App.homeFocused = hasFocus;
        if (hasFocus) {
            HomeKeyService svc = App.homeService;
            if (svc != null) svc.onHomeLanded();
            scheduleCapture();
        }
    }

    private void scheduleCapture() {
        main.removeCallbacks(capture);
        main.postDelayed(capture, 1500);
    }

    /**
     * Draw this window in software into an RGB_565 bitmap and save it for HomeOverlay. Tiles round themselves
     * (no clipToOutline), so the software draw matches the screen; only the focused tile's soft shadow is missing. Also records where the live clock goes and the colour to patch
     * under it. The bitmap is freed as soon as it is written.
     */
    private void captureSnapshot() {
        if (!hasWindowFocus() || isFinishing() || adapter.getItemCount() == 0) return;
        java.io.File ext = getExternalFilesDir(null);
        if (ext != null && new java.io.File(ext, "test_no_snapshot").exists()) { Log.i(App.TAG, "TEST: snapshot capture skipped"); return; }
        View decor = getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        View pill = findViewById(R.id.clock_pill), time = findViewById(R.id.clock_time), date = findViewById(R.id.clock_date);
        int[] p = new int[2], t = new int[2], d = new int[2];
        pill.getLocationInWindow(p);
        time.getLocationInWindow(t);
        date.getLocationInWindow(d);
        float dp = getResources().getDisplayMetrics().density;
        Snapshot.Meta m = new Snapshot.Meta();
        m.w = w; m.h = h;
        m.clockX = t[0]; m.pillY = p[1]; m.pillH = pill.getHeight();
        m.patchL = t[0] - Math.round(2 * dp);
        m.patchR = d[0] - Math.round(3 * dp);
        m.patchT = p[1] + Math.round(4 * dp);
        m.patchB = p[1] + pill.getHeight() - Math.round(4 * dp);
        int sx = Math.max(0, Math.min(w - 1, t[0] - Math.round(8 * dp))), sy = Math.min(h - 1, p[1] + pill.getHeight() / 2);
        final Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        } catch (OutOfMemoryError e) {
            Log.i(App.TAG, "snapshot skipped: " + e);
            return;
        }
        // Software draw of the whole window. PixelCopy (GPU read-back) left ~38 MB of render targets cached in this
        // process for good (measured: PSS 50 -> 90 MB), so it is not used.
        long t0 = android.os.SystemClock.uptimeMillis();
        decor.draw(new Canvas(bmp));
        m.patchColor = bmp.getPixel(sx, sy);
        Log.i(App.TAG, "snapshot drawn in " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
        try {
            io.execute(() -> { Snapshot.write(this, bmp, m); bmp.recycle(); });
        } catch (RejectedExecutionException e) {
            bmp.recycle();
        }
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(ramTick);
        main.removeCallbacks(capture);
        App.homeFocused = false;
        ((App) getApplication()).setForeground(false);
        App.ownResumed = Math.max(0, App.ownResumed - 1);
        Log.i(App.TAG, "MainActivity paused, ownResumed=" + App.ownResumed);
        HomeKeyService svc = App.homeService;
        if (svc != null) svc.onOwnActivityPaused();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        focusFirst();   // Home pressed while already home: back to the start of the row
    }

    /** While the Home service is off we are just an app, so Back returns to Amazon's home. Once Home is ours, Back stays. */
    @Override
    public void onBackPressed() {
        if (!homeServiceEnabled(this)) super.onBackPressed();
    }

    static boolean homeServiceEnabled(Context ctx) {
        String s = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(ctx.getPackageName() + "/");
    }

    private boolean menuPressedHere;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && event.getRepeatCount() == 0) menuPressedHere = true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean shortMenuHere = keyCode == KeyEvent.KEYCODE_MENU && menuPressedHere
                && event.getEventTime() - event.getDownTime() < 600;   // a long press is HomeKeyService's (Settings)
        if (keyCode == KeyEvent.KEYCODE_MENU) menuPressedHere = false;
        if (shortMenuHere && focused != null && !focused.isBuiltIn()) {
            openAppInfo(focused.pkg);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onFocus(AppInfo app) {
        pending = app;
        focused = app;
        focusHint.setText(TextUtils.concat(app.label, "  ·  OK to open"));
        main.removeCallbacks(applyHero);
        main.postDelayed(applyHero, 90);   // debounce fast scrolling: only the card you stop on repaints the hero
    }

    private void showHero(AppInfo app) {
        heroTitle.setText(app.label);
        heroSub.setText(app.isBuiltIn() ? (AppInfo.SETTINGS.equals(app.pkg) ? "Fire TV settings" : "Everything installed") : "Open " + app.label);
        if (app.color == shownColor) return;
        shownColor = app.color;
        accentLine.setBackgroundColor(app.color);
        View back = frontIsB ? ambientA : ambientB, front = frontIsB ? ambientB : ambientA;
        back.setBackground(ambient(app.color));
        back.setAlpha(0f);
        ViewGroup root = findViewById(R.id.root);
        root.removeView(back);
        root.addView(back, root.indexOfChild(front) + 1);   // right above the other tint layer: over photo and fade, under scrims
        back.animate().alpha(1f).setDuration(350).withLayer().withEndAction(() -> front.setAlpha(0f)).start();
        frontIsB = !frontIsB;
    }

    /** App colour at ~14% over the dark left side, fading to nothing before the photo. Built once per focus change. */
    private GradientDrawable ambient(int color) {
        int rgb = color & 0x00FFFFFF;
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{rgb | 0x24000000, rgb | 0x24000000, rgb | 0x14000000, rgb});   // last stop: alpha 0
    }

    /**
     * Decode once (RGB_565) and keep it. Re-decode only when push-photo.sh delivered a new file.
     * No photo: a neutral dark gradient instead (res/drawable/default_backdrop.xml).
     */
    private void loadPhoto() {
        io.execute(() -> {
            boolean changed = Photos.importPending(this);
            if (photo != null && !changed) return;
            Bitmap b = Photos.decodeHero(this);
            main.post(() -> {
                photo = b;
                if (b != null) heroPhoto.setImageBitmap(b);
                else heroPhoto.setImageResource(R.drawable.default_backdrop);
            });
        });
    }

    private void showWeather(Weather.Now w) {
        View box = findViewById(R.id.weather);
        if (w == null) { box.setVisibility(View.GONE); return; }
        ((ImageView) findViewById(R.id.weather_icon)).setImageResource(w.icon);
        ((TextView) findViewById(R.id.weather_temp)).setText(Math.round(w.tempC) + "°");
        ((TextView) findViewById(R.id.weather_city)).setText("·  " + w.city);
        box.setVisibility(View.VISIBLE);
    }

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        int pct = (int) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem);
        ramPct.setText(pct + "%");
        ramBar.setProgress(pct);
    }

    @Override
    public void onOpen(AppInfo app) {
        App.ownLaunchAt = android.os.SystemClock.elapsedRealtime();
        if (AppInfo.ALL_APPS.equals(app.pkg)) { startActivity(new Intent(this, AllAppsActivity.class)); return; }
        if (AppInfo.SETTINGS.equals(app.pkg)) { openSettings(this); return; }
        launch(this, app.pkg);
    }

    static void launch(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) { App.ownLaunchAt = android.os.SystemClock.elapsedRealtime(); ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    }

    static void openSettings(Context ctx) {
        App.settingsOpenedAt = App.ownLaunchAt = android.os.SystemClock.elapsedRealtime();
        ctx.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openAppInfo(String pkg) {
        App.ownLaunchAt = android.os.SystemClock.elapsedRealtime();
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException e) {
            openSettings(this);
        }
    }

    private String greetingText() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 17 ? "Good afternoon" : "Good evening";
        return TextUtils.isEmpty(homeName) ? g : g + "  ·  " + homeName;
    }

    @Override
    protected void onDestroy() {
        App.homeFocused = false;
        main.removeCallbacks(capture);
        io.shutdownNow();
        super.onDestroy();
    }
}
