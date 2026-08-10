package com.arda.projectioninsets;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Gravity;
import android.view.InsetsFrameProvider;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

public final class ProjectionInsetsService extends Service {
    private static final String TAG = "ArdaProjectionInsets";

    public static final String KEY_LEFT = "arda_projection_inset_left";
    public static final String KEY_TOP = "arda_projection_inset_top";
    public static final String KEY_RIGHT = "arda_projection_inset_right";
    public static final String KEY_BOTTOM = "arda_projection_inset_bottom";

    public static final String KEY_STABLE_LEFT = "arda_projection_stable_inset_left";
    public static final String KEY_STABLE_TOP = "arda_projection_stable_inset_top";
    public static final String KEY_STABLE_RIGHT = "arda_projection_stable_inset_right";
    public static final String KEY_STABLE_BOTTOM = "arda_projection_stable_inset_bottom";

    private static final int PROVIDER_LEFT = 0;
    private static final int PROVIDER_TOP = 1;
    private static final int PROVIDER_RIGHT = 2;
    private static final int PROVIDER_BOTTOM = 3;

    private final IBinder mInsetsOwner = new Binder();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private WindowManager mWindowManager;
    private View mAnchorView;
    private WindowManager.LayoutParams mLayoutParams;
    private InsetsFrameProvider[] mProviders;
    private ContentObserver mSettingsObserver;

    private int mLastLeft = Integer.MIN_VALUE;
    private int mLastTop = Integer.MIN_VALUE;
    private int mLastRight = Integer.MIN_VALUE;
    private int mLastBottom = Integer.MIN_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();
        mWindowManager = getSystemService(WindowManager.class);
        if (mWindowManager == null) {
            Slog.e(TAG, "SERVICE_CREATE_FAIL reason=no_window_manager user=" + UserHandle.myUserId());
            stopSelf();
            return;
        }
        final boolean internalSystemWindowGranted =
                checkSelfPermission(android.Manifest.permission.INTERNAL_SYSTEM_WINDOW)
                        == PackageManager.PERMISSION_GRANTED;
        final boolean statusBarServiceGranted =
                checkSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                        == PackageManager.PERMISSION_GRANTED;

        Slog.i(TAG, "PERMISSION_GATE user=" + UserHandle.myUserId()
                + " INTERNAL_SYSTEM_WINDOW="
                + (internalSystemWindowGranted ? "GRANTED" : "DENIED")
                + " STATUS_BAR_SERVICE="
                + (statusBarServiceGranted ? "GRANTED" : "DENIED"));

        if (!internalSystemWindowGranted || !statusBarServiceGranted) {
            Slog.e(TAG, "SERVICE_CREATE_FAIL reason=required_permission_missing user="
                    + UserHandle.myUserId());
            stopSelf();
            return;
        }

