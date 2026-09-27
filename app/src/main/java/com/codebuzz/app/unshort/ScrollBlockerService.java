package com.codebuzz.app.unshort;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import android.content.Intent;

public class ScrollBlockerService extends AccessibilityService {

    private static final String TAG = "ScrollBlockerService";

    // Minimum time between two block actions (back press / scroll block) so that
    // our own action does not immediately re-trigger another action off the
    // accessibility event it causes. Without this, a burst of events (e.g. while
    // YouTube Shorts is loading) can fire dozens of GLOBAL_ACTION_BACK calls in
    // under a second, tearing through the host app's back stack and killing its
    // task entirely (looks like the app "crashing").
    private static final long MIN_ACTION_INTERVAL_MS = 800;

    // GLOBAL_ACTION_BACK is delayed by this much so it lands after the host
    // app's screen/fragment transition has finished. Firing it immediately upon
    // first detecting "Shorts" (e.g. while the Shorts fragment is still being
    // attached) collides with that in-flight transition and can hang/kill the
    // host app's activity (observed as "Activity pause timeout" / the task being
    // closed with numActivities=0) -- what the user sees as YouTube crashing.
    private static final long BACK_ACTION_DELAY_MS = 400;

    // Limits how often a stream of events from X / Chrome re-inspects the screen.
    private static final long FEED_CHECK_THROTTLE_MS = 300;

    // Never assumes a feed it can't see: a back-press is only fired for one that is on screen.
    private final FeedDetector feedDetector = new FeedDetector(false);
    private long lastFeedCheckUptimeMs = 0L;
    private TimeLimitManager timeLimitManager;
    private String tlCurrentPackage = null;
    private long lastActionUptimeMs = 0L;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean backActionScheduled = false;
    private ScrollTracker scrollTracker;
    private boolean trackMode = false;

