package com.aswin.grade;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;

import java.text.NumberFormat;

/** Notification channels and builders. */
final class Notifs {
    static final String CH_TRACK = "tracking";
    static final String CH_REMIND = "reminders";
    static final int ID_TRACK = 1;
    static final int ID_WATER = 10, ID_VIT = 11, ID_MOVE = 12, ID_EXP = 13, ID_TEST = 99;

    private Notifs() {}

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel t = new NotificationChannel(CH_TRACK, "Step tracking", NotificationManager.IMPORTANCE_LOW);
        t.setDescription("Shows today's steps while Grade counts in the background");
        t.setShowBadge(false);
        NotificationChannel r = new NotificationChannel(CH_REMIND, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        r.setDescription("Water, multivitamin, movement and daily money reminders");
        nm.createNotificationChannel(t);
        nm.createNotificationChannel(r);
    }

    static PendingIntent openApp(Context c, String open, int req) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (open != null) i.putExtra("open", open);
        return PendingIntent.getActivity(c, req, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification tracking(Context c, long steps, long goal) {
        String s = NumberFormat.getIntegerInstance().format(Math.max(0, steps));
        int pct = goal > 0 ? (int) Math.min(100, steps * 100 / goal) : 0;
        return new Notification.Builder(c, CH_TRACK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(s + " steps today")
                .setContentText(pct >= 100 ? "Goal reached! Keep it up." : pct + "% of your daily goal")
                .setProgress(100, pct, false)
                .setColor(Color.parseColor("#1ED760"))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(c, null, 1))
                .build();
    }

    static void reminder(Context c, int id, String title, String text, String open, String action, String actionLabel) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification.Builder b = new Notification.Builder(c, CH_REMIND)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(Color.parseColor("#1ED760"))
                .setAutoCancel(true)
                .setContentIntent(openApp(c, open, 100 + id));
        if (action != null) {
            Intent a = new Intent(c, ReminderReceiver.class).setAction(action);
            PendingIntent pa = PendingIntent.getBroadcast(c, 200 + id, a, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, actionLabel, pa).build());
        }
        try {
            nm.notify(id, b.build());
        } catch (SecurityException ignored) {
            // notification permission not granted
        }
    }

    static void cancel(Context c, int id) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
