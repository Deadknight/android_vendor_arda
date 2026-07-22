package com.arda.vehicle.manager;

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.Slog;

import com.arda.vehicle.admin.IArdaVehicleSourceManager;
import com.arda.vehicle.admin.VehicleSourceGrantRequest;
import com.arda.vehicle.admin.VehicleSourceInfo;

public final class ArdaVehicleManagerService extends Service {
    private static final String TAG = "ArdaVehicleManager";
    private static final String PERMISSION_MANAGE_SOURCES =
            "com.arda.vehicle.permission.MANAGE_SOURCES";

    private final IArdaVehicleSourceManager.Stub mAdminBinder =
            new IArdaVehicleSourceManager.Stub() {
        @Override
        public VehicleSourceInfo[] listSources(int userId) {
            enforceAdmin(userId, "listSources");
            return ManagerRuntime.get(ArdaVehicleManagerService.this).listSources(userId);
        }

        @Override
        public void rescan(int userId) {
            enforceAdmin(userId, "rescan");
            ManagerRuntime.get(ArdaVehicleManagerService.this).rescanUser(userId);
        }

        @Override
        public void approveSource(VehicleSourceGrantRequest request) {
            if (request == null) {
                throw new IllegalArgumentException("grant request is null");
            }
            enforceAdmin(request.userId, "approveSource");
            ManagerRuntime.get(ArdaVehicleManagerService.this).approve(request);
        }

        @Override
        public void revokeSource(int userId, String componentName) {
            enforceAdmin(userId, "revokeSource");
            ComponentName component = requireComponent(componentName);
            ManagerRuntime.get(ArdaVehicleManagerService.this).revoke(userId, component);
        }

        @Override
        public void setSourceEnabled(int userId, String componentName, boolean enabled) {
            enforceAdmin(userId, "setSourceEnabled");
            ComponentName component = requireComponent(componentName);
            ManagerRuntime.get(ArdaVehicleManagerService.this)
                    .setEnabled(userId, component, enabled);
        }

        @Override
        public void applyVehicleInfo(int userId, int[] fuelTypes, int[] connectorTypes) {
            enforceAdmin(userId, "applyVehicleInfo");
            ManagerRuntime.get(ArdaVehicleManagerService.this)
                    .applyVehicleInfo(userId, fuelTypes, connectorTypes);
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ManagerRuntime.get(context).rescanCurrentUser();
        }
    };

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ManagerRuntime runtime = ManagerRuntime.get(context);
            runtime.rescanCurrentUser();
            runtime.replayVehicleInfoCurrentUser();
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
        ManagerRuntime runtime = ManagerRuntime.get(this);
        runtime.rescanCurrentUser();
        runtime.replayVehicleInfoCurrentUser();
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mPackageReceiver);
        unregisterReceiver(mUserReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        enforceCallingOrSelfPermission(
                PERMISSION_MANAGE_SOURCES,
                "Vehicle source management permission required");
        return mAdminBinder;
    }

    private void enforceAdmin(int userId, String operation) {
        enforceCallingOrSelfPermission(
                PERMISSION_MANAGE_SOURCES,
                "Vehicle source management permission required");
        int currentUser = ActivityManager.getCurrentUser();
        if (userId != currentUser) {
            throw new SecurityException(
                    "Only the current driver user may be managed: requested="
                            + userId + " current=" + currentUser);
        }
        Slog.i(TAG, "ARDA_VM_ADMIN_CALL operation=" + operation
                + " user=" + userId
                + " caller_uid=" + Binder.getCallingUid());
    }

    private static ComponentName requireComponent(String raw) {
        ComponentName component = raw != null ? ComponentName.unflattenFromString(raw) : null;
        if (component == null) {
            throw new IllegalArgumentException("invalid component=" + raw);
        }
        return component;
    }
}
