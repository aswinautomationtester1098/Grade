package com.aswin.grade;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Turns the phone's hardware step counter (total steps since the last reboot)
 * into "steps today", surviving midnight and reboots. The hardware counter is
 * filtered by the phone's motion chip, so shaking the phone does not count.
 */
public final class StepStore {
    private static final String P = "steps";

    private StepStore() {}

    static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** Called with each new hardware counter value. Returns today's steps. */
    static synchronized long onCounter(Context c, float value) {
        SharedPreferences p = prefs(c);
        long counter = (long) value;
        String day = todayKey();
        long last = p.getLong("last", -1);
        long base = p.getLong("base", 0);
        long offset = p.getLong("offset", 0);
        long prevToday = p.getLong("today", 0);
        String baseDay = p.getString("baseDay", day);

        if (last < 0) {                       // first reading ever
            base = counter;
            offset = 0;
            baseDay = day;
        } else if (counter < last) {          // phone rebooted: counter restarted from 0
            offset = day.equals(baseDay) ? prevToday : 0;
            base = 0;
            baseDay = day;
        } else if (!day.equals(baseDay)) {    // new day: steps since the last reading start the new day
            base = last;
            offset = 0;
            baseDay = day;
            prevToday = 0;
        }
        long today = offset + counter - base;
        if (today < 0) {
            base = counter;
            offset = 0;
            today = 0;
        }
        SharedPreferences.Editor e = p.edit()
                .putLong("last", counter).putLong("base", base).putLong("offset", offset)
                .putString("baseDay", baseDay).putLong("today", today)
                .putLong("day_" + day, today);
        if (today != prevToday) e.putLong("lastChange", System.currentTimeMillis());
        e.apply();
        return today;
    }

    /** Steps today, or -1 if the counter has never reported. */
    static long today(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getLong("last", -1) < 0) return -1;
        return todayKey().equals(p.getString("baseDay", "")) ? p.getLong("today", 0) : 0;
    }

    static long forDay(Context c, String key) {
        return prefs(c).getLong("day_" + key, 0);
    }

    static long lastChange(Context c) {
        return prefs(c).getLong("lastChange", 0);
    }
}
