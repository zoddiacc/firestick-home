package com.zoddiacc.tvhome;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * config.txt in the app's external files dir (/sdcard/Android/data/com.zoddiacc.tvhome/files/, written by
 * tools/push-config.sh), else files/config.txt. Plain key=value, comma-separated lists (see config.example.txt):
 *   home_name=My Home
 *   town=Your Town          latitude=12.34          longitude=56.78
 *   favourites=com.netflix.ninja,com.amazon.firetv.youtube,...
 *   hidden=com.example.foo,...
 * Missing file or key = defaults below. Favourites that are not installed are skipped. No town = no weather
 * (and no network call at all). photo= is read by tools/push-photo.sh on the computer, not here.
 */
final class Config {
    static final List<String> DEFAULT_FAVOURITES = Arrays.asList(
            "com.netflix.ninja",                    // Netflix
            "com.amazon.firetv.youtube",            // YouTube
            "com.amazon.firebat");                  // Prime Video
    static final List<String> DEFAULT_HIDDEN_PREFIXES = Arrays.asList("com.amazon.dummy.", "com.android.");
    /** System entries that are not apps you would open from a home screen. */
    static final List<String> DEFAULT_HIDDEN = Arrays.asList(
            "com.amazon.tv.launcher",          // Amazon's home ("Home"): HomeKeyService would bounce it anyway
            "com.amazon.ssm",                  // System Status Monitor
            "com.amazon.spiderpork",           // What Should I Watch
            "com.amazon.vizzini.ftvcds",       // News (voice stub)
            "com.amazon.ftv.profilepicker",    // Fire TV Profile UI
            "com.amazon.ftv.screensaver");     // Amazon screensaver settings

    final List<String> favourites;
    final Set<String> hidden;
    /** Name shown in big letters on the home screen. */
    String homeName;
    /** Weather location. All three must be set, otherwise weather is off. There is no IP lookup. */
    String weatherCity;
    double weatherLat, weatherLon;

    private Config(List<String> fav, Set<String> hidden) { this.favourites = fav; this.hidden = hidden; }

    static Config load(Context ctx) {
        List<String> fav = new ArrayList<>(DEFAULT_FAVOURITES);
        Set<String> hidden = new HashSet<>(DEFAULT_HIDDEN);
        String name = null, wCity = null; double wLat = Double.NaN, wLon = Double.NaN;
        File ext = ctx.getExternalFilesDir(null);
        File f = ext != null && new File(ext, "config.txt").exists() ? new File(ext, "config.txt") : new File(ctx.getFilesDir(), "config.txt");
        if (f.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    String k = line.substring(0, line.indexOf('=')).trim(), v = line.substring(line.indexOf('=') + 1).trim();
                    List<String> items = new ArrayList<>();
                    for (String s : v.split(",")) if (!s.trim().isEmpty()) items.add(s.trim());
                    if (k.equals("home_name")) name = v.isEmpty() ? null : v;
                    else if (k.equals("town")) wCity = v.isEmpty() ? null : v;
                    else if (k.equals("latitude")) wLat = parse(v);
                    else if (k.equals("longitude")) wLon = parse(v);
                    else if (k.equals("favourites")) fav = items;
                    else if (k.equals("hidden")) hidden.addAll(items);
                }
            } catch (Exception ignored) {
            }
        }
        Config c = new Config(fav, hidden);
        c.homeName = name;
        if (wCity != null && !Double.isNaN(wLat) && !Double.isNaN(wLon)) { c.weatherCity = wCity; c.weatherLat = wLat; c.weatherLon = wLon; }
        return c;
    }

    private static double parse(String v) {
        try { return Double.parseDouble(v); } catch (NumberFormatException e) { return Double.NaN; }
    }

    boolean isHidden(String pkg) {
        if (hidden.contains(pkg)) return true;
        for (String p : DEFAULT_HIDDEN_PREFIXES) if (pkg.startsWith(p)) return true;
        return false;
    }
}
