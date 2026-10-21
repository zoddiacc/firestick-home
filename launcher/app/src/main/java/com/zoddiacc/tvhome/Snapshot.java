package com.zoddiacc.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * A still of the home screen for HomeOverlay: files/home_snapshot.565, a 64-byte header followed by raw RGB_565
 * pixels (1920x1080 = ~4 MB). Raw + memory-mapped so the overlay can load it in a few ms; never kept in RAM
 * between overlays. The header also carries where the live clock goes and the colour to patch under it.
 */
final class Snapshot {
    private static final String NAME = "home_snapshot.565";
    private static final int MAGIC = 0x4D594831;   // "MYH1"
    private static final int HEADER = 64;

    static final class Meta {
        int w, h;
        int clockX, pillY, pillH;                      // where the live time + AM/PM row goes
        int patchL, patchT, patchR, patchB, patchColor; // background patch hiding the snapshot's (stale) time
    }

    static final class Loaded {
        final Bitmap bitmap;
        final Meta meta;
        Loaded(Bitmap b, Meta m) { bitmap = b; meta = m; }
    }

    /**
     * Background thread. Writes row by row (one 3.8 KB row buffer, no 4 MB map or heap buffer left for the GC),
     * to a temp file that is then renamed, so a reader never sees a half-written snapshot.
     */
    static void write(Context ctx, Bitmap bmp, Meta m) {
        long t0 = SystemClock.elapsedRealtime();
        File tmp = new File(ctx.getFilesDir(), NAME + ".tmp"), out = new File(ctx.getFilesDir(), NAME);
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] row = new int[w];
        byte[] px = new byte[w * 2];
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
             java.io.BufferedOutputStream os = new java.io.BufferedOutputStream(fos, 64 * 1024)) {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            hdr.putInt(MAGIC).putInt(m.w).putInt(m.h).putInt(m.clockX).putInt(m.pillY).putInt(m.pillH)
                    .putInt(m.patchL).putInt(m.patchT).putInt(m.patchR).putInt(m.patchB).putInt(m.patchColor);
            os.write(hdr.array());
            for (int y = 0; y < h; y++) {
                bmp.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0, i = 0; x < w; x++) {
                    int c = row[x];
                    int v = ((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F);   // ARGB -> RGB_565
                    px[i++] = (byte) v;           // little-endian, the Bitmap's in-memory RGB_565 layout
                    px[i++] = (byte) (v >> 8);
                }
                os.write(px);
            }
        } catch (Exception e) {
            Log.i(App.TAG, "snapshot write failed: " + e);
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(out)) { Log.i(App.TAG, "snapshot rename failed"); tmp.delete(); return; }
        Log.i(App.TAG, "snapshot saved " + w + "x" + h + " RGB_565 in " + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    /** Background thread. Null if there is no valid snapshot yet. */
    static Loaded read(Context ctx) {
        File f = new File(ctx.getFilesDir(), NAME);
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r"); FileChannel ch = raf.getChannel()) {
            ByteBuffer hdr = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
            if (ch.read(hdr, 0) != HEADER) return null;
            hdr.flip();
            if (hdr.getInt() != MAGIC) return null;
            Meta m = new Meta();
            m.w = hdr.getInt(); m.h = hdr.getInt();
            m.clockX = hdr.getInt(); m.pillY = hdr.getInt(); m.pillH = hdr.getInt();
            m.patchL = hdr.getInt(); m.patchT = hdr.getInt(); m.patchR = hdr.getInt(); m.patchB = hdr.getInt();
            m.patchColor = hdr.getInt();
            if (m.w <= 0 || m.h <= 0 || m.w > 4096 || m.h > 4096) return null;
            long size = (long) m.w * m.h * 2;
            if (ch.size() < HEADER + size) return null;
            MappedByteBuffer px = ch.map(FileChannel.MapMode.READ_ONLY, HEADER, size);
            Bitmap b = Bitmap.createBitmap(m.w, m.h, Bitmap.Config.RGB_565);
            b.copyPixelsFromBuffer(px);
            return new Loaded(b, m);
        } catch (Exception | OutOfMemoryError e) {
            Log.i(App.TAG, "snapshot read failed: " + e);
            return null;
        }
    }
}
