package app.rafiq.muslim;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import androidx.core.app.NotificationManagerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

/** One app: WebView hosting the bundled web UI (assets/www) + native adhan bridge (window.AndroidNative). */
public class MainActivity extends Activity {
    void scheduleUpdateCheck() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                UpdateWorker.class,
                6,
                java.util.concurrent.TimeUnit.HOURS
        ).setConstraints(constraints).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "rafiq_muslim_update_check",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
        );
    }

    static final String HOST = "appassets.androidplatform.net";
    static final int RQ_NOTIF = 11, RQ_LOC = 12;
    private WebView web;
    private String pendingNotifId;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;
    // Update state (filled only from our own update.json fetch, never from JS input)
    private volatile String pendingApkUrl = "", pendingSha256 = "";
    private volatile long pendingCode = 0;
    private boolean waitingInstallPerm;
    private final java.util.concurrent.atomic.AtomicBoolean downloading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Scheduling work (JSON parse + alarms) runs off the main thread so the UI never stutters.
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AdhanService.channels(this);
        scheduleUpdateCheck();
        web = new WebView(this);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF0C1411 : 0xFFF3F6F4; // same as the app's --bg, avoids a white flash on launch
        getWindow().getDecorView().setBackgroundColor(bg);
        web.setBackgroundColor(bg);
        setContentView(web);
        applyImmersive();
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return loader.shouldInterceptRequest(r.getUrl()); }
            @Override public void onPageFinished(WebView v, String url) {
    super.onPageFinished(v, url);
    setAppVersion();
    v.postDelayed(() -> checkForUpdate(), 1000);
}
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                    if (HOST.equals(r.getUrl().getHost())) return false;
                    try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) { }
                    return true;
                }
            });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) cb.invoke(origin, true, false);
                else { geoCb = cb; geoOrigin = origin; requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }, RQ_LOC); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidNative");
        web.loadUrl("https://" + HOST + "/assets/www/index.html");
    }

    @Override public void onBackPressed() { if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed(); }

    @Override
    public void onRequestPermissionsResult(int rq, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rq, perms, res);
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (rq == RQ_LOC && geoCb != null) { geoCb.invoke(geoOrigin, ok, false); geoCb = null; }
        if (rq == RQ_NOTIF && pendingNotifId != null) { resolve(pendingNotifId, status()); pendingNotifId = null; }
    }

    class Bridge {
        @JavascriptInterface
        public void call(final String id, final String m, final String json) {
            boolean heavy = "schedule".equals(m) || "cancelAll".equals(m) || "notesSchedule".equals(m) || "notesCancel".equals(m);
            if (heavy) io.execute(() -> handle(id, m, json)); else runOnUiThread(() -> handle(id, m, json));
        }
    }

    void resolve(String id, JSONObject o) {
        final String js = "window.__nb(" + JSONObject.quote(id) + "," + o + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    void handle(String id, String m, String json) {
        try {
            JSONObject a = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            switch (m) {
                case "schedule":
                    AdhanScheduler.saveAdhan(this, a.optJSONArray("items") == null ? new JSONArray() : a.optJSONArray("items"));
                    AdhanScheduler.scheduleNext(this); break;
                case "cancelAll":
                    AdhanScheduler.saveAdhan(this, new JSONArray()); AdhanScheduler.scheduleNext(this); break;
                case "notesSchedule":
                    if (a.optJSONArray("items") != null) AdhanScheduler.notesSchedule(this, a.getJSONArray("items"));
                    AdhanScheduler.scheduleNext(this); break;
                case "notesCancel":
                    if (a.optJSONArray("ids") != null) AdhanScheduler.notesCancel(this, a.getJSONArray("ids"));
                    AdhanScheduler.scheduleNext(this); break;
                case "requestNotifPermission":
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingNotifId = id; requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, RQ_NOTIF); return;
                    }
                    break;
                case "openExactAlarmSettings":
                    if (Build.VERSION.SDK_INT >= 31) startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
                    break;
                case "openBatterySettings":
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); break;
                case "stopAdhan":
                    startService(new Intent(this, AdhanService.class).setAction(AdhanService.ACT_STOP)); break;
                case "downloadUpdate":
                    startUpdate(); break;
                default: break; // "status"
            }
        } catch (Exception ignored) { }
        resolve(id, status());
    }

    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("native", true);
            o.put("versionName", versionName());
            o.put("notif", NotificationManagerCompat.from(this).areNotificationsEnabled());
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            o.put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
            o.put("battery", ((PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
            o.put("armed", AdhanScheduler.prefs(this).getInt(AdhanScheduler.K_ARMED, -1) >= 0);
        } catch (Exception ignored) { }
        return o;
    }

    /** Full-screen immersive mode: status bar and navigation bar hidden; a swipe from the edge shows them briefly. */
    private void applyImmersive() {
        android.view.Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) { // draw behind the camera cut-out too
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat.getInsetsController(w, w.getDecorView());
        if (c == null) return;
        c.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive(); // bars come back after keyboard/dialogs/notification shade: hide them again
    }

    String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) { return ""; }
    }

    /** Calls window.__upd.<call> in the web UI (progress / permission / error states of the update dialog). */
    void upd(final String call) {
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.__upd&&window.__upd." + call, null); });
    }

    /** Checks update.json once per page load. Shows the dialog only when a newer, installable version exists. */
    void checkForUpdate() {
        if (!checking.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                JSONObject x = UpdateWorker.fetchUpdateJson();
                if (x == null) return;
                long remoteCode = x.optLong("versionCode", 0);
                String remoteName = x.optString("versionName", "");
                String message = x.optString("message", "يتوفر إصدار جديد من رفيق المسلم");
                String apkUrl = x.optString("apkUrl", "");
                if (remoteCode <= UpdateWorker.currentVersionCode(this)) return;   // up to date: show nothing
                if (!UpdateWorker.isTrustedApkUrl(apkUrl)) return;                 // not installable: show nothing
                JSONArray notes = x.optJSONArray("notes");
                if (notes == null) notes = x.optJSONArray("whatsNew");
                if (notes == null) notes = new JSONArray();
                pendingApkUrl = apkUrl;
                pendingSha256 = x.optString("sha256", "").trim();
                pendingCode = remoteCode;
                final String js = "window.showUpdateDialog&&window.showUpdateDialog("
                        + JSONObject.quote(remoteName) + "," + JSONObject.quote(message) + ","
                        + JSONObject.quote(apkUrl) + "," + notes + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            } catch (Exception ignored) {
            } finally {
                checking.set(false);
            }
        }).start();
    }

    /** "تحديث الآن": make sure installing from this app is allowed, then download. */
    void startUpdate() {
        if (pendingApkUrl.isEmpty()) { upd("error('none')"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            waitingInstallPerm = true;
            upd("permission()");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) { waitingInstallPerm = false; upd("error('perm')"); }
            return;
        }
        downloadAndInstallApk();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingInstallPerm) { // returned from the "install unknown apps" settings screen
            waitingInstallPerm = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) downloadAndInstallApk();
            else upd("error('perm')");
        }
    }

    void downloadAndInstallApk() {
        if (pendingApkUrl.isEmpty() || !UpdateWorker.isTrustedApkUrl(pendingApkUrl)) { upd("error('url')"); return; }
        if (!downloading.compareAndSet(false, true)) return;
        final String apkUrl = pendingApkUrl, wantSha = pendingSha256;
        upd("progress(0)");
        new Thread(() -> {
            HttpURLConnection c = null;
            File dir = new File(getCacheDir(), "updates"), part = new File(dir, "update.apk.part"), apk = new File(dir, "rafiq-muslim-update.apk");
            try {
                dir.mkdirs();
                part.delete(); apk.delete();
                c = (HttpURLConnection) new URL(apkUrl).openConnection(); // https redirects (GitHub CDN) are followed
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setUseCaches(false);
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) { upd("error('http')"); return; }
                long total = c.getContentLengthLong();
                if (total > 300L * 1024 * 1024) { upd("error('size')"); return; }

                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                long done = 0; int lastPct = -1;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (done > 300L * 1024 * 1024) { upd("error('size')"); return; }
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) { lastPct = pct; upd("progress(" + pct + ")"); }
                        }
                    }
                    out.flush();
                }
                if (total > 0 && done != total) { upd("error('incomplete')"); return; }

                if (!wantSha.isEmpty()) { // optional integrity check when update.json provides "sha256"
                    StringBuilder hex = new StringBuilder();
                    for (byte b : md.digest()) hex.append(String.format("%02x", b));
                    if (!hex.toString().equalsIgnoreCase(wantSha)) { upd("error('hash')"); return; }
                }
                if (!part.renameTo(apk)) { upd("error('io')"); return; }
                if (!verifyApk(apk)) { apk.delete(); upd("error('verify')"); return; }

                Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
                final Intent install = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apkUri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                upd("installing()");
                runOnUiThread(() -> {
                    try { startActivity(install); }
                    catch (Exception e) { upd("error('install')"); }
                });
            } catch (Exception e) {
                upd("error('net')");
            } finally {
                part.delete();
                if (c != null) c.disconnect();
                downloading.set(false);
            }
        }).start();
    }

    /** Same package, newer versionCode, same signing certificate as the installed app (the OS enforces it again). */
    @SuppressWarnings("deprecation")
    boolean verifyApk(File apk) {
        try {
            PackageManager pm = getPackageManager();
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo a = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            if (a == null || !getPackageName().equals(a.packageName)) return false;
            long code = Build.VERSION.SDK_INT >= 28 ? a.getLongVersionCode() : a.versionCode;
            if (code <= UpdateWorker.currentVersionCode(this)) return false;
            PackageInfo me = pm.getPackageInfo(getPackageName(), flags);
            Signature[] x, y;
            if (Build.VERSION.SDK_INT >= 28) {
                if (a.signingInfo == null || me.signingInfo == null) return true; // unreadable: installer still enforces
                x = a.signingInfo.getApkContentsSigners();
                y = me.signingInfo.getApkContentsSigners();
            } else { x = a.signatures; y = me.signatures; }
            if (x == null || y == null) return true;
            return new java.util.HashSet<>(java.util.Arrays.asList(x)).equals(new java.util.HashSet<>(java.util.Arrays.asList(y)));
        } catch (Exception e) {
            return false;
        }
    }

    void setAppVersion() {
        String js = "window.setAppVersion && window.setAppVersion(" + JSONObject.quote(versionName()) + ")";
        web.evaluateJavascript(js, null);
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        if (web != null) { web.removeJavascriptInterface("AndroidNative"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
