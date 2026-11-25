package com.aswin.grade;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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

/** Hosts the Grade career web app: jobs, learning, resume upload and backups. */
public class MainActivity extends Activity {
    private static final String ASSET_URL = "file:///android_asset/index.html";
    private static final int REQ_FILE = 2;

    private WebView web;
    private FrameLayout rootView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean pageReady;
    private int bottomInsetCss = 0;   // navigation bar height in CSS px, handed to the page
    private volatile String currentUrl = "";   // checked by the bridge so only Grade's own pages can use it

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) {
            // Same layout on every Android version: the app draws to the screen edges and handles the bars itself.
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyBars(false);
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
                        + ";window.GRADE_BASE=" + JSONObject.quote(BuildConfig.SITE_URL)
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

        String site = BuildConfig.SITE_URL;
        web.loadUrl(site.isEmpty() ? ASSET_URL : site);

    }

    /** Light or dark system bars to match the page theme. */
    private void applyBars(boolean dark) {
        int bg = Color.parseColor(dark ? "#121212" : "#F4F7FB");
        getWindow().setStatusBarColor(bg);
        if (Build.VERSION.SDK_INT < 30) getWindow().setNavigationBarColor(bg);
        if (rootView != null) rootView.setBackgroundColor(bg);
        if (web != null) web.setBackgroundColor(bg);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            if (dark) f &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; else f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            d.setSystemUiVisibility(f);
        }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
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
        public void setTheme(String mode) {
            if (!trusted()) return;
            final boolean dark = "dark".equals(mode);
            runOnUiThread(() -> applyBars(dark));
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
