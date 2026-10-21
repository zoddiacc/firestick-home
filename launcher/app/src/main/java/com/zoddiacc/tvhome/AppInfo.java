package com.zoddiacc.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A tile: an installed app (icon + colour from its own resources), or one of the built-in tiles. */
final class AppInfo {
    static final String ALL_APPS = "#all-apps", SETTINGS = "#settings";
    private static final int ICON_PX = 112;      // 40 dp card icon at 320 dpi = 80 px; 112 keeps it sharp. ~49 KB each.

    static final int TILE_W = 272, TILE_H = 160;   // 136 x 80 dp at 320 dpi

    final String label, pkg;
    final Bitmap icon;        // only for apps without a banner (and null for built-in tiles)
    final File bannerFile;    // RGB_565 PNG at tile size, decoded lazily by BannerLoader; null = no banner
    final int color;

    AppInfo(String label, String pkg, Bitmap icon, int color) { this(label, pkg, icon, color, null); }

    AppInfo(String label, String pkg, Bitmap icon, int color, File bannerFile) {
        this.label = label; this.pkg = pkg; this.icon = icon; this.color = color; this.bannerFile = bannerFile;
    }

    boolean hasBanner() { return bannerFile != null; }

    boolean isBuiltIn() { return pkg.startsWith("#"); }

