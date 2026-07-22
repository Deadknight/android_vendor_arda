package com.github.deadknight.ardausbaccessorysink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Process;
import android.util.Log;

/**
 * Returns the Raspberry Pi USB gadget to the persistent ADB function after a
 * DHU/AOAP session detaches. The USB cable remains connected and continues
 * powering the board.
 */
public final class UsbAccessoryDetachReceiver extends BroadcastReceiver {
    private static final String TAG = "ArdaUsbRecovery";
    private static final String EXPECTED_MANUFACTURER = "Android";
    private static final String EXPECTED_MODEL =
            "Android Open Automotive Protocol";

    private static final int PER_USER_RANGE = 100000;
    private static final long DETACH_DEBOUNCE_MS = 1200L;
    private static final long FUNCTION_SETTLE_MS = 500L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(
                        intent.getAction())) {
            return;
        }

        UsbAccessory detached = getAccessory(intent);

        if (!isTargetAccessory(detached)) {
            Log.i(TAG, "ARDA_USB_DETACH_RESET=SKIP_NON_AOAP");
            return;
        }

        int currentUserId = Process.myUid() / PER_USER_RANGE;

        // The package must exist for the foreground FULL user to handle attach,
        // but gadget ownership and recovery are intentionally single-user.
        if (currentUserId != 0) {
            Log.i(
                    TAG,
                    "ARDA_USB_DETACH_RESET=SKIP_NON_SYSTEM_USER"
                            + " user=" + currentUserId);
            return;
        }

        PendingResult pendingResult = goAsync();
        Context appContext = context.getApplicationContext();

        Thread worker = new Thread(
                () -> resetAfterDebounce(appContext, pendingResult),
                "ArdaUsbDetachReset");
        worker.start();
    }

    private static void resetAfterDebounce(
            Context context,
            PendingResult pendingResult) {
        try {
            Thread.sleep(DETACH_DEBOUNCE_MS);

            UsbManager usbManager = context.getSystemService(UsbManager.class);

            if (usbManager == null) {
                Log.e(TAG, "ARDA_USB_DETACH_RESET=FAIL_NO_USB_MANAGER");
                return;
            }

            if (containsTargetAccessory(usbManager.getAccessoryList())) {
                Log.i(TAG, "ARDA_USB_DETACH_RESET=SKIP_REATTACHED");
                return;
            }

            Log.i(TAG, "ARDA_USB_DETACH_RESET_REQUEST=YES");

            // The device's persistent USB configuration is adb. Move out of
            // ACCESSORY first, then force a clean gadget data-stack reset.
            usbManager.setCurrentFunctions(UsbManager.FUNCTION_ADB);
            Thread.sleep(FUNCTION_SETTLE_MS);
            usbManager.resetUsbGadget();

            Log.i(
                    TAG,
                    "ARDA_USB_DETACH_RESET=PASS"
                            + " functions=adb");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "ARDA_USB_DETACH_RESET=FAIL_INTERRUPTED", exception);
        } catch (SecurityException exception) {
            Log.e(TAG, "ARDA_USB_DETACH_RESET=FAIL_PERMISSION", exception);
        } catch (RuntimeException exception) {
            Log.e(TAG, "ARDA_USB_DETACH_RESET=FAIL_RUNTIME", exception);
        } finally {
            pendingResult.finish();
        }
    }

    private static boolean containsTargetAccessory(
            UsbAccessory[] accessories) {
        if (accessories == null) {
            return false;
        }

        for (UsbAccessory accessory : accessories) {
            if (isTargetAccessory(accessory)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTargetAccessory(UsbAccessory accessory) {
        return accessory != null
                && EXPECTED_MANUFACTURER.equals(
                        accessory.getManufacturer())
                && EXPECTED_MODEL.equals(accessory.getModel());
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory getAccessory(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY,
                    UsbAccessory.class);
        }

        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }
}
