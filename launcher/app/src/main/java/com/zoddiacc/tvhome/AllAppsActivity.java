package com.zoddiacc.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** Every installed, non-hidden app. Back returns to the home screen. */
public class AllAppsActivity extends Activity implements AppAdapter.Listener {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_all_apps);
        List<AppInfo> all = ((App) getApplication()).allApps;
        if (all == null) all = AppInfo.loadAll(this, Config.load(this));
        ((TextView) findViewById(R.id.title)).setText("All apps   " + all.size());
        RecyclerView grid = findViewById(R.id.grid);
        grid.setLayoutManager(new GridLayoutManager(this, 6));
        grid.setItemAnimator(null);
        AppAdapter adapter = new AppAdapter(this);
        grid.setAdapter(adapter);
        adapter.set(all);
        grid.post(() -> {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    @Override protected void onResume() { super.onResume(); App.ownResumed++; }

    @Override protected void onPause() {
        App.ownResumed = Math.max(0, App.ownResumed - 1);
        HomeKeyService svc = App.homeService;
        if (svc != null) svc.onOwnActivityPaused();
        super.onPause();
    }

    @Override public void onFocus(AppInfo app) {
        ((TextView) findViewById(R.id.focus_hint)).setText(app.label + "  ·  OK to open");
    }
    @Override public void onOpen(AppInfo app) { MainActivity.launch(this, app.pkg); }
}
