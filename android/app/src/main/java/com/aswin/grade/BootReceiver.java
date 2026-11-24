package com.aswin.grade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts step tracking and reminders after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Notifs.channels(c);
            StepService.start(c);
            ReminderReceiver.schedule(c);
        }
    }
}
