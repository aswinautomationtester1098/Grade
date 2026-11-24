package com.aswin.grade;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Wakes up about every 15 minutes (inexact, battery friendly) and decides whether
 * a water, multivitamin, movement or daily-money reminder is due. Also handles the
 * "+ Glass" and "Taken" buttons on notifications.
 */
public class ReminderReceiver extends BroadcastReceiver {
    static final String TICK = "com.aswin.grade.TICK";
    static final String ADD_WATER = "com.aswin.grade.ADD_WATER";
    static final String VIT_TAKEN = "com.aswin.grade.VIT_TAKEN";

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ReminderReceiver.class).setAction(TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi);
    }

    static SharedPreferences app(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    private static int mins(String hhmm, int def) {
        try {
            String[] p = hhmm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (ADD_WATER.equals(a)) {
            SharedPreferences p = app(c);
            String day = StepStore.todayKey();
            int pending = day.equals(p.getString("pendingDay", "")) ? p.getInt("pendingWater", 0) : 0;
            boolean vit = day.equals(p.getString("pendingDay", "")) && p.getBoolean("pendingVit", false);
            int glass = 250;
            try { glass = new JSONObject(p.getString("state", "{}")).optInt("glassMl", 250); } catch (Exception ignored) {}
            p.edit().putString("pendingDay", day).putInt("pendingWater", pending + glass).putBoolean("pendingVit", vit)
                    .putLong("lastWaterAdd", System.currentTimeMillis()).apply();
            Notifs.cancel(c, Notifs.ID_WATER);
            return;
        }
        if (VIT_TAKEN.equals(a)) {
            SharedPreferences p = app(c);
            String day = StepStore.todayKey();
            int pending = day.equals(p.getString("pendingDay", "")) ? p.getInt("pendingWater", 0) : 0;
            p.edit().putString("pendingDay", day).putInt("pendingWater", pending).putBoolean("pendingVit", true)
                    .putString("pendingVitAt", new SimpleDateFormat("HH:mm", Locale.US).format(new Date())).apply();
            Notifs.cancel(c, Notifs.ID_VIT);
            return;
        }
        if (TICK.equals(a)) {
            StepService.start(c);   // keep the counter alive if Android stopped it
            check(c);
        }
    }

    static void check(Context c) {
        SharedPreferences p = app(c);
        JSONObject s;
        try { s = new JSONObject(p.getString("state", "{}")); } catch (Exception e) { return; }
        if (s.length() == 0) return;   // app never opened yet

        String day = StepStore.todayKey();
        boolean sameDay = day.equals(s.optString("day"));
        boolean pendingToday = day.equals(p.getString("pendingDay", ""));
        int water = (sameDay ? s.optInt("water", 0) : 0) + (pendingToday ? p.getInt("pendingWater", 0) : 0);
        boolean vit = (sameDay && s.optBoolean("vit", false)) || (pendingToday && p.getBoolean("pendingVit", false));
        boolean expLogged = sameDay && s.optBoolean("expLogged", false);
        int goal = s.optInt("waterGoal", 2500), glass = s.optInt("glassMl", 250);
        long every = Math.max(15, s.optInt("every", 60)) * 60_000L;
        String name = s.optString("name", "Aswin");

        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        int wake = mins(s.optString("wake", "07:00"), 420), bed = mins(s.optString("sleep", "23:00"), 1380);
        int len = ((bed - wake) + 1440) % 1440;
        if (len == 0) len = 960;
        int sinceWake = ((now - wake) + 1440) % 1440;
        boolean awake = sinceWake < len;
        long t = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit();

        // Daily money check-in: once a day after the chosen time, until something is logged.
        int expTime = mins(s.optString("expTime", "21:00"), 1260);
        if (!expLogged && now >= expTime && !day.equals(p.getString("nExpDay", ""))) {
            Notifs.reminder(c, Notifs.ID_EXP, "How did you spend money today?",
                    "Tap to log today's expenses, " + name + ". It takes 20 seconds.", "expask", null, null);
            e.putString("nExpDay", day);
        }

        if (awake && len - sinceWake > 60) {
            double frac = Math.min(1.0, sinceWake / (double) len);
            if (!vit && now >= mins(s.optString("vitTime", "09:00"), 540) && t - p.getLong("nVit", 0) > 2 * every) {
                Notifs.reminder(c, Notifs.ID_VIT, "Time for your multivitamin",
                        "Take it with food. Fat-soluble vitamins absorb better with a meal.", null, VIT_TAKEN, "Taken");
                e.putLong("nVit", t);
            } else if (water < goal * frac - glass / 2.0 && t - p.getLong("nWater", 0) > every
                    && t - Math.max(p.getLong("lastWaterAdd", 0), s.optLong("lastWater", 0)) > every) {
                NumberFormat nf = NumberFormat.getIntegerInstance();
                Notifs.reminder(c, Notifs.ID_WATER, "Drink a glass of water",
                        nf.format(water) + " of " + nf.format(goal) + " ml so far today.", null, ADD_WATER, "+ Glass");
                e.putLong("nWater", t);
            } else if (sinceWake > 60 && StepService.canRun(c)
                    && t - Math.max(StepStore.lastChange(c), s.optLong("lastStretch", 0)) > every
                    && t - p.getLong("nMove", 0) > every) {
                Notifs.reminder(c, Notifs.ID_MOVE, "Time to move",
                        "No steps for a while. Stand up, stretch and walk for a few minutes.", null, null, null);
                e.putLong("nMove", t);
            }
        }
        e.apply();
    }
}
