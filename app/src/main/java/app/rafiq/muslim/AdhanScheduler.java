package app.rafiq.muslim;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Keeps the adhan schedule + generic reminders (hourly dhikr...) in SharedPreferences and arms exactly ONE
 * AlarmManager alarm: the earliest upcoming item. Every item has a stable id/key => no duplicate alarms.
 */
public class AdhanScheduler {
    static final String PREFS = "adhan_prefs";
    static final String K_LIST = "list", K_NOTES = "notes", K_ARMED = "armed", K_PLAYED = "played";
    static final String ACTION = "app.rafiq.muslim.ADHAN_ALARM";

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONArray read(Context c, String k) {
        try { return new JSONArray(prefs(c).getString(k, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static void saveAdhan(Context c, JSONArray items) {
        prefs(c).edit().putString(K_LIST, items.toString()).apply();
    }

    /** Replace/add reminder items {id,title,body,at}. */
    public static void notesSchedule(Context c, JSONArray items) {
        try {
            long now = System.currentTimeMillis();
            JSONArray old = read(c, K_NOTES), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                boolean replaced = false;
                for (int j = 0; j < items.length(); j++) if (items.getJSONObject(j).optInt("id", -1) == o.optInt("id")) replaced = true;
                if (!replaced && o.optLong("at") > now - 3600000L) out.put(o);
            }
            for (int j = 0; j < items.length(); j++) {
                JSONObject n = items.getJSONObject(j);
                if (n.optLong("at") <= now) continue;
                n.put("key", "n" + n.optInt("id") + "@" + n.optLong("at")).put("play", false).put("notif", true).put("ch", "reminders");
                out.put(n);
            }
            prefs(c).edit().putString(K_NOTES, out.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static void notesCancel(Context c, JSONArray ids) {
        try {
            JSONArray old = read(c, K_NOTES), out = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.getJSONObject(i);
                boolean drop = false;
                for (int j = 0; j < ids.length(); j++) if (ids.optInt(j, -1) == o.optInt("id")) drop = true;
                if (!drop) out.put(o);
            }
            prefs(c).edit().putString(K_NOTES, out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static Intent baseIntent(Context c) { return new Intent(c, AdhanReceiver.class).setAction(ACTION); }

    static int flags(boolean create) {
        return PendingIntent.FLAG_IMMUTABLE | (create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
    }

    public static void cancelArmed(Context c) {
        int id = prefs(c).getInt(K_ARMED, -1);
        if (id < 0) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = PendingIntent.getBroadcast(c, id, baseIntent(c), flags(false));
        if (p != null) { am.cancel(p); p.cancel(); }
        prefs(c).edit().remove(K_ARMED).apply();
    }

    public static void scheduleNext(Context c) {
        try {
            long now = System.currentTimeMillis();
            JSONObject best = null;
            JSONArray[] lists = { read(c, K_LIST), read(c, K_NOTES) };
            for (JSONArray a : lists) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                long at = o.getLong("at");
                if (at > now + 500 && (best == null || at < best.getLong("at"))) best = o;
            }
            cancelArmed(c);
            if (best == null) return;
            int id = best.getInt("id");
            long at = best.getLong("at");
            Intent in = baseIntent(c)
                .putExtra("id", id).putExtra("at", at)
                .putExtra("key", best.optString("key"))
                .putExtra("title", best.optString("title", "الأذان"))
                .putExtra("body", best.optString("body"))
                .putExtra("sound", best.optString("sound"))
                .putExtra("ch", best.optString("ch", "adhan"))
                .putExtra("play", best.optBoolean("play", true))
                .putExtra("notif", best.optBoolean("notif", true));
            PendingIntent pi = PendingIntent.getBroadcast(c, id, in, flags(true));
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); // inexact until the user grants exact alarms
            prefs(c).edit().putInt(K_ARMED, id).apply();
        } catch (Exception ignored) { }
    }

    /** True only the first time a prayer/day key is claimed => the same adhan can never play twice. */
    public static synchronized boolean claim(Context c, String key) {
        if (key == null || key.isEmpty()) return true;
        SharedPreferences p = prefs(c);
        String last = p.getString(K_PLAYED, "");
        if (last.contains("|" + key + "|")) return false;
        String[] parts = last.split("\\|");
        StringBuilder sb = new StringBuilder("|");
        for (int i = Math.max(0, parts.length - 40); i < parts.length; i++) if (!parts[i].isEmpty()) sb.append(parts[i]).append("|");
        p.edit().putString(K_PLAYED, sb.append(key).append("|").toString()).apply();
        return true;
    }
}