    /**
     * Every launchable, non-hidden app, sorted by label. Icons and colours come from
     * cacheDir/apps/ when the package's lastUpdateTime matches, otherwise they are rebuilt.
     */
    static List<AppInfo> loadAll(Context ctx, Config cfg) {
        long t0 = System.currentTimeMillis();
        PackageManager pm = ctx.getPackageManager();
        LinkedHashMap<String, ResolveInfo> seen = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String p = ri.activityInfo.packageName;
                if (p.equals(ctx.getPackageName()) || cfg.isHidden(p)) continue;
                if (!seen.containsKey(p)) seen.put(p, ri);
            }
        }
        File dir = new File(ctx.getCacheDir(), "apps-v6");   // v5: tile banners, flat/gradient/odd-aspect handling
        dir.mkdirs();
        Map<String, String[]> index = readIndex(new File(dir, "index.tsv"));
        List<AppInfo> out = new ArrayList<>();
        int hits = 0;
        List<String> noBanner = new ArrayList<>();
        StringBuilder newIndex = new StringBuilder();
        for (ResolveInfo ri : seen.values()) {
            ActivityInfo ai = ri.activityInfo;
            long updated;
            try { updated = pm.getPackageInfo(ai.packageName, 0).lastUpdateTime; } catch (Exception e) { continue; }
            String[] row = index.get(ai.packageName);
            File png = new File(dir, ai.packageName + ".png"), bannerPng = new File(dir, ai.packageName + ".banner.png");
            AppInfo app = null;
            if (row != null && row[1].equals(String.valueOf(updated))) {
                boolean hasBanner = "1".equals(row[3]);
                if (hasBanner && bannerPng.exists()) {
                    app = new AppInfo(row[4], ai.packageName, null, Integer.parseInt(row[2]), bannerPng);   // no icon decode
                    hits++;
                } else if (!hasBanner && png.exists()) {
                    Bitmap b = BitmapFactory.decodeFile(png.getPath());
                    if (b != null) { app = new AppInfo(row[4], ai.packageName, b, Integer.parseInt(row[2])); hits++; }
                }
            }
            if (app == null) {
                app = build(pm, ai, png, bannerPng);
            }
            if (!app.hasBanner()) noBanner.add(app.label);
            newIndex.append(app.pkg).append('\t').append(updated).append('\t').append(app.color).append('\t')
                    .append(app.hasBanner() ? "1" : "0").append('\t')
                    .append(app.label.replace('\t', ' ').replace('\n', ' ')).append('\n');
            out.add(app);
        }
        try (FileWriter w = new FileWriter(new File(dir, "index.tsv"))) { w.write(newIndex.toString()); } catch (Exception ignored) { }
        out.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        Log.i(App.TAG, "apps=" + out.size() + " cacheHits=" + hits + " in " + (System.currentTimeMillis() - t0) + " ms");
        Log.i(App.TAG, "no banner (icon-card fallback): " + noBanner);
        return out;
    }

    /** Favourites in config order (installed ones only), then the All apps and Settings tiles. */
    static List<AppInfo> favourites(List<AppInfo> all, Config cfg) {
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (AppInfo a : all) byPkg.put(a.pkg, a);
        List<AppInfo> out = new ArrayList<>();
        for (String p : cfg.favourites) { AppInfo a = byPkg.get(p); if (a != null) out.add(a); }
        out.add(new AppInfo("All apps", ALL_APPS, null, 0xFF3A4150));
        out.add(new AppInfo("Settings", SETTINGS, null, 0xFF5A606B));
        return out;
    }

    private static AppInfo build(PackageManager pm, ActivityInfo ai, File iconPng, File bannerPng) {
        String label = ai.loadLabel(pm).toString();
        // The app's own TV banner: application banner first, then the leanback activity's banner.
        Drawable banner = null;
        try { banner = pm.getApplicationBanner(ai.applicationInfo); } catch (Exception ignored) { }
        if (banner == null) {
            try { banner = pm.getActivityBanner(new android.content.ComponentName(ai.packageName, ai.name)); } catch (Exception ignored) { }
        }
        Bitmap tile = banner != null ? renderCover(banner, TILE_W, TILE_H) : null;
        if (tile != null && isFlat(tile)) {   // a plain colour/gradient "banner" says nothing: use the icon card
            Log.i(App.TAG, "flat banner, using icon card: " + ai.packageName);
            tile.recycle(); tile = null; banner = null;
        }
        if (banner != null) {
            int color = ambient(tile);
            try (FileOutputStream os = new FileOutputStream(bannerPng)) { tile.compress(Bitmap.CompressFormat.PNG, 100, os); } catch (Exception ignored) { }
            tile.recycle();
            return new AppInfo(label, ai.packageName, null, color, bannerPng);
        }
        // No banner: icon card (highest-density icon, trimmed and plated).
        Drawable icon = null;
        try {
            Resources r = pm.getResourcesForApplication(ai.applicationInfo);
            int res = ai.getIconResource();
            if (res != 0) icon = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception ignored) { }
        if (icon == null) icon = ai.loadIcon(pm);
        Bitmap raw = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, 192, 192);
        icon.draw(new Canvas(raw));
        Bitmap ib = IconFit.fit(raw, ICON_PX);
        raw.recycle();
        try (FileOutputStream os = new FileOutputStream(iconPng)) { ib.compress(Bitmap.CompressFormat.PNG, 100, os); } catch (Exception ignored) { }
        return new AppInfo(label, ai.packageName, ib, ambient(ib));
    }

    /**
     * Scale to fill w x h and centre-crop the overflow. Banners far from 16:9 (crop would cut their text) are fitted
     * instead, on their own edge colour. RGB_565.
     */
    private static Bitmap renderCover(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float ratio = iw > 0 && ih > 0 ? (float) iw / ih : 16f / 9f;
        boolean fit = ratio < 1.4f || ratio > 2.2f;
        c.drawColor(fit ? edgeColor(d) : 0xFF000000);
        if (iw <= 0 || ih <= 0) { d.setBounds(0, 0, w, h); }
        else {
            float s = fit ? Math.min((float) w / iw, (float) h / ih) : Math.max((float) w / iw, (float) h / ih);
            int dw = Math.round(iw * s), dh = Math.round(ih * s), l = (w - dw) / 2, t = (h - dh) / 2;
            d.setBounds(l, t, l + dw, t + dh);
        }
        d.draw(c);
        return b;
    }

    /** The rendered tile has no logo or text: tiny luminance spread on a 32x18 sample of exactly what is shown. */
    private static boolean isFlat(Bitmap tile) {
        Bitmap s = Bitmap.createScaledBitmap(tile, 32, 18, true);
        double sum = 0, sq = 0, lo = 255, hi = 0;
        for (int y = 0; y < 18; y++) for (int x = 0; x < 32; x++) {
            int px = s.getPixel(x, y);
            double l = 0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px);
            sum += l; sq += l * l; lo = Math.min(lo, l); hi = Math.max(hi, l);
        }
        s.recycle();
        double mean = sum / 576, sd = Math.sqrt(Math.max(0, sq / 576 - mean * mean));
        // flat colour or a plain gradient (e.g. FireTV Troubleshooting): no logo or text to show
        return sd < 6 || (sd < 14 && hi - lo < 50);
    }

    /** Average of the four corners of the drawable, used to letterbox a fitted banner. */
    private static int edgeColor(Drawable d) {
        Bitmap s = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(s);
        c.drawColor(0xFF000000);
        d.setBounds(0, 0, 16, 16);
        d.draw(c);
        int[] pts = {s.getPixel(0, 0), s.getPixel(15, 0), s.getPixel(0, 15), s.getPixel(15, 15)};
        s.recycle();
        int r = 0, g = 0, b = 0;
        for (int p : pts) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); }
        return Color.rgb(r / 4, g / 4, b / 4);
    }

    private static Map<String, String[]> readIndex(File f) {
        Map<String, String[]> m = new HashMap<>();
        if (!f.exists()) return m;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 5);
                if (p.length == 5) m.put(p[0], p);
            }
        } catch (Exception ignored) { }
        return m;
    }

    /** Saturation-weighted average, pushed into a range that works as a card gradient and a glow. */
    static int ambient(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 16, 9, true);
        float[] hsv = new float[3];
        double r = 0, g = 0, bl = 0, w = 0;
        for (int y = 0; y < 9; y++) for (int x = 0; x < 16; x++) {
            int px = s.getPixel(x, y);
            if (Color.alpha(px) < 128) continue;
            Color.colorToHSV(px, hsv);
            double wt = hsv[1] * hsv[2] * hsv[2] + 0.02;
            r += Color.red(px) * wt; g += Color.green(px) * wt; bl += Color.blue(px) * wt; w += wt;
        }
        if (s != b) s.recycle();
        if (w == 0) return 0xFF3A4150;
        Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (bl / w)), hsv);
        if (hsv[1] > 0.12f) hsv[1] = Math.max(hsv[1], 0.78f);
        hsv[2] = Math.min(Math.max(hsv[2], 0.5f), 0.82f);
        return Color.HSVToColor(hsv);
    }
}
