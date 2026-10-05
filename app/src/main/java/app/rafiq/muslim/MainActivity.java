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
                15,
                java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(constraints).build();

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "rafiq_muslim_update_check",
                ExistingPeriodicWorkPolicy.KEEP,
                request
        );
    }

    static final String HOST = "appassets.androidplatform.net";
    static final String UPDATE_URL = "https://raw.githubusercontent.com/RafiqMuslimApp/RafiqMuslim/main/update.json";
    static final int RQ_NOTIF = 11, RQ_LOC = 12;
    private WebView web;
    private String pendingNotifId;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AdhanService.channels(this);
        scheduleUpdateCheck();
        web = new WebView(this);
        setContentView(web);
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
            @Override public void onPageFinished(WebView v, String url) { super.onPageFinished(v, url); checkForUpdate(); }
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
        public void call(final String id, final String m, final String json) { runOnUiThread(() -> handle(id, m, json)); }
    }

    void resolve(String id, JSONObject o) {
        final String js = "window.__nb(" + JSONObject.quote(id) + "," + o + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
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
                default: break; // "status"
            }
        } catch (Exception ignored) { }
        resolve(id, status());
    }

    JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("native", true);
            o.put("notif", NotificationManagerCompat.from(this).areNotificationsEnabled());
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            o.put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
            o.put("battery", ((PowerManager) getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName()));
            o.put("armed", AdhanScheduler.prefs(this).getInt(AdhanScheduler.K_ARMED, -1) >= 0);
        } catch (Exception ignored) { }
        return o;
    }

    void checkForUpdate() {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL u = new URL(UPDATE_URL);
                c = (HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);

                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return;

                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder b = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) b.append(line);
                r.close();

                JSONObject x = new JSONObject(b.toString());
                long remoteCode = x.optLong("versionCode", 0);
                String remoteName = x.optString("versionName", "");
                String message = x.optString("message", "يتوفر إصدار جديد من رفيق المسلم");
                String telegram = x.optString("telegram", "https://t.me/Rafiq_Almusilm");

                long currentCode;
                if (Build.VERSION.SDK_INT >= 28) {
                    currentCode = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
                } else {
                    currentCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                }

                if (remoteCode > currentCode) {
                    runOnUiThread(() -> showUpdateDialog(remoteName, message, telegram));
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    void showUpdateDialog(String versionName, String message, String telegram) {
        String js = "window.showUpdateDialog && window.showUpdateDialog("
                + JSONObject.quote(versionName) + ","
                + JSONObject.quote(message) + ","
                + JSONObject.quote(telegram) + ")";
        web.evaluateJavascript(js, null);
    }


}
