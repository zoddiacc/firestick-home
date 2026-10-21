package com.zoddiacc.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Decodes cached banner PNGs (RGB_565, tile size, ~85 KB each) only when a tile binds, with a small LRU.
 * The All apps grid never holds more than what is on screen plus a few rows.
 */
final class BannerLoader {
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Rounded in the drawable itself (no clipToOutline), so a software draw of the screen looks identical. */
    static void set(ImageView view, Bitmap b) {
        androidx.core.graphics.drawable.RoundedBitmapDrawable d =
                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(view.getResources(), b);
        d.setCornerRadius(10 * view.getResources().getDisplayMetrics().density);
        d.setAntiAlias(true);
        view.setImageDrawable(d);
    }

    static void load(ImageView view, AppInfo app) {
        view.setTag(app.pkg);
        Bitmap hit = CACHE.get(app.pkg);
        if (hit != null) { set(view, hit); return; }
        view.setImageDrawable(null);
        IO.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(app.bannerFile.getPath(), o);
            if (b == null) return;
            CACHE.put(app.pkg, b);
            MAIN.post(() -> { if (app.pkg.equals(view.getTag())) set(view, b); });
        });
    }
}
