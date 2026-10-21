package com.zoddiacc.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Makes every app icon sit the same way in the card's 40 dp slot:
 *  - trims the transparent padding many legacy icons ship with,
 *  - full-bleed icons (opaque corners) fill the slot as a rounded square,
 *  - transparent logos go on a plate: white, or dark when the logo itself is mostly white.
 */
final class IconFit {
    static Bitmap fit(Bitmap src, int size) {
        Rect box = opaqueBounds(src);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        if (box == null) return out;
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float radius = size * 0.22f;
        RectF slot = new RectF(0, 0, size, size);
        Bitmap trimmed = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height());

        if (fullBleed(trimmed)) {
            // Scale to cover the slot, clip to the rounded square.
            Bitmap scaled = Bitmap.createScaledBitmap(trimmed, size, size, true);
            p.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            c.drawRoundRect(slot, radius, radius, p);
        } else {
            Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
            plate.setColor(mostlyWhite(trimmed) ? 0xFF16161B : 0xFFF4F4F6);
            c.drawRoundRect(slot, radius, radius, plate);
            float inner = size * 0.72f;
            float scale = Math.min(inner / trimmed.getWidth(), inner / trimmed.getHeight());
            float w = trimmed.getWidth() * scale, h = trimmed.getHeight() * scale;
            c.drawBitmap(trimmed, null, new RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2), p);
        }
        if (trimmed != src) trimmed.recycle();
        return out;
    }

    private static Rect opaqueBounds(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), l = w, t = h, r = -1, bo = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (Color.alpha(row[x]) > 24) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    bo = y;
                }
            }
        }
        if (r < 0) return null;
        // keep it square around the content so logos are not stretched
        int cw = r - l + 1, ch = bo - t + 1, side = Math.max(cw, ch);
        int cx = l + cw / 2, cy = t + ch / 2;
        int left = Math.max(0, cx - side / 2), top = Math.max(0, cy - side / 2);
        return new Rect(left, top, Math.min(w, left + side), Math.min(h, top + side));
    }

    /** Opaque near all four corners (after trimming) = the icon brings its own background. */
    private static boolean fullBleed(Bitmap b) {
        int w = b.getWidth() - 1, h = b.getHeight() - 1, inset = Math.max(1, b.getWidth() / 10);
        int[][] pts = {{inset, inset}, {w - inset, inset}, {inset, h - inset}, {w - inset, h - inset}};
        for (int[] pt : pts) if (Color.alpha(b.getPixel(pt[0], pt[1])) < 200) return false;
        return true;
    }

    private static boolean mostlyWhite(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
        int opaque = 0, white = 0;
        for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
            int px = s.getPixel(x, y);
            if (Color.alpha(px) < 128) continue;
            opaque++;
            if (Color.red(px) > 215 && Color.green(px) > 215 && Color.blue(px) > 215) white++;
        }
        s.recycle();
        return opaque > 0 && white * 10 > opaque * 6;
    }
}