        registerSettingsObserver();
        applyFromSettings("service-create");
        Slog.i(TAG, "SERVICE_CREATE_PASS user=" + UserHandle.myUserId()
                + " protocol=settings-global-v1 provider=systemOverlays"
                + " source=display stable_policy=log-only");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        applyFromSettings("service-start");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mSettingsObserver != null) {
            getContentResolver().unregisterContentObserver(mSettingsObserver);
            mSettingsObserver = null;
        }
        removeAnchorWindow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerSettingsObserver() {
        if (mSettingsObserver != null) return;
        mSettingsObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                applyFromSettings("settings-change");
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.CONTENT_URI, true, mSettingsObserver);
    }

    private int readNonNegative(ContentResolver resolver, String key) {
        return Math.max(0, Settings.Global.getInt(resolver, key, 0));
    }

    private void applyFromSettings(String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mMainHandler.post(() -> applyFromSettings(reason + "-main"));
            return;
        }

        final ContentResolver resolver = getContentResolver();
        final int left = readNonNegative(resolver, KEY_LEFT);
        final int top = readNonNegative(resolver, KEY_TOP);
        final int right = readNonNegative(resolver, KEY_RIGHT);
        final int bottom = readNonNegative(resolver, KEY_BOTTOM);

        final int stableLeft = readNonNegative(resolver, KEY_STABLE_LEFT);
        final int stableTop = readNonNegative(resolver, KEY_STABLE_TOP);
        final int stableRight = readNonNegative(resolver, KEY_STABLE_RIGHT);
        final int stableBottom = readNonNegative(resolver, KEY_STABLE_BOTTOM);

        if (mAnchorView == null) {
            try {
                addAnchorWindow(left, top, right, bottom);
            } catch (RuntimeException e) {
                Slog.e(TAG, "INSETS_WINDOW_ADD_FAIL user=" + UserHandle.myUserId()
                        + " reason=" + reason + " values=L" + left + " T" + top
                        + " R" + right + " B" + bottom, e);
                // addView() failed. Do not retain an unattached View/params pair:
                // the next start/settings change must retry addView(), not updateViewLayout().
                mAnchorView = null;
                mLayoutParams = null;
                mProviders = null;
                return;
            }
        } else if (left != mLastLeft || top != mLastTop
                || right != mLastRight || bottom != mLastBottom) {
            updateProviderSizes(left, top, right, bottom);
            try {
                mWindowManager.updateViewLayout(mAnchorView, mLayoutParams);
            } catch (RuntimeException e) {
                Slog.e(TAG, "INSETS_WINDOW_UPDATE_FAIL user=" + UserHandle.myUserId()
                        + " reason=" + reason, e);
                return;
            }
        }

        mLastLeft = left;
        mLastTop = top;
        mLastRight = right;
        mLastBottom = bottom;

        Slog.i(TAG, "INSETS_APPLY user=" + UserHandle.myUserId()
                + " reason=" + reason
                + " content=L" + left + ",T" + top + ",R" + right + ",B" + bottom
                + " stable=L" + stableLeft + ",T" + stableTop
                + ",R" + stableRight + ",B" + stableBottom
                + " stable_policy=log-only");
    }

    private InsetsFrameProvider makeProvider(int index, Insets size) {
        return new InsetsFrameProvider(mInsetsOwner, index, WindowInsets.Type.systemOverlays())
                .setSource(InsetsFrameProvider.SOURCE_DISPLAY)
                .setInsetsSize(size);
    }

    private void addAnchorWindow(int left, int top, int right, int bottom) {
        mProviders = new InsetsFrameProvider[] {
                makeProvider(PROVIDER_LEFT, Insets.of(left, 0, 0, 0)),
                makeProvider(PROVIDER_TOP, Insets.of(0, top, 0, 0)),
                makeProvider(PROVIDER_RIGHT, Insets.of(0, 0, right, 0)),
                makeProvider(PROVIDER_BOTTOM, Insets.of(0, 0, 0, bottom)),
        };

        mLayoutParams = new WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mLayoutParams.gravity = Gravity.TOP | Gravity.LEFT;
        mLayoutParams.x = 0;
        mLayoutParams.y = 0;
        mLayoutParams.setFitInsetsTypes(0);
        mLayoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mLayoutParams.setTitle("ArdaProjectionInsets");
        mLayoutParams.packageName = getPackageName();
        mLayoutParams.providedInsets = mProviders;

        final View anchorView = new View(this);
        anchorView.setBackgroundColor(Color.TRANSPARENT);
        anchorView.setAlpha(1.0f);
        mWindowManager.addView(anchorView, mLayoutParams);
        mAnchorView = anchorView;

        Slog.i(TAG, "INSETS_WINDOW_ADD_PASS user=" + UserHandle.myUserId()
                + " type=TYPE_SYSTEM_OVERLAY provider_type=systemOverlays"
                + " source=SOURCE_DISPLAY");
    }

    private void updateProviderSizes(int left, int top, int right, int bottom) {
        mProviders[PROVIDER_LEFT].setInsetsSize(Insets.of(left, 0, 0, 0));
        mProviders[PROVIDER_TOP].setInsetsSize(Insets.of(0, top, 0, 0));
        mProviders[PROVIDER_RIGHT].setInsetsSize(Insets.of(0, 0, right, 0));
        mProviders[PROVIDER_BOTTOM].setInsetsSize(Insets.of(0, 0, 0, bottom));
    }

    private void removeAnchorWindow() {
        if (mAnchorView == null || mWindowManager == null) return;
        try {
            mWindowManager.removeViewImmediate(mAnchorView);
            Slog.i(TAG, "INSETS_WINDOW_REMOVE_PASS user=" + UserHandle.myUserId());
        } catch (RuntimeException e) {
            Slog.w(TAG, "INSETS_WINDOW_REMOVE_FAIL user=" + UserHandle.myUserId(), e);
        } finally {
            mAnchorView = null;
            mLayoutParams = null;
            mProviders = null;
        }
    }
}
