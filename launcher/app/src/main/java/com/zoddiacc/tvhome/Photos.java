package com.zoddiacc.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The home background photo. tools/push-photo.sh drops hero.jpg into the app's external files dir
 * (/sdcard/Android/data/com.zoddiacc.tvhome/files/, writable by adb). On resume we move it into internal
 * storage (files/hero.jpg, app-private) and delete the external copy.
 */
final class Photos {
    private static final String NAME = "hero.jpg";

    /** @return true if a new photo was imported. */
    static boolean importPending(Context ctx) {
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) return false;
        File in = new File(ext, NAME);
        if (!in.exists()) return false;
        File out = new File(ctx.getFilesDir(), NAME), tmp = new File(ctx.getFilesDir(), NAME + ".tmp");
        try (InputStream is = new FileInputStream(in); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            Log.i(App.TAG, "photo import failed: " + e);
            tmp.delete();
            return false;
        }
        boolean ok = tmp.renameTo(out);
        in.delete();
        Log.i(App.TAG, "photo imported (" + out.length() + " bytes) ok=" + ok);
        return ok;
    }

    /** RGB_565, never larger than 1440x1080: about 3 MB. */
    static Bitmap decodeHero(Context ctx) {
        File f = new File(ctx.getFilesDir(), NAME);
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 1440 && o.outHeight / (sample * 2) >= 1080) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b != null) Log.i(App.TAG, "photo decoded " + b.getWidth() + "x" + b.getHeight() + " " + b.getByteCount() / 1024 + " KB");
        return b;
    }
}
