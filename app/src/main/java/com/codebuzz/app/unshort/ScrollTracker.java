package com.codebuzz.app.unshort;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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

import java.util.List;
import java.util.Locale;

/**
 * Tracks mindless scrolling in short-form video feeds (Instagram Reels, YouTube
 * Shorts, TikTok). While the user is in one of those feeds, two translucent
 * bubbles are shown on top of it: one with the number of swipes made and one
 * with the time spent in the feed. Totals are also recorded in
 * {@link ScrollStats} for the dashboard.
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

    // A page must fill at least this much of the feed to count as landed on,
    // so a swipe that is let go halfway and bounces back isn't counted.
    private static final float SETTLED_FRACTION = 0.8f;

    // Limits how often a stream of accessibility events re-inspects the screen.
    private static final long EVAL_THROTTLE_MS = 300;

    // TikTok is a short-form feed as a whole.
    private static final String[] TIKTOK_PACKAGES = {
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
    };

    // Views that are only on screen while the Reels / Shorts player is showing.
    private static final String[] FEED_VIEW_IDS = {
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
    };

    // Paged lists whose pages report their row index. In these, a swipe is
    // counted when a different page lands on screen: YouTube Shorts doesn't send
    // TYPE_VIEW_SCROLLED when moving between videos, so scroll events miss swipes.
    private static final String[] PAGED_FEED_IDS = {
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.google.android.youtube:id/reel_recycler",
    };

    private final AccessibilityService service;
    private final WindowManager windowManager;
    private final ScrollStats stats;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private View overlay;
    private TextView scrollCountText;
    private TextView timeText;

    private boolean inFeed = false;
    private String feedPackage = null;
    private boolean hasSession = false;
    private int scrollCount = 0;
    private long feedTimeMs = 0L;
    private long lastTickUptimeMs = 0L;
    private long leftFeedUptimeMs = 0L;
    private long lastScrollEventUptimeMs = 0L;
    private long lastEvalUptimeMs = 0L;

    // Row of the page last seen filling the feed, or -1 if unknown.
    private int lastRow = -1;
    // Whether the current feed reports rows; if not, scroll events are counted.
    private boolean feedReportsRows = false;

    ScrollTracker(AccessibilityService service) {
        this.service = service;
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        this.stats = ScrollStats.getInstance(service);
    }

    static boolean isTrackedPackage(String packageName) {
        return ScrollStats.appFor(packageName) != null;
    }

    void onAccessibilityEvent(AccessibilityEvent event) {
        long now = SystemClock.uptimeMillis();
        int type = event.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || now - lastEvalUptimeMs >= EVAL_THROTTLE_MS) {
            evaluate();
        }

        if (inFeed && !feedReportsRows && type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
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
        String pkg = root != null && root.getPackageName() != null
                ? root.getPackageName().toString() : null;
        boolean nowInFeed = root != null && isFeedScreen(root, pkg);

        if (!nowInFeed) {
            if (inFeed) leaveFeed();
            return;
        }

        if (!inFeed) {
            enterFeed(pkg);
        } else if (!pkg.equals(feedPackage)) {
            // Switched straight from one feed app to another.
            accumulateTime();
            feedPackage = pkg;
            lastRow = -1;
            feedReportsRows = false;
        }
        checkPage(root, pkg);
    }

    private boolean isFeedScreen(AccessibilityNodeInfo root, String pkg) {
        if (pkg == null) return false;

        for (String p : TIKTOK_PACKAGES) {
            if (p.equals(pkg)) return true;
        }

        for (String id : FEED_VIEW_IDS) {
            if (!id.startsWith(pkg + ":")) continue;
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isVisibleToUser()) return true;
            }
        }
        return false;
    }

    private void checkPage(AccessibilityNodeInfo root, String pkg) {
        AccessibilityNodeInfo feed = findVisible(root, pkg, PAGED_FEED_IDS);
        if (feed == null) return;

        Rect feedBounds = new Rect();
        feed.getBoundsInScreen(feedBounds);
        if (feedBounds.height() <= 0) return;

        // The page taking up most of the feed is the one being watched.
        AccessibilityNodeInfo page = null;
        int pageHeight = 0;
        Rect r = new Rect();
        for (int i = 0; i < feed.getChildCount(); i++) {
            AccessibilityNodeInfo child = feed.getChild(i);
            if (child == null) continue;
            child.getBoundsInScreen(r);
            if (!r.intersect(feedBounds)) continue;
            if (r.height() > pageHeight) {
                pageHeight = r.height();
                page = child;
            }
        }
        if (page == null || pageHeight < feedBounds.height() * SETTLED_FRACTION) return;

        AccessibilityNodeInfo.CollectionItemInfo item = page.getCollectionItemInfo();
        if (item == null) return;

        feedReportsRows = true;
        int row = item.getRowIndex();
        if (row != lastRow) Log.d(TAG, "Page " + lastRow + " -> " + row);
        if (lastRow >= 0 && row != lastRow) countSwipe();
        lastRow = row;
    }

    private static AccessibilityNodeInfo findVisible(AccessibilityNodeInfo root, String pkg, String[] ids) {
        for (String id : ids) {
            if (!id.startsWith(pkg + ":")) continue;
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
                if (n.isVisibleToUser()) return n;
            }
        }
        return null;
    }

    private void countSwipe() {
        scrollCount++;
        stats.addSwipe(feedPackage);
        render();
    }

    private void enterFeed(String pkg) {
        long now = SystemClock.uptimeMillis();
        long away = now - leftFeedUptimeMs;
        boolean newSession = !hasSession || away > SESSION_RESET_MS;
        if (newSession) {
            scrollCount = 0;
            feedTimeMs = 0L;
            hasSession = true;
        }
        if (newSession || away > POSITION_FORGET_MS || !pkg.equals(feedPackage)) {
            lastRow = -1;
            feedReportsRows = false;
        }
        inFeed = true;
        feedPackage = pkg;
        lastTickUptimeMs = now;
        lastScrollEventUptimeMs = 0L;
        Log.d(TAG, "Entered short-form feed in " + pkg + (newSession ? " (new session)" : " (resumed)"));

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
        stats.addTime(feedPackage, delta);
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
