package app.rafiq.muslim;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Background update check (notification only). The same static helpers are reused by MainActivity
 * so the update.json URL, parsing and APK-URL validation live in ONE place.
 */
public class UpdateWorker extends Worker {

    static final String UPDATE_URL =
            "https://raw.githubusercontent.com/RafiqMuslimApp/RafiqMuslim/main/update.json";

    private static final String CHANNEL_ID = "app_updates";
    private static final int NOTIFICATION_ID = 1001;

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Downloads and parses update.json. Returns null on any network/HTTP/parse failure. */
    static JSONObject fetchUpdateJson() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            StringBuilder b = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) b.append(line);
            }
            return new JSONObject(b.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @SuppressWarnings("deprecation")
    static long currentVersionCode(Context ctx) {
        try {
            android.content.pm.PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return Long.MAX_VALUE; // unknown => never claim an update exists
        }
    }

    /** Only https links to this project's GitHub repository are accepted as APK sources. */
    static boolean isTrustedApkUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            String path = u.getPath() == null ? "" : u.getPath();
            return ("github.com".equals(host) || "raw.githubusercontent.com".equals(host))
                    && path.startsWith("/RafiqMuslimApp/RafiqMuslim/");
        } catch (Exception e) {
            return false;
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            JSONObject x = fetchUpdateJson();
            if (x == null) return Result.retry();

            Context ctx = getApplicationContext();
            long remoteCode = x.optLong("versionCode", 0);
            String versionName = x.optString("versionName", "");
            String message = x.optString("message", "يتوفر إصدار جديد من رفيق المسلم");
            String apkUrl = x.optString("apkUrl", "");

            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (remoteCode <= currentVersionCode(ctx)) {
                nm.cancel(NOTIFICATION_ID); // already up to date: drop any stale update notification
                return Result.success();
            }
            if (!isTrustedApkUrl(apkUrl)) return Result.success(); // nothing installable yet

            SharedPreferences prefs = ctx.getSharedPreferences("update_prefs", Context.MODE_PRIVATE);
            // One notification per version: never repeats for the same release.
            if (remoteCode != prefs.getLong("last_notified_code", 0)) {
                showNotification(ctx, nm, versionName, message);
                prefs.edit().putLong("last_notified_code", remoteCode).apply();
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void showNotification(Context context, NotificationManager manager, String versionName, String message) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "تحديثات رفيق المسلم", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("إشعارات تحديثات تطبيق رفيق المسلم");
            manager.createNotificationChannel(channel);
        }

        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        PendingIntent pi = intent == null ? null : PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("تحديث جديد لرفيق المسلم")
                .setContentText(versionName.isEmpty() ? message : "الإصدار " + versionName + " متاح الآن، افتح التطبيق للتحديث")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        if (pi != null) n.setContentIntent(pi);

        try {
            manager.notify(NOTIFICATION_ID, n.build());
        } catch (SecurityException ignored) { }
    }
}
