package app.rafiq.muslim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Foreground (mediaPlayback) service: plays the local adhan MP3 from res/raw with proper audio focus. */
public class AdhanService extends Service implements MediaPlayer.OnCompletionListener, AudioManager.OnAudioFocusChangeListener {
    static final String CH = "adhan", CH_QUIET = "adhan_quiet", CH_REM = "reminders";
    static final String ACT_STOP = "app.rafiq.muslim.ADHAN_STOP";
    static final int NID = 7711;

    private MediaPlayer mp;
    private AudioManager am;
    private AudioFocusRequest focusReq;
    private boolean hasFocus;

    static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel a = new NotificationChannel(CH, "الأذان", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("إشعار وقت الصلاة وتشغيل الأذان");
        a.setSound(null, null); // audio is played by the service itself
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
        NotificationChannel q = new NotificationChannel(CH_QUIET, "الأذان (بدون إشعار ظاهر)", NotificationManager.IMPORTANCE_MIN);
        q.setSound(null, null);
        nm.createNotificationChannel(q);
        NotificationChannel r = new NotificationChannel(CH_REM, "التذكيرات", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("تذكيرات الأذكار");
        nm.createNotificationChannel(r);
    }

    static PendingIntent openApp(Context c) {
        Intent li = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        return PendingIntent.getActivity(c, 0, li, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Notification build(Context c, String title, String body, String chan, boolean stopBtn) {
        channels(c);
        int pr = CH.equals(chan) ? NotificationCompat.PRIORITY_HIGH : (CH_REM.equals(chan) ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_MIN);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, chan)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title == null || title.isEmpty() ? "الأذان" : title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(CH_REM.equals(chan) ? NotificationCompat.CATEGORY_REMINDER : NotificationCompat.CATEGORY_ALARM)
            .setPriority(pr)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(c))
            .setAutoCancel(!stopBtn)
            .setOngoing(stopBtn);
        if (stopBtn) {
            Intent st = new Intent(c, AdhanService.class).setAction(ACT_STOP);
            b.addAction(0, "إيقاف الأذان", PendingIntent.getService(c, 1, st, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return b.build();
    }

    /** Notification without audio (sound disabled, a reminder, or the service could not start). */
    static void notifyOnly(Context c, String title, String body, boolean reminder) {
        try { NotificationManagerCompat.from(c).notify((int) (System.currentTimeMillis() % 100000) + 100, build(c, title, body, reminder ? CH_REM : CH, false)); }
        catch (SecurityException ignored) { }
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in == null || ACT_STOP.equals(in.getAction())) { finish(); return START_NOT_STICKY; }
        Notification n = build(this, in.getStringExtra("title"), in.getStringExtra("body"), in.getBooleanExtra("notif", true) ? CH : CH_QUIET, true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
        play(in.getStringExtra("sound"));
        return START_NOT_STICKY;
    }

    private void play(String sound) {
        try {
            release();
            int res = getResources().getIdentifier(sound == null ? "" : sound, "raw", getPackageName());
            if (res == 0) { finish(); return; }
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM) // alarm stream: audible with the screen off; volume is never forced
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).setOnAudioFocusChangeListener(this).build();
            hasFocus = am.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            AssetFileDescriptor fd = getResources().openRawResourceFd(res);
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener((m, w, e) -> { finish(); return true; });
            mp.prepare();
            mp.start();
        } catch (Exception e) { finish(); }
    }

    @Override public void onCompletion(MediaPlayer m) { finish(); }

    @Override public void onAudioFocusChange(int change) { if (change == AudioManager.AUDIOFOCUS_LOSS) finish(); }

    private void release() {
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) { } mp.release(); mp = null; }
        if (am != null && hasFocus && focusReq != null) { am.abandonAudioFocusRequest(focusReq); hasFocus = false; }
    }

    private void finish() { release(); stopForeground(true); stopSelf(); }

    @Override public void onDestroy() { release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }
}
