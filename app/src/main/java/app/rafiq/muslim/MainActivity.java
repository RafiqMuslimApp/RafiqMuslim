package app.rafiq.muslim;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
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

import org.json.JSONArray;
import org.json.JSONObject;

/** One app: WebView hosting the bundled web UI (assets/www) + native adhan bridge (window.AndroidNative). */
public class MainActivity extends Activity {
    static final String HOST = "appassets.androidplatform.net";
    static final int RQ_NOTIF = 11, RQ_LOC = 12;
    private WebView web;
    private String pendingNotifId;
    private GeolocationPermissions.Callback geoCb;
    private String geoOrigin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AdhanService.channels(this);
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
}
