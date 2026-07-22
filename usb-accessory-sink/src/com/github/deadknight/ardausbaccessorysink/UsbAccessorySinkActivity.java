package com.github.deadknight.ardausbaccessorysink;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/**
 * Matches the DHU Android Open Accessory identity so Android does not display
 * the "No installed apps work with this USB accessory" dialog.
 *
 * <p>This activity intentionally does not call {@link UsbManager#openAccessory}
 * or request USB permission. The existing native ARDA AAP transport remains
 * the owner of the accessory data path.</p>
 */
public final class UsbAccessorySinkActivity extends Activity {
    private static final String TAG = "ArdaUsbAccessory";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        consumeAttachIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeAttachIntent(intent);
    }

    private void consumeAttachIntent(Intent intent) {
        UsbAccessory accessory = getAccessory(intent);

        if (accessory == null) {
            Log.w(TAG, "ARDA_AOAP_ACCESSORY_ATTACH_CONSUMED accessory=null");
        } else {
            Log.i(
                    TAG,
                    "ARDA_AOAP_ACCESSORY_ATTACH_CONSUMED"
                            + " manufacturer=" + accessory.getManufacturer()
                            + " model=" + accessory.getModel()
                            + " version=" + accessory.getVersion()
                            + " serial=" + accessory.getSerial());
        }

        // Theme.NoDisplay activities must finish before onResume completes.
        finish();
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory getAccessory(Intent intent) {
        if (intent == null) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY,
                    UsbAccessory.class);
        }

        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }
}
