package com.arda.vehicle.manager;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.util.Slog;

import com.arda.vehicle.admin.VehicleSourceGrantRequest;
import com.arda.vehicle.admin.VehicleSourceInfo;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ManagerRuntime {
    private static final String TAG = "ArdaVehicleManager";
    private static final Object sLock = new Object();
    private static ManagerRuntime sInstance;

    private final GrantStore mGrantStore;
    private final ExecutorService mExecutor;
    private final AdapterRegistry mRegistry;
    private final VehicleInfoController mVehicleInfoController;

    private ManagerRuntime(Context context) {
        Context applicationContext = context.getApplicationContext();
        mGrantStore = new GrantStore(applicationContext);
        mExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ArdaVehicleManager");
            thread.setDaemon(true);
            return thread;
        });
        mRegistry = new AdapterRegistry(applicationContext, mGrantStore);
        mVehicleInfoController = new VehicleInfoController(applicationContext, mRegistry);
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

    void applyVehicleInfo(int userId, int[] fuelTypes, int[] connectorTypes) {
        callSync(() -> {
            mVehicleInfoController.apply(userId, fuelTypes, connectorTypes);
            return null;
        }, 90L);
    }

    void replayVehicleInfoCurrentUser() {
        int userId = currentUserId();
        mExecutor.execute(() -> {
            try {
                mVehicleInfoController.replayStored(userId);
            } catch (RuntimeException error) {
                Slog.e(TAG, "ARDA_VM_VEHICLE_INFO_REPLAY_FAIL user=" + userId, error);
            }
        });
    }

    void rescanCurrentUser() {
        int userId = currentUserId();
        mExecutor.execute(() -> {
            Slog.i(TAG, "ARDA_VM_RESCAN user=" + userId);
            mRegistry.rescan(userId);
        });
    }

    String describeCurrentUser() {
        return callSync(() -> mRegistry.describe(currentUserId()));
    }

    VehicleSourceInfo[] listSources(int userId) {
        return callSync(() -> mRegistry.listSources(userId));
    }

    void rescanUser(int userId) {
        callSync(() -> {
            mRegistry.rescan(userId);
            return null;
        });
    }

    String grant(
            ComponentName component,
            int priority,
            String publishCapabilityCsv,
            String commandCapabilityCsv,
            String publishPropertyCsv,
            String commandPropertyCsv) {
        int userId = currentUserId();
        return callSync(() -> grantInternal(
                userId,
                component,
                priority,
                publishCapabilityCsv,
                commandCapabilityCsv,
                publishPropertyCsv,
                commandPropertyCsv));
    }

    void approve(VehicleSourceGrantRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("grant request is null");
        }
        ComponentName component = ComponentName.unflattenFromString(request.componentName);
        if (component == null) {
            throw new IllegalArgumentException("invalid component=" + request.componentName);
        }
        callSync(() -> {
            AdapterCandidate candidate = requireCandidate(request.userId, component);
            AdapterGrant grant = mGrantStore.grant(
                    candidate,
                    request.priority,
                    request.publishCapabilities,
                    request.commandCapabilities,
                    request.publishPropertyIds,
                    request.commandPropertyIds);
            Slog.i(TAG, "ARDA_VM_SOURCE_APPROVED " + grant.summary());
            mRegistry.rescan(request.userId);
            return null;
        });
    }

    String revoke(ComponentName component) {
        return revoke(currentUserId(), component);
    }

    String revoke(int userId, ComponentName component) {
        return callSync(() -> {
            boolean removed = mGrantStore.revoke(userId, component);
            mRegistry.rescan(userId);
            return "ARDA_VM_REVOKE component=" + component.flattenToShortString()
                    + " user=" + userId
                    + " removed=" + removed;
        });
    }

    void setEnabled(int userId, ComponentName component, boolean enabled) {
        callSync(() -> {
            AdapterGrant grant = mGrantStore.setEnabled(userId, component, enabled);
            Slog.i(TAG, enabled ? "ARDA_VM_SOURCE_ENABLED " + grant.summary()
                    : "ARDA_VM_SOURCE_DISABLED " + grant.summary());
            mRegistry.rescan(userId);
            return null;
        });
    }

    private String grantInternal(
            int userId,
            ComponentName component,
            int priority,
            String publishCapabilityCsv,
            String commandCapabilityCsv,
            String publishPropertyCsv,
            String commandPropertyCsv) {
        AdapterCandidate candidate = requireCandidate(userId, component);
        int[] publishCapabilities = publishCapabilityCsv == null
                ? candidate.publishCapabilities
                : CapabilityParser.parse(publishCapabilityCsv, false);
        int[] commandCapabilities = commandCapabilityCsv == null
                ? candidate.commandCapabilities
                : CapabilityParser.parse(commandCapabilityCsv, true);
        int[] publishProperties = publishPropertyCsv == null
                ? candidate.requestedPublishPropertyIds
                : PropertyIdParser.parse(publishPropertyCsv, "publish grant");
        int[] commandProperties = commandPropertyCsv == null
                ? candidate.requestedCommandPropertyIds
                : PropertyIdParser.parse(commandPropertyCsv, "command grant");
        AdapterGrant grant = mGrantStore.grant(
                candidate,
                priority,
                publishCapabilities,
                commandCapabilities,
                publishProperties,
                commandProperties);
        Slog.i(TAG, "ARDA_VM_SOURCE_APPROVED " + grant.summary());
        mRegistry.rescan(userId);
        return "ARDA_VM_GRANT_OK " + grant.summary();
    }

    private AdapterCandidate requireCandidate(int userId, ComponentName component) {
        AdapterCandidate candidate = mRegistry.findCandidate(userId, component);
        if (candidate == null) {
            throw new IllegalArgumentException(
                    "adapter component not discovered for user " + userId + ": " + component);
        }
        return candidate;
    }

    private <T> T callSync(Callable<T> task) {
        return callSync(task, 10L);
    }

    private <T> T callSync(Callable<T> task, long timeoutSeconds) {
        Future<T> future = mExecutor.submit(task);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("vehicle manager operation interrupted", e);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("vehicle manager operation timed out", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("vehicle manager operation failed", cause);
        }
    }

}
