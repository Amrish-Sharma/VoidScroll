package com.codebuzz.app.unshort;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.Locale;

/**
 * Tracks mindless scrolling in short-form video feeds (Instagram Reels, YouTube
 * Shorts, TikTok, X videos, and their web versions in Chrome). While the user is
 * in one of those feeds, two translucent bubbles are shown on top of it: one
 * with the number of swipes made and one with the time spent in the feed.
 * Totals are also recorded in {@link ScrollStats} for the dashboard.
 */
class ScrollTracker {

    private static final String TAG = "ScrollTracker";

    private static final long TICK_MS = 1000;

    // Fallback for feeds that don't expose item positions: a single swipe emits
    // a burst of TYPE_VIEW_SCROLLED events, so a new swipe is counted only after
    // the feed has been still for at least this long.
    private static final long SCROLL_GAP_MS = 400;

    // Leaving the feed briefly (opening comments, pulling down notifications)
    // keeps the session going; staying away longer than this starts a fresh one.
    private static final long SESSION_RESET_MS = 60_000;

    // The feed can briefly look "gone" while a swipe animates. Coming back within
    // this window keeps the last known page, so that swipe is still counted;
    // after longer the feed may have been rebuilt and positions start over.
    private static final long POSITION_FORGET_MS = 3_000;

    // Limits how often a stream of accessibility events re-inspects the screen.
    private static final long EVAL_THROTTLE_MS = 300;

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final ScrollStats stats;
    private final FeedDetector detector = new FeedDetector(true);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private View overlay;
    private TextView scrollCountText;
    private TextView timeText;

    private boolean inFeed = false;
    // Dashboard app (ScrollStats.APPS) of the current or last feed.
    private String feedApp = null;
    private boolean hasSession = false;
    private int scrollCount = 0;
    private long feedTimeMs = 0L;
    private long lastTickUptimeMs = 0L;
    private long leftFeedUptimeMs = 0L;
    private long lastScrollEventUptimeMs = 0L;
    private long lastEvalUptimeMs = 0L;

    // Key of the page last seen filling the feed, or null if unknown.
    private String lastPageKey = null;
    // Whether the current feed identifies its pages; if not, scroll events are counted.
    private boolean feedReportsPages = false;

    ScrollTracker(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        this.stats = ScrollStats.getInstance(service);
    }

    static boolean isTrackedPackage(String packageName) {
        return FeedDetector.isWatchedPackage(packageName);
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        long now = SystemClock.uptimeMillis();
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || now - lastEvalUptimeMs >= EVAL_THROTTLE_MS) {
            evaluate();
        }

        if (inFeed && !feedReportsPages && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (now - lastScrollEventUptimeMs >= SCROLL_GAP_MS) {
                countSwipe();
            }
            lastScrollEventUptimeMs = now;
        }
    }

    void release() {
        if (inFeed) leaveFeed();
        handler.removeCallbacksAndMessages(null);
        removeOverlay();
    }

    // Checks what is on screen right now: enters/leaves feed mode accordingly
    // and counts a swipe if a different page has landed in the feed.
    private void evaluate() {
        lastEvalUptimeMs = SystemClock.uptimeMillis();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        FeedDetector.Feed feed = detector.detect(root, inFeed);

        if (feed == null) {
            if (inFeed) leaveFeed();
            return;
        }

        if (!inFeed) {
            enterFeed(feed.app);
        } else if (!feed.app.equals(feedApp)) {
            // Switched straight from one feed to another.
            accumulateTime();
            feedApp = feed.app;
            lastPageKey = null;
            feedReportsPages = false;
        }
        checkPage(feed.pageKey);
    }

    private void checkPage(String pageKey) {
        if (pageKey == null) return;
        feedReportsPages = true;
        if (!pageKey.equals(lastPageKey)) Log.d(TAG, "Page " + lastPageKey + " -> " + pageKey);
        if (lastPageKey != null && !pageKey.equals(lastPageKey)) countSwipe();
        lastPageKey = pageKey;
    }

    private void countSwipe() {
        scrollCount++;
        stats.addSwipe(feedApp);
        render();
    }

    private void enterFeed(String app) {
        long now = SystemClock.uptimeMillis();
        long away = now - leftFeedUptimeMs;
        boolean newSession = !hasSession || away > SESSION_RESET_MS;
        if (newSession) {
            scrollCount = 0;
            feedTimeMs = 0L;
            hasSession = true;
        }
        if (newSession || away > POSITION_FORGET_MS || !app.equals(feedApp)) {
            lastPageKey = null;
            feedReportsPages = false;
        }
        inFeed = true;
        feedApp = app;
        lastTickUptimeMs = now;
        lastScrollEventUptimeMs = 0L;
        Log.d(TAG, "Entered short-form feed: " + app + (newSession ? " (new session)" : " (resumed)"));

        showOverlay();
        render();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    private void leaveFeed() {
        accumulateTime();
        inFeed = false;
        leftFeedUptimeMs = SystemClock.uptimeMillis();
        handler.removeCallbacks(tick);
        hideOverlay();
        Log.d(TAG, "Left short-form feed: " + scrollCount + " swipes, " + formatTime(feedTimeMs));
    }

    private void onTick() {
        if (!inFeed) return;
        accumulateTime();
        // The service only receives events from the tracked apps, so switching
        // to another app is noticed here rather than through an event.
        evaluate();
        if (inFeed) {
            render();
            handler.postDelayed(tick, TICK_MS);
        }
    }

    private void accumulateTime() {
        long now = SystemClock.uptimeMillis();
        long delta = now - lastTickUptimeMs;
        lastTickUptimeMs = now;
        feedTimeMs += delta;
        stats.addTime(feedApp, delta);
    }

    private void render() {
        if (overlay == null) return;
        scrollCountText.setText(String.valueOf(scrollCount));
        timeText.setText(formatTime(feedTimeMs));
    }

    static String formatTime(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }

    private void showOverlay() {
        if (overlay == null) {
            overlay = LayoutInflater.from(service).inflate(R.layout.overlay_scroll_bubbles, null);
            scrollCountText = overlay.findViewById(R.id.scrollCountText);
            timeText = overlay.findViewById(R.id.scrollTimeText);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    // Purely informational: never steal touches or focus from the feed.
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = dp(12);
            lp.y = dp(120);
            try {
                windowManager.addView(overlay, lp);
            } catch (Exception e) {
                Log.w(TAG, "Could not add scroll bubbles overlay", e);
                overlay = null;
                return;
            }
        }
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        if (overlay != null) overlay.setVisibility(View.GONE);
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try {
            windowManager.removeView(overlay);
        } catch (Exception e) {
            Log.w(TAG, "Could not remove scroll bubbles overlay", e);
        }
        overlay = null;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                service.getResources().getDisplayMetrics());
    }
}
