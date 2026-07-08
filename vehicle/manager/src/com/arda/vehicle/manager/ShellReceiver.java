package com.arda.vehicle.manager;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public final class ShellReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.arda.vehicle.manager.SHELL";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_COMPONENT = "component";
    public static final String EXTRA_PRIORITY = "priority";
    public static final String EXTRA_PUBLISH = "publish";
    public static final String EXTRA_COMMANDS = "commands";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.startService(new Intent(context, ArdaVehicleManagerService.class));
            ManagerRuntime runtime = ManagerRuntime.get(context);
            String command = intent.getStringExtra(EXTRA_COMMAND);
            String result;
            if (command == null || command.equals("status") || command.equals("list")) {
                result = runtime.describeCurrentUser();
            } else if (command.equals("rescan")) {
                runtime.rescanCurrentUser();
                result = "ARDA_VM_RESCAN_QUEUED user=" + runtime.currentUserId();
            } else if (command.equals("grant")) {
                ComponentName component = requireComponent(intent);
                int priority = intent.getIntExtra(
                        EXTRA_PRIORITY,
                        GrantStore.DEFAULT_ADAPTER_PRIORITY);
                result = runtime.grant(
                        component,
                        priority,
                        intent.hasExtra(EXTRA_PUBLISH)
                                ? intent.getStringExtra(EXTRA_PUBLISH)
                                : null,
                        intent.hasExtra(EXTRA_COMMANDS)
                                ? intent.getStringExtra(EXTRA_COMMANDS)
                                : null);
            } else if (command.equals("revoke")) {
                result = runtime.revoke(requireComponent(intent));
            } else {
                throw new IllegalArgumentException("unknown command=" + command);
            }
            setResultCode(0);
            setResultData(result);
        } catch (RuntimeException e) {
            setResultCode(1);
            setResultData("ARDA_VM_SHELL_ERROR " + e.getMessage());
        }
    }

    private static ComponentName requireComponent(Intent intent) {
        String raw = intent.getStringExtra(EXTRA_COMPONENT);
        ComponentName component = raw != null ? ComponentName.unflattenFromString(raw) : null;
        if (component == null) {
            throw new IllegalArgumentException("missing/invalid component extra");
        }
        return component;
    }
}
