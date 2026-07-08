package com.arda.vehicle.manager;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ManagerRuntime {
    private static final String TAG = "ArdaVehicleManager";
    private static final Object sLock = new Object();
    private static ManagerRuntime sInstance;

    private final GrantStore mGrantStore;
    private final ExecutorService mExecutor;
    private final AdapterRegistry mRegistry;

    private ManagerRuntime(Context context) {
        Context applicationContext = context.getApplicationContext();
        mGrantStore = new GrantStore(applicationContext);
        mExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ArdaVehicleManager");
            thread.setDaemon(true);
            return thread;
        });
        mRegistry = new AdapterRegistry(applicationContext, mGrantStore);
    }

    static ManagerRuntime get(Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ManagerRuntime(context);
            }
            return sInstance;
        }
    }

    int currentUserId() {
        return ActivityManager.getCurrentUser();
    }

    void rescanCurrentUser() {
        int userId = currentUserId();
        mExecutor.execute(() -> {
            Slog.i(TAG, "ARDA_VM_RESCAN user=" + userId);
            mRegistry.rescan(userId);
        });
    }

    String describeCurrentUser() {
        return mRegistry.describe(currentUserId());
    }

    String grant(ComponentName component, int priority, String publishCsv, String commandCsv) {
        int userId = currentUserId();
        AdapterCandidate candidate = mRegistry.findCandidate(userId, component);
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "adapter component not discovered for current user: " + component);
        }
        int[] publish = publishCsv == null
                ? candidate.publishCapabilities
                : CapabilityParser.parse(publishCsv, false);
        int[] commands = commandCsv == null
                ? candidate.commandCapabilities
                : CapabilityParser.parse(commandCsv, true);
        AdapterGrant grant = mGrantStore.grant(candidate, priority, publish, commands);
        rescanCurrentUser();
        return "ARDA_VM_GRANT_OK " + grant.summary();
    }

    String revoke(ComponentName component) {
        int userId = currentUserId();
        boolean removed = mGrantStore.revoke(userId, component);
        rescanCurrentUser();
        return "ARDA_VM_REVOKE component=" + component.flattenToShortString()
                + " user=" + userId
                + " removed=" + removed;
    }
}
