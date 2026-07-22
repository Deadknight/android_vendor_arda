package com.arda.inputbridge;

import android.app.Application;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ArdaInputApplication extends Application {
    private static final String TAG = "ArdaInputBridge";
    private static final String SOCKET_NAME = "arda_input_bridge";

    @Override
    public void onCreate() {
        super.onCreate();

        if (UserHandle.myUserId() != UserHandle.USER_SYSTEM) {
            Log.i(TAG, "server disabled for non-system user=" + UserHandle.myUserId());
            return;
        }

        Thread serverThread = new Thread(
                ArdaInputApplication::runServer, "arda-input-bridge");
        serverThread.setDaemon(false);
        serverThread.start();
    }

    private static void runServer() {
        final InputBridge bridge;
        try {
            bridge = new InputBridge();
        } catch (Throwable t) {
            Log.e(TAG, "failed to initialize input bridge", t);
            return;
        }

        while (true) {
            try (BoundServer boundServer = BoundServer.open()) {
                Log.i(TAG, "READY transport=unix-abstract name="
                        + SOCKET_NAME
                        + " allowed_uid=" + Process.ROOT_UID
                        + " uid=" + Process.myUid());

                while (true) {
                    try (LocalSocket socket = boundServer.accept()) {
                        Credentials peer = socket.getPeerCredentials();
                        int peerUid = peer.getUid();
                        int peerPid = peer.getPid();

                        if (peerUid != Process.ROOT_UID) {
                            Log.w(TAG, "rejected peer uid=" + peerUid + " pid=" + peerPid);
                            continue;
                        }

                        Log.i(TAG, "client accepted uid=" + peerUid + " pid=" + peerPid);
                        bridge.handleClient(socket);
                    } catch (Throwable t) {
                        bridge.cancelActiveTouch();
                        Log.e(TAG, "client failed", t);
                    }
                }
            } catch (Throwable t) {
                bridge.cancelActiveTouch();
                Log.e(TAG, "server failed; retrying name=" + SOCKET_NAME, t);
                SystemClock.sleep(1000);
            }
        }
    }

    private static final class BoundServer implements AutoCloseable {
        private final LocalSocket ownerSocket;
        private final LocalServerSocket serverSocket;

        private BoundServer(
                LocalSocket ownerSocket,
                LocalServerSocket serverSocket) {
            this.ownerSocket = ownerSocket;
            this.serverSocket = serverSocket;
        }

        static BoundServer open() throws Exception {
            LocalSocket ownerSocket = new LocalSocket(LocalSocket.SOCKET_STREAM);
            boolean success = false;
            try {
                LocalSocketAddress address = new LocalSocketAddress(
                        SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT);
                ownerSocket.bind(address);

                LocalServerSocket serverSocket =
                        new LocalServerSocket(ownerSocket.getFileDescriptor());
                success = true;
                return new BoundServer(ownerSocket, serverSocket);
            } finally {
                if (!success) {
                    try {
                        ownerSocket.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        LocalSocket accept() throws IOException {
            return serverSocket.accept();
        }

        @Override
        public void close() {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {
            }
            try {
                ownerSocket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static final class InputBridge {
        private static final float ACTIVE_PRESSURE = 0.8f;
        private static final float POINTER_SIZE = 0.05f;

        private final InputManagerGlobal inputManager = InputManagerGlobal.getInstance();

        private boolean touchActive;
        private long touchDownTime;
        private int lastPointerId;
        private float lastX;
        private float lastY;
        private int displayId;

        void handleClient(LocalSocket socket) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if ("STOP".equals(line) || "BYE".equals(line)) {
                        cancelActiveTouch();
                        return;
                    }
                    if ("PING".equals(line)) {
                        continue;
                    }

                    String[] p = line.split("\\s+");
                    try {
                        if (p.length >= 2 && "DISPLAY".equals(p[0])) {
                            displayId = Integer.parseInt(p[1]);
                            Log.i(TAG, "display=" + displayId);
                        } else if (p.length >= 5 && "T".equals(p[0])) {
                            injectTouch(
                                    p[1],
                                    Integer.parseInt(p[2]),
                                    Integer.parseInt(p[3]),
                                    Integer.parseInt(p[4]));
                        } else if (p.length >= 2 && "K".equals(p[0])) {
                            injectKeyPress(Integer.parseInt(p[1]));
                        } else {
                            Log.w(TAG, "ignored line=" + line);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "line failed: " + line, t);
                    }
                }
            } finally {
                cancelActiveTouch();
                Log.i(TAG, "client disconnected");
            }
        }

        private void injectTouch(String actionWord, int x, int y, int pointerId) {
            String action = actionWord.toUpperCase(Locale.US);
            long now = SystemClock.uptimeMillis();
            int motionAction;
            float pressure;

            if ("DOWN".equals(action) || "D".equals(action)) {
                cancelActiveTouch();
                touchActive = true;
                touchDownTime = now;
                lastPointerId = pointerId;
                motionAction = MotionEvent.ACTION_DOWN;
                pressure = ACTIVE_PRESSURE;
            } else if ("MOVE".equals(action) || "M".equals(action)) {
                if (!touchActive) {
                    Log.w(TAG, "MOVE without active DOWN x=" + x + " y=" + y);
                    return;
                }
                motionAction = MotionEvent.ACTION_MOVE;
                pressure = ACTIVE_PRESSURE;
            } else if ("UP".equals(action) || "U".equals(action)) {
                if (!touchActive) {
                    return;
                }
                motionAction = MotionEvent.ACTION_UP;
                pressure = 0.0f;
            } else {
                Log.w(TAG, "unknown touch action=" + actionWord);
                return;
            }

            lastX = x;
            lastY = y;
            lastPointerId = pointerId;

            boolean ok = injectMotion(
                    motionAction, touchDownTime, now, pointerId, x, y, pressure);
            if (!ok) {
                Log.e(TAG, "touch injection returned false action=" + action
                        + " x=" + x + " y=" + y + " pointer=" + pointerId);
            }

            if (motionAction == MotionEvent.ACTION_UP) {
                touchActive = false;
            }
        }

        private boolean injectMotion(
                int motionAction,
                long downTime,
                long eventTime,
                int pointerId,
                float x,
                float y,
                float pressure) {
            MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
            property.id = pointerId;
            property.toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords coord = new MotionEvent.PointerCoords();
            coord.x = x;
            coord.y = y;
            coord.pressure = pressure;
            coord.size = POINTER_SIZE;

            MotionEvent event = MotionEvent.obtain(
                    downTime,
                    eventTime,
                    motionAction,
                    1,
                    new MotionEvent.PointerProperties[] { property },
                    new MotionEvent.PointerCoords[] { coord },
                    0,
                    0,
                    1.0f,
                    1.0f,
                    findInputDeviceId(InputDevice.SOURCE_TOUCHSCREEN),
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    displayId,
                    0);

            try {
                int mode = motionAction == MotionEvent.ACTION_MOVE
                        ? InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
                        : InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH;
                return inject(event, mode);
            } finally {
                event.recycle();
            }
        }

        void cancelActiveTouch() {
            if (!touchActive) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            injectMotion(
                    MotionEvent.ACTION_CANCEL,
                    touchDownTime,
                    now,
                    lastPointerId,
                    lastX,
                    lastY,
                    0.0f);
            touchActive = false;
        }

        private void injectKeyPress(int keyCode) {
            long now = SystemClock.uptimeMillis();
            int deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD;

            KeyEvent down = new KeyEvent(
                    now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                    deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD);
            down.setDisplayId(displayId);

            KeyEvent up = new KeyEvent(
                    now, now, KeyEvent.ACTION_UP, keyCode, 0, 0,
                    deviceId, 0, 0, InputDevice.SOURCE_KEYBOARD);
            up.setDisplayId(displayId);

            boolean okDown = inject(down, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            boolean okUp = inject(up, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH);
            if (!(okDown && okUp)) {
                Log.e(TAG, "key injection returned false keycode=" + keyCode);
            } else {
                Log.i(TAG, "key injected keycode=" + keyCode);
            }
        }

        private boolean inject(InputEvent event, int mode) {
            try {
                return inputManager.injectInputEvent(event, mode);
            } catch (Throwable t) {
                Log.e(TAG, "injectInputEvent failed event=" + event, t);
                return false;
            }
        }

        private static int findInputDeviceId(int source) {
            int[] ids = InputDevice.getDeviceIds();
            for (int id : ids) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && device.supportsSource(source)) {
                    return id;
                }
            }
            return -1;
        }
    }
}
