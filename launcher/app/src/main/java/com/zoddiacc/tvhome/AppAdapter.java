package com.zoddiacc.tvhome;

import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Reference-style cards. Focus = scale + elevation with a shadow in the app's colour; nothing per frame. */
final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
    interface Listener { void onFocus(AppInfo app); void onOpen(AppInfo app); }

    private final List<AppInfo> apps = new ArrayList<>();
    private final Listener listener;

    AppAdapter(Listener l) { listener = l; setHasStableIds(true); }

    void set(List<AppInfo> list) { apps.clear(); apps.addAll(list); notifyDataSetChanged(); }

    @Override public long getItemId(int pos) { return apps.get(pos).pkg.hashCode(); }
    @Override public int getItemCount() { return apps.size(); }

    @NonNull @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        final float d = parent.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 10 * d); }
        });
        // No setClipToOutline: every layer rounds itself (GradientDrawable corners, RoundedBitmapDrawable, IconFit),
        // so a software draw (HomeOverlay snapshot) matches the GPU-rendered screen. The outline still shapes the shadow.
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int pos) {
        AppInfo app = apps.get(pos);
        float d = h.itemView.getResources().getDisplayMetrics().density;
        h.itemView.setContentDescription(app.label);
        if (app.isBuiltIn()) {                     // All apps / Settings: dark tile, centred white icon
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1C1D23);
            bg.setCornerRadius(10 * d);
            bg.setStroke(Math.max(1, (int) d), 0x1FFFFFFF);
            h.itemView.setBackground(bg);
            show(h, false, true, false);
            h.centerIcon.setImageResource(AppInfo.SETTINGS.equals(app.pkg) ? R.drawable.ic_settings : R.drawable.ic_apps);
        } else if (app.hasBanner()) {              // the app's own TV banner fills the tile
            GradientDrawable black = new GradientDrawable();
            black.setColor(0xFF000000);
            black.setCornerRadius(10 * d);
            h.itemView.setBackground(black);
            show(h, true, false, false);
            BannerLoader.load(h.banner, app);
        } else {                                   // fallback: icon on a tinted card, with name
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{shade(app.color, 0.62f), shade(app.color, 0.26f)});
            bg.setCornerRadius(10 * d);
            bg.setStroke(Math.max(1, (int) d), 0x1FFFFFFF);
            h.itemView.setBackground(bg);
            show(h, false, false, true);
            h.icon.setImageBitmap(app.icon);
            h.name.setText(app.label);
        }
        final float lift = 12 * d;
        h.itemView.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f)
                    .translationZ(has ? lift : 0f).setDuration(140).start();
            if (has) listener.onFocus(app);
        });
        h.itemView.setOnClickListener(v -> listener.onOpen(app));
    }

    private static void show(Holder h, boolean banner, boolean center, boolean card) {
        h.banner.setVisibility(banner ? View.VISIBLE : View.GONE);
        if (!banner) { h.banner.setImageDrawable(null); h.banner.setTag(null); }
        h.centerIcon.setVisibility(center ? View.VISIBLE : View.GONE);
        h.icon.setVisibility(card ? View.VISIBLE : View.GONE);
        h.name.setVisibility(card ? View.VISIBLE : View.GONE);
    }

    static int shade(int c, float f) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[2] = Math.min(1f, hsv[2] * f);
        if (f > 1f) hsv[1] = hsv[1] * 0.92f;
        return Color.HSVToColor(hsv);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView icon, banner, centerIcon;
        final TextView name;
        Holder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon); name = v.findViewById(R.id.name);
            banner = v.findViewById(R.id.banner); centerIcon = v.findViewById(R.id.center_icon);
        }
    }
}