    // Held as a field: SharedPreferences only keeps a weak reference to listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener modeListener = (prefs, key) -> {
        if (ShortFormMode.KEY_MODE.equals(key)) applyMode();
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        timeLimitManager = TimeLimitManager.getInstance(this);
        scrollTracker = new ScrollTracker(this);
        ShortFormMode.prefs(this).registerOnSharedPreferenceChangeListener(modeListener);
        applyMode();
        timeLimitManager.setLimitListener(pkg -> {
            Log.d(TAG, "Auto-block requested for: " + pkg);
            // Launch full-screen BlockingActivity when limit reached
            Intent i = new Intent(this, BlockingActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("pkg", pkg);
            startActivity(i);
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;

        String packageName = pkg.toString();

        // Inform TimeLimitManager about package foreground/background changes
        if (timeLimitManager != null) {
            if (tlCurrentPackage == null || !tlCurrentPackage.equals(packageName)) {
                if (tlCurrentPackage != null) {
                    timeLimitManager.onAppBackground();
                }
                tlCurrentPackage = packageName;
                timeLimitManager.onAppForeground(packageName);
            }
        }

        // In track mode short-form feeds are allowed and only tracked, so none of
        // the blocking logic below runs.
        if (trackMode) {
            if (scrollTracker != null && ScrollTracker.isTrackedPackage(packageName)) {
                scrollTracker.onAccessibilityEvent(event);
            }
            return;
        }

        // Window state changes are only subscribed to for scroll tracking; the
        // blocking logic below keeps reacting to scroll/content events only.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        // X and Chrome are used for far more than short-form video, so only their
        // actual video feeds are blocked, never scrolling in general.
        if (packageName.equals(FeedDetector.X) || packageName.equals(FeedDetector.CHROME)) {
            blockFeedIfShown();
            return;
        }

        // Check common packages where short-form video content appears
        if (!(packageName.equals("com.google.android.youtube")
                || packageName.equals("com.instagram.android")
                || packageName.contains("com.snapchat"))) {
            return;
        }

        // Skip while we're still cooling down from our own previous action.
        // Our own performAction()/performGlobalAction() calls generate further
        // accessibility events; without this guard those events re-enter here
        // immediately and fire another action, snowballing into a burst of
        // back-presses that can blow through the host app's back stack.
        long now = SystemClock.uptimeMillis();
        if (now - lastActionUptimeMs < MIN_ACTION_INTERVAL_MS) {
            return;
        }

        AccessibilityNodeInfo nodeInfo = event.getSource();
        if (nodeInfo == null) return;

        Log.d(TAG, "Accessibility event from: " + packageName);

        // Try to find a WebView or scrollable node and block scrolling
        if (findAndBlockWebViewOrScrollable(nodeInfo)) return;

        // Also search text nodes for keywords like "reels" / "shorts" / "video",
        // scoped to the app they're actually relevant to so unrelated UI text
        // (e.g. YouTube search hints containing the word "video") isn't matched.
        traverseForTextAndBlock(nodeInfo, packageName);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (timeLimitManager != null) timeLimitManager.onAppBackground();
        ShortFormMode.prefs(this).unregisterOnSharedPreferenceChangeListener(modeListener);
        if (scrollTracker != null) scrollTracker.release();
        mainHandler.removeCallbacksAndMessages(null);
        backActionScheduled = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (timeLimitManager != null) timeLimitManager.onAppBackground();
        ShortFormMode.prefs(this).unregisterOnSharedPreferenceChangeListener(modeListener);
        if (scrollTracker != null) scrollTracker.release();
        mainHandler.removeCallbacksAndMessages(null);
        backActionScheduled = false;
        super.onDestroy();
    }

    private void applyMode() {
        trackMode = ShortFormMode.TRACK.equals(ShortFormMode.get(this));
        if (trackMode) {
            // Drop any back-press queued while we were still blocking.
            mainHandler.removeCallbacksAndMessages(null);
            backActionScheduled = false;
        } else if (scrollTracker != null) {
            scrollTracker.release();
        }
        Log.d(TAG, "Short-form mode: " + (trackMode ? "track" : "block"));
    }

    // Recursively search for WebView class or any scrollable node and perform ACTION_SCROLL_BACKWARD
    private boolean findAndBlockWebViewOrScrollable(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence className = node.getClassName();
        if (className != null) {
            String cn = className.toString().toLowerCase();
            if (cn.contains("webview")) {
                Log.d(TAG, "Blocking scroll inside WebView");
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                lastActionUptimeMs = SystemClock.uptimeMillis();
                return true;
            }
        }

        try {
            if (node.isScrollable()) {
                Log.d(TAG, "Blocking scroll on scrollable node: " + (className != null ? className : "unknown"));
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                lastActionUptimeMs = SystemClock.uptimeMillis();
                return true;
            }
        } catch (Exception e) {
            // Some nodes may throw when calling isScrollable(); ignore and continue
            Log.d(TAG, "isScrollable check failed: " + e.getMessage());
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean blocked = findAndBlockWebViewOrScrollable(child);
                // Do NOT call child.recycle() here; parent AccessibilityNodeInfo owns children in this context
                if (blocked) return true;
            }
        }

        return false;
    }

    // Recursively search for text hints and call the appropriate block actions.
    // Keyword matching is scoped to the package it's relevant to, since generic
    // words like "video" appear throughout unrelated UI (e.g. YouTube search
    // hints, descriptions) and would otherwise trigger a back-press constantly.
    private void traverseForTextAndBlock(AccessibilityNodeInfo node, String packageName) {
        if (node == null) return;

        CharSequence text = node.getText();
        if (text != null) {
            String t = text.toString().toLowerCase();
            if (packageName.equals("com.instagram.android") && t.contains("reels")) {
                blockReels(node);
                return;
            }
            if (packageName.equals("com.google.android.youtube") && t.contains("shorts")) {
                blockShorts(node);
                return;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseForTextAndBlock(child, packageName);
            }
        }
    }


    private void blockReels(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("reels")) {
            scheduleBack("Instagram Reels");
        }
    }

    private void blockShorts(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("shorts")) {
            scheduleBack("YouTube Shorts");
        }
    }

    // Leaves the screen if it is showing a short-form feed (X's video viewer, or
    // Reels / Shorts / X videos / TikTok opened in Chrome).
    private void blockFeedIfShown() {
        long now = SystemClock.uptimeMillis();
        if (backActionScheduled
                || now - lastActionUptimeMs < MIN_ACTION_INTERVAL_MS
                || now - lastFeedCheckUptimeMs < FEED_CHECK_THROTTLE_MS) {
            return;
        }
        lastFeedCheckUptimeMs = now;
        FeedDetector.Feed feed = feedDetector.detect(getRootInActiveWindow(), false);
        if (feed != null) scheduleBack("short-form feed (" + feed.app + ")");
    }

    // Fires GLOBAL_ACTION_BACK after a short settle delay instead of immediately,
    // and coalesces repeated detections into a single pending action, so we
    // never interrupt a screen/fragment transition that's still in flight.
    private void scheduleBack(String reason) {
        if (backActionScheduled) return;
        backActionScheduled = true;
        mainHandler.postDelayed(() -> {
            backActionScheduled = false;
            performGlobalAction(GLOBAL_ACTION_BACK);
            lastActionUptimeMs = SystemClock.uptimeMillis();
            Log.d(TAG, "Blocked " + reason + " (after settle delay)");
        }, BACK_ACTION_DELAY_MS);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }
}
