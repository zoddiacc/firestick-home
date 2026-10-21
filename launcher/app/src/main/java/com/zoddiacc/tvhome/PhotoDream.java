package com.zoddiacc.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.io.File;

/**
 * Photo screensaver: the home photo full screen with a very slow Ken Burns drift, and a thin clock
 * bottom-left that also shifts a little every minute so nothing burns in. Photo decoded once at 1280x720 RGB_565.
 */
public class PhotoDream extends DreamService {
    private static final int W = 1280, H = 720;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap photo;
    private ObjectAnimator drift;
    private View clockBox;
    private int clockStep;
    private final Runnable moveClock = new Runnable() {
        @Override public void run() {
            float d = getResources().getDisplayMetrics().density;
            int[][] offsets = {{0, 0}, {10, -6}, {20, 0}, {10, 6}};   // dp, a slow loop
            int[] o = offsets[clockStep++ % offsets.length];
            clockBox.animate().translationX(o[0] * d).translationY(o[1] * d).setDuration(4000).start();
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_photo);
        clockBox = findViewById(R.id.clock_box);
        ImageView iv = findViewById(R.id.photo);
        photo = decode(new File(getFilesDir(), "hero.jpg"));
        if (photo != null) iv.setImageBitmap(photo);
        else iv.setImageResource(R.drawable.default_backdrop);   // no photo pushed yet: neutral dark gradient
        iv.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // the drift is then just a GPU transform
        drift = ObjectAnimator.ofPropertyValuesHolder(iv,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.06f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -24f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -10f));
        drift.setDuration(40_000);
        drift.setRepeatMode(ValueAnimator.REVERSE);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        Log.i(App.TAG, "PhotoDream started");
        App.dreaming = true;
        HomeKeyService svc = App.homeService;
        if (svc != null) svc.onDreamStarted();
        if (drift != null) drift.start();
        main.postDelayed(moveClock, 60_000);
    }

    @Override
    public void onDreamingStopped() {
        App.dreaming = false;
        if (drift != null) drift.cancel();
        main.removeCallbacks(moveClock);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (photo != null) { photo.recycle(); photo = null; }
        super.onDetachedFromWindow();
    }

    /**
     * 1440x1080 (4:3) -> 1280x960 via density scaling, then crop to 1280x720 keeping more of the top
     * (faces usually sit high in the frame). RGB_565: 1.8 MB.
     */
    static Bitmap decode(File f) {
        if (!f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        float s = Math.max((float) W / o.outWidth, (float) H / o.outHeight);
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        d.inScaled = true;
        d.inDensity = o.outWidth;
        d.inTargetDensity = Math.round(o.outWidth * s);
        Bitmap full = BitmapFactory.decodeFile(f.getPath(), d);
        if (full == null) return null;
        int x = Math.max(0, (full.getWidth() - W) / 2);
        int extra = Math.max(0, full.getHeight() - H), y = extra / 4;   // keep 3/4 of the overflow below, 1/4 above
        Bitmap out = Bitmap.createBitmap(full, x, y, Math.min(W, full.getWidth()), Math.min(H, full.getHeight()));
        if (out != full) full.recycle();
        Log.i(App.TAG, "dream photo " + out.getWidth() + "x" + out.getHeight() + " " + out.getByteCount() / 1024 + " KB");
        return out;
    }
}
