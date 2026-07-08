package com.arda.vehicle.manager;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

public final class ArdaVehicleManagerService extends Service {
    private static final String TAG = "ArdaVehicleManager";

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ManagerRuntime.get(context).rescanCurrentUser();
        }
    };

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ManagerRuntime.get(context).rescanCurrentUser();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        registerReceiverAsUser(mPackageReceiver, UserHandle.ALL, packageFilter, null, null);

        IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_SWITCHED);
        userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiverAsUser(mUserReceiver, UserHandle.ALL, userFilter, null, null);

        Slog.i(TAG, "ARDA_VM_START uid=" + android.os.Process.myUid()
                + " user=" + UserHandle.myUserId());
        ManagerRuntime.get(this).rescanCurrentUser();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mPackageReceiver);
        unregisterReceiver(mUserReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
