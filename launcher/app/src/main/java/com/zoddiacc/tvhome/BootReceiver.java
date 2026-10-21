package com.zoddiacc.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Brings the home screen up after boot. Fire OS delays third-party boot broadcasts, so HomeKeyService's fallback usually wins first. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Log.i(App.TAG, "boot: " + intent.getAction());
        ((App) ctx.getApplicationContext()).record("boot " + intent.getAction());
        ctx.startActivity(new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
