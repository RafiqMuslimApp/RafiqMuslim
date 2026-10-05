package app.rafiq.muslim;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
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

public class UpdateWorker extends Worker {

    private static final String UPDATE_URL =
            "https://raw.githubusercontent.com/RafiqMuslimApp/RafiqMuslim/main/update.json";

    private static final String CHANNEL_ID = "app_updates";
    private static final int NOTIFICATION_ID = 1001;

    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        HttpURLConnection c = null;

        try {
            URL u = new URL(UPDATE_URL);
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);

            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return Result.retry();
            }

            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8")
            );

            StringBuilder b = new StringBuilder();
            String line;

            while ((line = r.readLine()) != null) {
                b.append(line);
            }

            r.close();

            JSONObject x = new JSONObject(b.toString());

            long remoteCode = x.optLong("versionCode", 0);
            String versionName = x.optString("versionName", "");
            String message = x.optString(
                    "message",
                    "يتوفر إصدار جديد من رفيق المسلم"
            );

            long currentCode;

            if (Build.VERSION.SDK_INT >= 28) {
                currentCode = getApplicationContext()
                        .getPackageManager()
                        .getPackageInfo(
                                getApplicationContext().getPackageName(),
                                0
                        )
                        .getLongVersionCode();
            } else {
                currentCode = getApplicationContext()
                        .getPackageManager()
                        .getPackageInfo(
                                getApplicationContext().getPackageName(),
                                0
                        )
                        .versionCode;
            }

            if (remoteCode > currentCode) {
                android.content.SharedPreferences prefs =
                        getApplicationContext().getSharedPreferences(
                                "update_prefs",
                                Context.MODE_PRIVATE
                        );

                long lastNotifiedCode = prefs.getLong("last_notified_code", 0);

                if (remoteCode != lastNotifiedCode) {
                    showNotification(versionName, message);

                    prefs.edit()
                            .putLong("last_notified_code", remoteCode)
                            .apply();
                }
            }

            return Result.success();

        } catch (Exception e) {
            return Result.retry();

        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void showNotification(String versionName, String message) {

        Context context = getApplicationContext();

        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE
                );

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "تحديثات رفيق المسلم",
                    NotificationManager.IMPORTANCE_DEFAULT
            );

            channel.setDescription("إشعارات تحديثات تطبيق رفيق المسلم");
            manager.createNotificationChannel(channel);
        }

        Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(context.getPackageName());

        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("تحديث جديد لرفيق المسلم")
                        .setContentText(
                                versionName.isEmpty()
                                        ? message
                                        : "الإصدار " + versionName + " متاح الآن"
                        )
                        .setStyle(
                                new NotificationCompat.BigTextStyle()
                                        .bigText(message)
                        )
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

        if (intent != null) {
            notification.setContentIntent(
                    androidx.core.app.TaskStackBuilder.create(context)
                            .addNextIntentWithParentStack(intent)
                            .getPendingIntent(
                                    0,
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT |
                                            (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0)
                            )
            );
        }

        manager.notify(NOTIFICATION_ID, notification.build());
    }
}
