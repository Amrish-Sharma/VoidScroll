package com.codebuzz.app.unshort;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class TimeLimitManager {
    private static final String TAG = "TimeLimitManager";
    private static final String PREFS_NAME = "time_limits_prefs";
    private static final String PREF_KEY_PREFIX = "used_"; // + yyyyMMdd + _ + package

    private static volatile TimeLimitManager instance;

    public static TimeLimitManager getInstance(Context ctx) {
        if (instance == null) {
            synchronized (TimeLimitManager.class) {
                if (instance == null) {
                    instance = new TimeLimitManager(ctx.getApplicationContext());
                }
            }
        }
        return instance;
    }

    // Current day's date stamp, used to scope daily usage/allowance counters so they reset at midnight
    public static String todayStamp() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler;

    // Defaults
    private long defaultLimitMs = 15 * 60 * 1000; // 15 minutes
    private long warningThresholdMs = 5 * 60 * 1000; // 5 minutes
    private boolean perSessionDefault = false; // "total" by default

    private String currentPackage = null;
    private long sessionStart = 0L;

    private final HashMap<String, Runnable> scheduledRunnables = new HashMap<>();

    private static final String CHANNEL_ID = "focusguard_warnings";

    // Preset categories and their default limits (ms)
    public static final String CATEGORY_SOCIAL = "Social Media";
    public static final String CATEGORY_GAMES = "Games";
    public static final String CATEGORY_PRODUCTIVITY = "Productivity";
    public static final String CATEGORY_OTHER = "Other";
    private static final HashMap<String, Long> CATEGORY_LIMITS = new HashMap<>();
    static {
        CATEGORY_LIMITS.put(CATEGORY_SOCIAL, 15 * 60 * 1000L); // 15 min
        CATEGORY_LIMITS.put(CATEGORY_GAMES, 30 * 60 * 1000L); // 30 min
        CATEGORY_LIMITS.put(CATEGORY_PRODUCTIVITY, 60 * 60 * 1000L); // 1 hr
        CATEGORY_LIMITS.put(CATEGORY_OTHER, 20 * 60 * 1000L); // 20 min
    }

    // Store app-category assignments
    private static final String PREF_CATEGORY_PREFIX = "cat_"; // + package

    // Focus Session (Deep Work Mode) state
    private static final String PREF_FOCUS_SESSION = "focus_session_active";

    private TimeLimitManager(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
    }

    // Call when an app comes to foreground
    public void onAppForeground(String pkg) {
        if (pkg == null) return;
        if (pkg.equals(currentPackage)) return;

        // finalize previous
        onAppBackground();

        currentPackage = pkg;
        sessionStart = System.currentTimeMillis();

        long used = getTodayUsedMs(pkg);
        long limit = getLimitForPackage(pkg);
        boolean perSession = getPerSessionFlag(pkg);

        if (perSession) {
            used = 0; // reset for session mode
        }

        long remaining = limit - used;
        Log.d(TAG, "Foreground: " + pkg + " used=" + used + " remaining=" + remaining);

        if (remaining <= 0) {
            // already exceeded
            notifyLimitReached(pkg);
            return;
        }

        // schedule warning and block runnables
        if (remaining > warningThresholdMs) {
            long warnDelay = remaining - warningThresholdMs;
            scheduleRunnable(pkg + "_warn", () -> notifyWarning(pkg, warningThresholdMs));
            handler.postDelayed(scheduledRunnables.get(pkg + "_warn"), warnDelay);
        } else {
            // If already within warning threshold, warn immediately
            notifyWarning(pkg, remaining);
        }

        // schedule block
        scheduleRunnable(pkg + "_block", () -> notifyLimitReached(pkg));
        handler.postDelayed(scheduledRunnables.get(pkg + "_block"), remaining);
    }

    // Call when an app goes to background or on package change
    public void onAppBackground() {
        if (currentPackage == null || sessionStart == 0L) return;
        long now = System.currentTimeMillis();
        long session = now - sessionStart;

        boolean perSession = getPerSessionFlag(currentPackage);
        if (perSession) {
            // store session as used amount for this session only (not accumulating across day)
            // For per-session mode we may not persist across sessions; keep it simple and store last session duration
            prefs.edit().putLong(PREF_KEY_PREFIX + todayStamp() + "_" + currentPackage, session).apply();
        } else {
            long prev = getTodayUsedMs(currentPackage);
            prefs.edit().putLong(PREF_KEY_PREFIX + todayStamp() + "_" + currentPackage, prev + session).apply();
        }

        // cancel scheduled runnables for previous package
        cancelScheduled(currentPackage + "_warn");
        cancelScheduled(currentPackage + "_block");
        cancelScheduled(currentPackage + "_extra_block");

        Log.d(TAG, "Background: " + currentPackage + " session=" + session);

        currentPackage = null;
        sessionStart = 0L;
    }

    private void scheduleRunnable(String key, Runnable r) {
        cancelScheduled(key);
        scheduledRunnables.put(key, r);
    }

    private void cancelScheduled(String key) {
        Runnable r = scheduledRunnables.remove(key);
        if (r != null) {
            handler.removeCallbacks(r);
        }
    }

    public long getTodayUsedMs(String pkg) {
        return prefs.getLong(PREF_KEY_PREFIX + todayStamp() + "_" + pkg, 0L);
    }

    public void resetTodayUsage(String pkg) {
        prefs.edit().putLong(PREF_KEY_PREFIX + todayStamp() + "_" + pkg, 0L).apply();
    }

    public long getLimitForPackage(String pkg) {
        // If Focus Session is active and app is in a distracting category, block it
        if (isFocusSessionActive()) {
            String cat = getAppCategory(pkg);
            if (CATEGORY_SOCIAL.equals(cat) || CATEGORY_GAMES.equals(cat)) {
                return 0L; // Block completely during Focus Session
            }
        }
        // If per-app limit exists, use it; else use category limit
        // (Assume getPerAppLimitMs returns 0 if not set)
        long perApp = getPerAppLimitMs(pkg);
        if (perApp > 0) return perApp;
        String cat = getAppCategory(pkg);
        return getCategoryLimit(cat);
    }

    public boolean getPerSessionFlag(String pkg) {
        // Placeholder: per-package flag storage. Return default for now
        return perSessionDefault;
    }

    // Grant extra time (ms) for the given package from now. Schedules a new block in ms and a warning
    public void grantExtraTime(String pkg, long extraMs) {
        if (pkg == null) return;
        Log.d(TAG, "grantExtraTime: " + pkg + " +" + extraMs + "ms");

        // cancel any existing block/warn for this pkg
        cancelScheduled(pkg + "_warn");
        cancelScheduled(pkg + "_block");
        cancelScheduled(pkg + "_extra_block");

        // schedule warning based on warningThresholdMs
        if (extraMs > warningThresholdMs) {
            long warnDelay = extraMs - warningThresholdMs;
            scheduleRunnable(pkg + "_extra_warn", () -> notifyWarning(pkg, warningThresholdMs));
            handler.postDelayed(scheduledRunnables.get(pkg + "_extra_warn"), warnDelay);
        } else {
            // immediate warn
            notifyWarning(pkg, extraMs);
        }

        // schedule extra block
        scheduleRunnable(pkg + "_extra_block", () -> notifyLimitReached(pkg));
        handler.postDelayed(scheduledRunnables.get(pkg + "_extra_block"), extraMs);
    }

    // Listener for external actions (e.g. auto-block)
    public interface LimitListener {
        void onLimitReached(String pkg);
    }

    private LimitListener limitListener = null;

    public void setLimitListener(LimitListener l) {
        this.limitListener = l;
    }

    private void notifyWarning(String pkg, long remainingMs) {
        Log.d(TAG, "Warning: " + pkg + " remainingMs=" + remainingMs);
        String title = "FocusGuard: Time remaining";
        String text = pkg + " — " + formatMs(remainingMs) + " remaining";
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(pkg.hashCode() | 0x1000, n);
    }

    private void notifyLimitReached(String pkg) {
        Log.d(TAG, "Limit reached: " + pkg);
        String title = "FocusGuard: Time limit reached";
        String text = pkg + " has reached its time limit.";
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(pkg.hashCode() | 0x2000, n);

        // Notify listener so caller can perform blocking action
        if (limitListener != null) {
            limitListener.onLimitReached(pkg);
        }

        // For auto-blocking, the service can listen to this notification or call a callback.
        // Here we just post the notification and log. The caller should implement actual blocking action.
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "FocusGuard warnings", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Notifications for app time warnings and limits");
            nm.createNotificationChannel(ch);
        }
    }

    private String formatMs(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s = s % 60;
        return m + "m " + s + "s";
    }

    // Store app-category assignments

    public void setAppCategory(String pkg, String category) {
        prefs.edit().putString(PREF_CATEGORY_PREFIX + pkg, category).apply();
    }
    public String getAppCategory(String pkg) {
        return prefs.getString(PREF_CATEGORY_PREFIX + pkg, CATEGORY_OTHER);
    }
    public long getCategoryLimit(String category) {
        Long limit = CATEGORY_LIMITS.get(category);
        return limit != null ? limit : defaultLimitMs;
    }

    public boolean isFocusSessionActive() {
        return prefs.getBoolean(PREF_FOCUS_SESSION, false);
    }
    public void startFocusSession() {
        prefs.edit().putBoolean(PREF_FOCUS_SESSION, true).apply();
    }
    public void endFocusSession() {
        prefs.edit().putBoolean(PREF_FOCUS_SESSION, false).apply();
    }

    // Dummy implementation for per-app limit (returns 0 = not set)
    public long getPerAppLimitMs(String pkg) {
        // TODO: Implement per-app custom limits if needed
        return 0L;
    }
}
