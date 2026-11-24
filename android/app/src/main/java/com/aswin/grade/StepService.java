package com.aswin.grade;

import android.Manifest;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

/** Always-on step counting using the phone's low-power hardware step counter. */
public class StepService extends Service implements SensorEventListener {
    private SensorManager sm;
    private long shown = -1;

    static boolean canRun(Context c) {
        if (Build.VERSION.SDK_INT >= 29
                && c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        SensorManager s = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        return s != null && s.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
    }

    static void start(Context c) {
        if (!canRun(c)) return;
        try {
            c.startForegroundService(new Intent(c, StepService.class));
        } catch (Exception ignored) {
            // Android may refuse to start it from the background; the next app open or boot restarts it.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifs.channels(this);
        if (!goForeground()) return;
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor counter = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if (counter == null) {
            stopSelf();
            return;
        }
        // Batch readings for up to 60 s to save battery; the chip keeps counting meanwhile.
        sm.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, 60_000_000);
    }

    /** Must run after every startForegroundService() call, including repeat starts. */
    private boolean goForeground() {
        long steps = Math.max(0, StepStore.today(this));
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifs.ID_TRACK, Notifs.tracking(this, steps, goal()), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH);
            } else {
                startForeground(Notifs.ID_TRACK, Notifs.tracking(this, steps, goal()));
            }
            return true;
        } catch (Exception e) {
            stopSelf();
            return false;
        }
    }

    private long goal() {
        return getSharedPreferences("app", MODE_PRIVATE).getLong("stepGoal", 8000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_STEP_COUNTER) return;
        long today = StepStore.onCounter(this, event.values[0]);
        if (today != shown) {
            shown = today;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                try {
                    nm.notify(Notifs.ID_TRACK, Notifs.tracking(this, today, goal()));
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        if (sm != null) sm.unregisterListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
