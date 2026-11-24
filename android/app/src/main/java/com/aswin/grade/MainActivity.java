package com.aswin.grade;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Hosts the Grade web app and gives it native step counting, reminders and backups. */
public class MainActivity extends Activity {
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final int REQ_PERMS = 1, REQ_FILE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingOpen;
    private boolean pageReady;
    private int bottomInsetCss = 0;   // navigation bar height in CSS px, handed to the page
    private volatile String currentUrl = "";   // checked by the bridge so only Grade's own pages can use it

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Notifs.channels(this);
        getWindow().setStatusBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 30) {
            // Same layout on every Android version: the app draws to the screen edges and handles the bars itself.
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
        } else {
            getWindow().setNavigationBarColor(Color.BLACK);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#121212"));
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Android 15 draws apps edge to edge: keep content clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener((View v, WindowInsets wi) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = wi.getInsets(WindowInsets.Type.systemBars());
                Insets ime = wi.getInsets(WindowInsets.Type.ime());
                // Top: keep clear of the status bar. Bottom: no gap; the page's tab bar pads itself
                // by the navigation bar height so it reaches the screen edge. Keyboard: lift content.
                v.setPadding(bars.left, bars.top, bars.right, ime.bottom);
                float density = getResources().getDisplayMetrics().density;
                bottomInsetCss = ime.bottom > 0 ? 0 : Math.round(bars.bottom / density);
                pushInsets();
            }
            return wi;
        });

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);          // built-in assets still load; the rest of the file system stays closed
        ws.setAllowContentAccess(false);
        ws.setGeolocationEnabled(false);
        ws.setSupportMultipleWindows(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "AswinNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (isOwn(url)) return false;
                String scheme = req.getUrl().getScheme();
                // Only ordinary web links leave the app (news, job applications). intent:, file:, javascript: etc. are blocked.
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, req.getUrl());
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        startActivity(i);
                    } catch (ActivityNotFoundException ignored) {
                    }
                }
                return true;
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest req, android.webkit.WebResourceResponse res) {
                // Site not published yet (404) or GitHub having trouble (5xx): show the built-in copy instead.
                if (req.isForMainFrame() && res != null && res.getStatusCode() >= 400
                        && !req.getUrl().toString().startsWith("file:")) {
                    view.loadUrl(ASSET_URL);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url == null ? "" : url;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !req.getUrl().toString().startsWith("file:")) {
                    view.loadUrl(ASSET_URL);   // offline and not cached yet: use the built-in copy
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url == null ? "" : url;
                pageReady = true;
                pushInsets();
                if (!url.startsWith("file:") || BuildConfig.SITE_URL.isEmpty()) return;
                view.evaluateJavascript("window.GRADE_NEWS_URL=" + JSONObject.quote(BuildConfig.SITE_URL + "news.json")
                        + ";window.GRADE_JOBS_URL=" + JSONObject.quote(BuildConfig.SITE_URL + "jobs.json")
                        + ";window.aswinReload&&aswinReload()", null);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                // Build the picker from real file types (application/pdf, ...) so PDFs and Word files are selectable.
                List<String> types = new ArrayList<>();
                for (String t : params.getAcceptTypes()) {
                    if (t != null && t.contains("/")) types.add(t.trim());
                }
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                if (types.size() == 1) {
                    pick.setType(types.get(0));
                } else {
                    pick.setType("*/*");
                    if (!types.isEmpty()) pick.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
                }
                try {
                    startActivityForResult(pick, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        pendingOpen = getIntent().getStringExtra("open");
        String site = BuildConfig.SITE_URL;
        web.loadUrl(site.isEmpty() ? ASSET_URL : site);

        if (permissionsOk()) startBackground();
        ReminderReceiver.schedule(this);
    }

    private void pushInsets() {
        if (web != null) {
            web.evaluateJavascript("document.documentElement.style.setProperty('--sab','" + bottomInsetCss + "px')", null);
        }
    }

    private boolean isOwn(String url) {
        if (url == null) return false;
        String site = BuildConfig.SITE_URL;
        return url.startsWith("file:///android_asset/") || (site.startsWith("https://") && url.startsWith(site));
    }

    private boolean trusted() {
        return isOwn(currentUrl);
    }

    private boolean permissionsOk() {
        boolean act = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        boolean notif = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        return act && notif;
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            startBackground();
            refreshPage();
            return;
        }
        boolean blocked = false;
        for (String p : need) {
            if (getSharedPreferences("app", MODE_PRIVATE).getBoolean("asked_" + p, false)
                    && !shouldShowRequestPermissionRationale(p)) blocked = true;
        }
        if (blocked) {   // the user chose "Don't ask again": send them to the app's settings page
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            return;
        }
        for (String p : need) getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("asked_" + p, true).apply();
        requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    private void startBackground() {
        StepService.start(this);
        ReminderReceiver.schedule(this);
    }

    private void refreshPage() {
        if (web != null) web.evaluateJavascript("window.aswinRefresh&&aswinRefresh()", null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            if (StepService.canRun(this)) StepService.start(this);
            ReminderReceiver.schedule(this);
            refreshPage();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionsOk()) StepService.start(this);
        refreshPage();
        if (pendingOpen != null && pageReady) openSection();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingOpen = intent.getStringExtra("open");
        if (pageReady) openSection();
    }

    private void openSection() {
        if (pendingOpen == null) return;
        String js = "setTimeout(function(){window.aswinOpen&&aswinOpen(" + JSONObject.quote(pendingOpen) + ")},400)";
        pendingOpen = null;
        web.evaluateJavascript(js, null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /** Methods the web app calls as window.AswinNative.* (they run on a background thread). */
    class Bridge {
        @JavascriptInterface
        public boolean hasStepSensor() {
            if (!trusted()) return false;
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            return sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null;
        }

        @JavascriptInterface
        public boolean permissionsOk() {
            if (!trusted()) return false;
            return MainActivity.this.permissionsOk();
        }

        @JavascriptInterface
        public void requestPermissions() {
            if (!trusted()) return;
            runOnUiThread(MainActivity.this::askPermissions);
        }

        @JavascriptInterface
        public int getTodaySteps() {
            if (!trusted()) return -1;
            return (int) StepStore.today(MainActivity.this);
        }

        @JavascriptInterface
        public int getSteps(String day) {
            if (!trusted() || day == null || !day.matches("\\d{4}-\\d{2}-\\d{2}")) return 0;
            return (int) StepStore.forDay(MainActivity.this, day);
        }

        @JavascriptInterface
        public void syncState(String json) {
            if (!trusted() || json == null || json.length() > 20_000) return;
            long goal = 8000;
            try { goal = new JSONObject(json).optLong("stepGoal", 8000); } catch (Exception ignored) {}
            getSharedPreferences("app", MODE_PRIVATE).edit().putString("state", json).putLong("stepGoal", goal).apply();
        }

        @JavascriptInterface
        public String takePending() {
            if (!trusted()) return "{}";
            android.content.SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                o.put("day", p.getString("pendingDay", ""));
                o.put("water", p.getInt("pendingWater", 0));
                o.put("vit", p.getBoolean("pendingVit", false));
                o.put("vitAt", p.getString("pendingVitAt", ""));
            } catch (Exception ignored) {
            }
            p.edit().putInt("pendingWater", 0).putBoolean("pendingVit", false).apply();
            return o.toString();
        }

        @JavascriptInterface
        public boolean isIgnoringBattery() {
            if (!trusted()) return true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void openBatterySettings() {
            if (!trusted()) return;
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void testNotification() {
            if (!trusted()) return;
            Notifs.reminder(MainActivity.this, Notifs.ID_TEST, "Grade",
                    "Reminders are on. This is how they will look.", null, null, null);
        }

        @JavascriptInterface
        public String shareBackup(String json) {
            if (!trusted() || json == null || json.length() > 20_000_000) return "Backup not available";
            String name = "grade-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Grade");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri != null) {
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(json.getBytes(StandardCharsets.UTF_8));
                        }
                        return "Backup saved in Downloads/Grade";
                    }
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, name).putExtra(Intent.EXTRA_TEXT, json);
                startActivity(Intent.createChooser(send, "Save your Grade backup"));
            });
            return "Choose where to save your backup";
        }
    }
}
