package com.zoddiacc.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Weather for the clock pill, for the town set in config.txt (town, latitude, longitude). No town = no weather and
 * no network call. IP geolocation is deliberately not used (it is often a city or two off).
 * Open-Meteo (no key), cached 30 min: the launcher's only network call. Failures are silent.
 */
final class Weather {
    private static final long WEATHER_MAX_AGE = 30 * 60 * 1000L;

    static final class Now {
        final float tempC; final int icon; final String city;
        Now(float t, int i, String c) { tempC = t; icon = i; city = c; }
    }

    static void refresh(Context ctx, Executor io, Handler main, Consumer<Now> ui) {
        SharedPreferences p = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE);
        Config cfg = Config.load(ctx);
        if (cfg.weatherCity == null) { ui.accept(null); return; }   // weather off
        final String city = cfg.weatherCity;
        final double lat = cfg.weatherLat, lon = cfg.weatherLon;
        boolean sameCity = city.equals(p.getString("shown_city", null));
        ui.accept(sameCity ? fromPrefs(p) : null);
        if (sameCity && System.currentTimeMillis() - p.getLong("at", 0) < WEATHER_MAX_AGE) return;
        io.execute(() -> {
            try {
                JSONObject cur = get(String.format(Locale.US,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon))
                        .getJSONObject("current");
                p.edit().putLong("at", System.currentTimeMillis()).putString("shown_city", city)
                        .putFloat("temp", (float) cur.getDouble("temperature_2m"))
                        .putInt("code", cur.getInt("weather_code"))
                        .putBoolean("day", cur.optInt("is_day", 1) == 1).apply();
                Log.i(App.TAG, "weather " + city + " " + cur.getDouble("temperature_2m") + "C code=" + cur.getInt("weather_code"));
                Now n = fromPrefs(p);
                main.post(() -> ui.accept(n));
            } catch (Exception e) {
                Log.i(App.TAG, "weather fetch failed (ignored): " + e);
            }
        });
    }

    private static JSONObject get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return new JSONObject(s.hasNext() ? s.next() : "{}");
        } finally {
            c.disconnect();
        }
    }

    private static Now fromPrefs(SharedPreferences p) {
        if (!p.contains("temp")) return null;
        if (System.currentTimeMillis() - p.getLong("at", 0) > 6 * 3600 * 1000L) return null;   // too stale to show
        return new Now(p.getFloat("temp", 0), icon(p.getInt("code", 0), p.getBoolean("day", true)),
                p.getString("shown_city", ""));
    }

    /** WMO weather codes. */
    private static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code <= 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51) return R.drawable.ic_w_rain;
        return R.drawable.ic_w_cloud;
    }
}
