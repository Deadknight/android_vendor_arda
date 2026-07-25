package com.arda.vehicle.manager;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Narrow vehicle-information administration path.
 *
 * The platform UI may only supply typed fuel/connector enum arrays. The values
 * are forwarded over the already authenticated Manager -> router -> VHAL link;
 * no shell command, debug dumpsys transaction, or general root API is exposed.
 */
final class VehicleInfoController {
    private static final String TAG = "ArdaVehicleManager";
    private static final String PREFS = "arda_vehicle_info_profile";
    private static final String KEY_CONFIGURED = "configured";
    private static final String KEY_FUEL_TYPES = "fuel_types";
    private static final String KEY_CONNECTOR_TYPES = "connector_types";

    private static final String CAR_PROCESS = "com.android.car";
    private static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private static final int ELECTRIC = 10;
    private static final int UNKNOWN_CONNECTOR = 0;
    private static final long PROCESS_TIMEOUT_MS = 30_000L;
    private static final int BOOT_ROUTER_ATTEMPTS = 60;
    private static final long BOOT_ROUTER_RETRY_MS = 500L;
    private static final long REPLAY_DEDUP_WINDOW_MS = 60_000L;

    private static final Set<Integer> ALLOWED_FUEL_TYPES = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    private static final Set<Integer> ALLOWED_CONNECTOR_TYPES = Set.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 101);

    private final Context mContext;
    private final AdapterRegistry mRegistry;
    private final SharedPreferences mPreferences;

    private int mLastAppliedUserId = UserHandle.USER_NULL;
    private String mLastAppliedFuelCsv = "";
    private String mLastAppliedConnectorCsv = "";
    private long mLastAppliedElapsedRealtime;

    VehicleInfoController(Context context, AdapterRegistry registry) {
        mContext = context.getApplicationContext();
        mRegistry = registry;
        Context storageContext = mContext.createDeviceProtectedStorageContext();
        mPreferences = storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void apply(int targetUserId, int[] requestedFuelTypes, int[] requestedConnectorTypes) {
        int[] fuelTypes = validateAndNormalize(
                requestedFuelTypes, ALLOWED_FUEL_TYPES, 1, 4, "fuel type");
        int[] connectorTypes = validateAndNormalize(
                requestedConnectorTypes, ALLOWED_CONNECTOR_TYPES, 1, 16, "connector type");
        boolean electric = contains(fuelTypes, ELECTRIC);
        if (!electric && !(connectorTypes.length == 1
                && connectorTypes[0] == UNKNOWN_CONNECTOR)) {
            throw new IllegalArgumentException(
                    "Non-electric profiles must use connector UNKNOWN");
        }

        applyToRuntime(
                targetUserId,
                fuelTypes,
                connectorTypes,
                false,
                true);
        persist(fuelTypes, connectorTypes);
    }

    void replayStored(int targetUserId) {
        if (targetUserId == UserHandle.USER_SYSTEM) {
            Slog.i(TAG, "ARDA_VM_VEHICLE_INFO_REPLAY_SKIP reason=system_user");
            return;
        }
        if (!mPreferences.getBoolean(KEY_CONFIGURED, false)) {
            Slog.i(TAG, "ARDA_VM_VEHICLE_INFO_REPLAY_SKIP reason=not_configured");
            return;
        }
        int[] fuelTypes = parseCsv(mPreferences.getString(KEY_FUEL_TYPES, null));
        int[] connectorTypes = parseCsv(mPreferences.getString(KEY_CONNECTOR_TYPES, null));
        if (fuelTypes.length == 0 || connectorTypes.length == 0) {
            Slog.e(TAG, "ARDA_VM_VEHICLE_INFO_REPLAY_SKIP reason=invalid_persisted_profile");
            return;
        }
        String fuelCsv = csv(fuelTypes);
        String connectorCsv = csv(connectorTypes);
        long now = SystemClock.elapsedRealtime();
        if (mLastAppliedUserId == targetUserId
                && mLastAppliedFuelCsv.equals(fuelCsv)
                && mLastAppliedConnectorCsv.equals(connectorCsv)
                && now - mLastAppliedElapsedRealtime < REPLAY_DEDUP_WINDOW_MS) {
            Slog.i(TAG, "ARDA_VM_VEHICLE_INFO_REPLAY_SKIP reason=recently_applied user="
                    + targetUserId);
            return;
        }
        applyToRuntime(
                targetUserId,
                fuelTypes,
                connectorTypes,
                true,
                false);
    }

    private void applyToRuntime(
            int targetUserId,
            int[] fuelTypes,
            int[] connectorTypes,
            boolean retryRouter,
            boolean refreshConsumers) {
        RuntimeException lastRouterError = null;
        int attempts = retryRouter ? BOOT_ROUTER_ATTEMPTS : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                mRegistry.applyVehicleInfo(fuelTypes, connectorTypes);
                lastRouterError = null;
                break;
            } catch (RuntimeException error) {
                lastRouterError = error;
                if (attempt == attempts) {
                    break;
                }
                SystemClock.sleep(BOOT_ROUTER_RETRY_MS);
            }
        }
        if (lastRouterError != null) {
            throw new IllegalStateException(
                    "Vehicle router/VHAL is not ready: " + clean(lastRouterError.getMessage()),
                    lastRouterError);
        }

        boolean mapsRestarted = false;
        if (refreshConsumers) {
            restartSystemCarService();
            mapsRestarted = restartMapsBestEffort(targetUserId);
        } else {
            Slog.i(TAG, "ARDA_VM_BOOT_REPLAY_NO_CONSUMER_RESTART user="
                    + targetUserId);
        }
        mLastAppliedUserId = targetUserId;
        mLastAppliedFuelCsv = csv(fuelTypes);
        mLastAppliedConnectorCsv = csv(connectorTypes);
        mLastAppliedElapsedRealtime = SystemClock.elapsedRealtime();
        Slog.i(TAG, "ARDA_VM_VEHICLE_INFO_APPLY_PASS user=" + targetUserId
                + " fuel=" + Arrays.toString(fuelTypes)
                + " connectors=" + Arrays.toString(connectorTypes)
                + " consumers_refreshed=" + refreshConsumers
                + " maps_restarted=" + mapsRestarted);
    }

    private void restartSystemCarService() {
        int oldPid = waitForProcess(CAR_PROCESS, Process.SYSTEM_UID, -1, PROCESS_TIMEOUT_MS);
        if (oldPid <= 0) {
            throw new IllegalStateException("System CarService process was not found");
        }
        try {
            ActivityManager.getService().killApplicationProcess(CAR_PROCESS, Process.SYSTEM_UID);
        } catch (RemoteException error) {
            throw error.rethrowFromSystemServer();
        }
        int newPid = waitForProcess(CAR_PROCESS, Process.SYSTEM_UID, oldPid, PROCESS_TIMEOUT_MS);
        if (newPid <= 0) {
            throw new IllegalStateException("System CarService process did not restart");
        }
        // The process PID can become visible before CarService has rebuilt its static
        // CarInfo cache. Give the service a short deterministic settle window before
        // Maps is relaunched and reads fuel types during startup.
        SystemClock.sleep(2_000L);
        Slog.i(TAG, "ARDA_VM_CAR_SERVICE_RESTART_PASS old_pid=" + oldPid
                + " new_pid=" + newPid);
    }

    private int waitForProcess(String processName, int uid, int excludedPid, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            int pid = findProcess(processName, uid);
            if (pid > 0 && pid != excludedPid) {
                return pid;
            }
            SystemClock.sleep(200L);
        } while (SystemClock.elapsedRealtime() < deadline);
        return -1;
    }

    private int findProcess(String processName, int uid) {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return -1;
        }
        List<ActivityManager.RunningAppProcessInfo> processes =
                activityManager.getRunningAppProcesses();
        if (processes == null) {
            return -1;
        }
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == uid && processName.equals(process.processName)) {
                return process.pid;
            }
        }
        return -1;
    }

    /**
     * Maps refresh is deliberately best-effort.
     *
     * Vehicle-info publication and the CarService static-cache refresh are the
     * authoritative operation. A temporary ActivityTaskManager availability
     * problem must not roll the profile back or prevent it from being persisted.
     * The provider notifies the model after this method returns; if Maps cannot
     * be relaunched here, the next manual/launcher start reads the current BEV
     * profile and model.
     */
    private boolean restartMapsBestEffort(int targetUserId) {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=activity_manager_unavailable"
                    + " user=" + targetUserId);
            return false;
        }

        UserHandle user = UserHandle.of(targetUserId);
        Context userContext = mContext.createContextAsUser(user, 0);
        final int mapsUid;
        try {
            mapsUid = userContext.getPackageManager()
                    .getApplicationInfo(MAPS_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=package_not_found"
                    + " user=" + targetUserId, error);
            return false;
        }

        int oldMainPid = findProcess(MAPS_PACKAGE, mapsUid);
        try {
            ActivityManager.getService().forceStopPackage(MAPS_PACKAGE, targetUserId);
        } catch (RemoteException error) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=force_stop_failed"
                    + " user=" + targetUserId, error);
            return false;
        } catch (RuntimeException error) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=force_stop_runtime"
                    + " user=" + targetUserId, error);
            return false;
        }

        Intent launchIntent = userContext.getPackageManager()
                .getLaunchIntentForPackage(MAPS_PACKAGE);
        if (launchIntent == null) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=launch_activity_missing"
                    + " user=" + targetUserId);
            return false;
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // CarService can expose its new PID before the wider car UI has settled.
        // Retry the activity launch instead of converting a transient ATMS null
        // binder into a failed vehicle-profile operation.
        RuntimeException lastLaunchError = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                mContext.startActivityAsUser(launchIntent, user);
                lastLaunchError = null;
                break;
            } catch (RuntimeException error) {
                lastLaunchError = error;
                Slog.w(TAG, "ARDA_VM_MAPS_START_RETRY attempt=" + attempt
                        + " user=" + targetUserId
                        + " error=" + clean(error.getMessage()));
                SystemClock.sleep(1_000L);
            }
        }
        if (lastLaunchError != null) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=start_failed"
                    + " user=" + targetUserId
                    + " error=" + clean(lastLaunchError.getMessage()));
            return false;
        }

        int newMainPid = waitForProcess(
                MAPS_PACKAGE, mapsUid, oldMainPid, PROCESS_TIMEOUT_MS);
        if (newMainPid <= 0) {
            Slog.w(TAG, "ARDA_VM_MAPS_RESTART_DEFERRED reason=process_not_observed"
                    + " user=" + targetUserId);
            return false;
        }
        Slog.i(TAG, "ARDA_VM_MAPS_RESTART_PASS user=" + targetUserId
                + " old_pid=" + oldMainPid
                + " new_pid=" + newMainPid);
        return true;
    }

    private void persist(int[] fuelTypes, int[] connectorTypes) {
        if (!mPreferences.edit()
                .putBoolean(KEY_CONFIGURED, true)
                .putString(KEY_FUEL_TYPES, csv(fuelTypes))
                .putString(KEY_CONNECTOR_TYPES, csv(connectorTypes))
                .commit()) {
            throw new IllegalStateException("Could not persist vehicle profile");
        }
    }

    private static int[] validateAndNormalize(
            int[] values,
            Set<Integer> allowed,
            int minSize,
            int maxSize,
            String label) {
        if (values == null || values.length < minSize || values.length > maxSize) {
            throw new IllegalArgumentException(
                    label + " list must contain " + minSize + ".." + maxSize + " values");
        }
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (int value : values) {
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException("Unsupported " + label + ": " + value);
            }
            if (!normalized.add(value)) {
                throw new IllegalArgumentException("Duplicate " + label + ": " + value);
            }
        }
        int[] result = new int[normalized.size()];
        int index = 0;
        for (int value : normalized) {
            result[index++] = value;
        }
        return result;
    }

    private static boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private static String csv(int[] values) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(values[index]);
        }
        return output.toString();
    }

    private static int[] parseCsv(String value) {
        if (value == null || value.isBlank()) {
            return new int[0];
        }
        String[] parts = value.split(",");
        ArrayList<Integer> parsed = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                parsed.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException error) {
                return new int[0];
            }
        }
        int[] result = new int[parsed.size()];
        for (int index = 0; index < parsed.size(); index++) {
            result[index] = parsed.get(index);
        }
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? "unknown error" : value;
    }
}
