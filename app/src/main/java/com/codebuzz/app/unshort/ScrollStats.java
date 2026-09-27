package com.codebuzz.app.unshort;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * Per-day, per-app totals of swipes and time spent in short-form feeds, kept
 * for the last {@link #KEEP_DAYS} days. Written by {@link ScrollTracker} and
 * read by the dashboard.
 */
final class ScrollStats {

    // Apps as shown on the dashboard. The array order is also the chart's stack
    // order and color order, so it must not change.
    static final String APP_INSTAGRAM = "instagram";
    static final String APP_YOUTUBE = "youtube";
    static final String APP_TIKTOK = "tiktok";
    static final String[] APPS = {APP_INSTAGRAM, APP_YOUTUBE, APP_TIKTOK};

    private static final String PREFS_NAME = "scroll_stats";
    private static final int KEEP_DAYS = 30;

    private static ScrollStats instance;

    private final SharedPreferences prefs;
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);

    static synchronized ScrollStats getInstance(Context context) {
        if (instance == null) instance = new ScrollStats(context.getApplicationContext());
        return instance;
    }

    private ScrollStats(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        pruneOldDays();
    }

    /** Maps a package name to its dashboard app, or null if it isn't a tracked app. */
    static String appFor(String packageName) {
        if (packageName == null) return null;
        switch (packageName) {
            case "com.instagram.android":
                return APP_INSTAGRAM;
            case "com.google.android.youtube":
                return APP_YOUTUBE;
            case "com.zhiliaoapp.musically":
            case "com.ss.android.ugc.trill":
                return APP_TIKTOK;
            default:
                return null;
        }
    }

    void addSwipe(String packageName) {
        String app = appFor(packageName);
        if (app == null) return;
        String key = key(today(), app, "s");
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    void addTime(String packageName, long ms) {
        String app = appFor(packageName);
        if (app == null || ms <= 0) return;
        String key = key(today(), app, "t");
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + ms).apply();
    }

    /** @param daysAgo 0 for today, 1 for yesterday, ... */
    int swipes(int daysAgo, String app) {
        return prefs.getInt(key(day(daysAgo), app, "s"), 0);
    }

    long timeMs(int daysAgo, String app) {
        return prefs.getLong(key(day(daysAgo), app, "t"), 0L);
    }

    int totalSwipes(int daysAgo) {
        int sum = 0;
        for (String app : APPS) sum += swipes(daysAgo, app);
        return sum;
    }

    long totalTimeMs(int daysAgo) {
        long sum = 0;
        for (String app : APPS) sum += timeMs(daysAgo, app);
        return sum;
    }

    /** Day-start timestamp for {@code daysAgo}, for labelling. */
    static Date dayDate(int daysAgo) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return c.getTime();
    }

    private String today() {
        return day(0);
    }

    private String day(int daysAgo) {
        return dayFormat.format(dayDate(daysAgo));
    }

    private static String key(String day, String app, String metric) {
        return day + "|" + app + "|" + metric;
    }

    private void pruneOldDays() {
        String oldest = day(KEEP_DAYS - 1);
        SharedPreferences.Editor editor = null;
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            String k = e.getKey();
            int bar = k.indexOf('|');
            if (bar > 0 && k.substring(0, bar).compareTo(oldest) < 0) {
                if (editor == null) editor = prefs.edit();
                editor.remove(k);
            }
        }
        if (editor != null) editor.apply();
    }
}
