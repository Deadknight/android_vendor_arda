package com.arda.projectioninsets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.util.Slog;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ArdaProjectionInsets";

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : "null";
        Slog.i(TAG, "BOOT_RECEIVER action=" + action + " user=" + UserHandle.myUserId());
        try {
            context.startService(new Intent(context, ProjectionInsetsService.class));
        } catch (RuntimeException e) {
            Slog.e(TAG, "BOOT_RECEIVER_START_FAILED action=" + action
                    + " user=" + UserHandle.myUserId(), e);
        }
    }
}
