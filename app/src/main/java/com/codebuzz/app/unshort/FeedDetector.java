package com.codebuzz.app.unshort;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises short-form video feeds on screen: Instagram Reels, YouTube Shorts,
 * TikTok and X's swipeable video viewer in their apps, and the web versions of
 * those feeds in Chrome. Shared by blocking and tracking, so both modes agree on
 * what counts as a feed.
 */
class FeedDetector {

    static final String CHROME = "com.android.chrome";
    static final String X = "com.twitter.android";

    /** A feed found on screen. */
    static final class Feed {
        /** Dashboard app ({@link ScrollStats#APPS}) the feed belongs to. */
        final String app;
        /**
         * Identifies the video filling the feed, so a swipe shows up as a change
         * of key; null when the feed doesn't say (mid-swipe, or it isn't exposed).
         */
        final String pageKey;

        Feed(String app, String pageKey) {
            this.app = app;
            this.pageKey = pageKey;
        }
    }

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

    private static final String CHROME_URL_BAR_ID = CHROME + ":id/url_bar";

    // Web pages that are a swipeable short-form feed. Each video has its own URL
    // there, so a swipe is seen as the URL changing.
    private static final Pattern WEB_INSTAGRAM = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*instagram\\.com/(?:[^/?#]+/)?reels?/");
    private static final Pattern WEB_YOUTUBE = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*youtube\\.com/shorts/");
    private static final Pattern WEB_TIKTOK = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*tiktok\\.com/");
    private static final Pattern WEB_X = Pattern.compile(
            "^(?:[a-z0-9-]+\\.)*(?:x|twitter)\\.com/.+/video/\\d+");
    private static final Pattern URL_SCHEME = Pattern.compile("^[a-z]+://");

    // A page must fill at least this much of the feed to count as landed on,
    // so a swipe that is let go halfway and bounces back isn't counted.
    private static final float SETTLED_FRACTION = 0.8f;

    // X has no stable view ids for its video viewer, so it is recognised by its
    // shape instead: a vertical pager (nearly) as tall as the window. The home
    // timeline sits between the app bar and bottom navigation, so it stays short
    // of this, and none of its posts fills it the way a video page does.
    private static final float FULL_SCREEN_FRACTION = 0.85f;

    // Upper bound on nodes inspected per search for X's pager, to keep each
    // check cheap on deep view trees.
    private static final int MAX_NODES_VISITED = 600;

    // Chrome drops its toolbar from the view tree while a page is fullscreen. The
    // last feed seen in Chrome is kept through such gaps for up to this long.
    private static final long CHROME_URL_GRACE_MS = 5_000;

    private final boolean holdThroughHiddenToolbar;
    private Feed lastChromeFeed = null;
    private long lastChromeFeedUptimeMs = 0L;

    /**
     * @param holdThroughHiddenToolbar whether a Chrome feed is still reported for
     *                                 a while after its toolbar disappears (see
     *                                 {@link #CHROME_URL_GRACE_MS})
     */
    FeedDetector(boolean holdThroughHiddenToolbar) {
        this.holdThroughHiddenToolbar = holdThroughHiddenToolbar;
    }

    /** Maps a native app's package to its dashboard app, or null if it has no feed we know. */
    static String appFor(String packageName) {
        if (packageName == null) return null;
        switch (packageName) {
            case "com.instagram.android":
                return ScrollStats.APP_INSTAGRAM;
            case "com.google.android.youtube":
                return ScrollStats.APP_YOUTUBE;
            case "com.zhiliaoapp.musically":
            case "com.ss.android.ugc.trill":
                return ScrollStats.APP_TIKTOK;
            case X:
                return ScrollStats.APP_X;
            default:
                return null;
        }
    }

    /** Whether feeds can show up in this package at all, natively or on the web. */
    static boolean isWatchedPackage(String packageName) {
        return CHROME.equals(packageName) || appFor(packageName) != null;
    }

    /**
     * @param root         root of the active window
     * @param alreadyInFeed whether a feed was on screen at the last check; X's
     *                      viewer only has to be fully settled on a page to be
     *                      entered, not to be stayed in while a swipe animates
     * @return the feed on screen, or null if there isn't one
     */
    Feed detect(AccessibilityNodeInfo root, boolean alreadyInFeed) {
        if (root == null || root.getPackageName() == null) return null;
        String pkg = root.getPackageName().toString();

        if (CHROME.equals(pkg)) return detectWeb(root);

        String app = appFor(pkg);
        if (app == null) return null;

        for (String p : TIKTOK_PACKAGES) {
            if (p.equals(pkg)) return new Feed(app, null);
        }

        if (X.equals(pkg)) return detectXViewer(root, alreadyInFeed);

        if (findVisible(root, pkg, FEED_VIEW_IDS) == null) return null;
        AccessibilityNodeInfo pager = findVisible(root, pkg, PAGED_FEED_IDS);
        return new Feed(app, pager != null ? settledRow(pager) : null);
    }

    private Feed detectXViewer(AccessibilityNodeInfo root, boolean alreadyInFeed) {
        Rect window = new Rect();
        root.getBoundsInScreen(window);
        if (window.height() <= 0) return null;

        AccessibilityNodeInfo pager = findFullScreenVerticalPager(root, window);
        if (pager == null) return null;

        String row = settledRow(pager);
        if (row == null && !alreadyInFeed) {
            // Entering needs a page filling the pager (see FULL_SCREEN_FRACTION),
            // unless the pager's pages don't report positions at all.
            if (largestSettledPage(pager) == null) return null;
        }
        return new Feed(ScrollStats.APP_X, row);
    }

    private Feed detectWeb(AccessibilityNodeInfo root) {
        long now = SystemClock.uptimeMillis();
        String url = chromeUrl(root);
        if (url == null) {
            // Toolbar is hidden (e.g. fullscreen): assume the same page is still
            // showing for a while, without claiming any page change.
            if (holdThroughHiddenToolbar && lastChromeFeed != null
                    && now - lastChromeFeedUptimeMs <= CHROME_URL_GRACE_MS) {
                return new Feed(lastChromeFeed.app, null);
            }
            return null;
        }

        String app = webAppFor(url);
        if (app == null) {
            lastChromeFeed = null;
            return null;
        }
        lastChromeFeed = new Feed(app, url);
        lastChromeFeedUptimeMs = now;
        return lastChromeFeed;
    }

    private static String chromeUrl(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(CHROME_URL_BAR_ID);
        for (AccessibilityNodeInfo bar : bars) {
            // While being edited the bar holds what's typed, not the page's URL.
            if (bar.isFocused()) return null;
            CharSequence text = bar.getText();
            if (text == null || text.length() == 0) continue;
            String url = URL_SCHEME.matcher(text.toString().trim().toLowerCase(Locale.US)).replaceFirst("");
            return url.startsWith("www.") ? url.substring(4) : url;
        }
        return null;
    }

    static String webAppFor(String url) {
        if (WEB_INSTAGRAM.matcher(url).find()) return ScrollStats.APP_INSTAGRAM;
        if (WEB_YOUTUBE.matcher(url).find()) return ScrollStats.APP_YOUTUBE;
        if (WEB_X.matcher(url).find()) return ScrollStats.APP_X;
        Matcher tiktok = WEB_TIKTOK.matcher(url);
        // The For You feed and individual videos, not e.g. tiktok.com/legal.
        if (tiktok.find()) {
            String path = url.substring(tiktok.end());
            if (path.isEmpty() || path.startsWith("foryou") || path.startsWith("@")
                    || path.startsWith("?")) {
                return ScrollStats.APP_TIKTOK;
            }
        }
        return null;
    }

    // Breadth-first so the outermost matching pager wins over any nested list
    // inside a video page (e.g. a comments sheet).
    private static AccessibilityNodeInfo findFullScreenVerticalPager(AccessibilityNodeInfo root, Rect window) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        Rect r = new Rect();
        while (!queue.isEmpty() && visited++ < MAX_NODES_VISITED) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isScrollable() && isVerticalList(node) && node.isVisibleToUser()) {
                node.getBoundsInScreen(r);
                if (r.height() >= window.height() * FULL_SCREEN_FRACTION) return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    // Excludes horizontal pagers such as a post's photo carousel.
    private static boolean isVerticalList(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo.CollectionInfo info = node.getCollectionInfo();
        return info != null && info.getColumnCount() <= 1 && info.getRowCount() != 1;
    }

    /** The page filling most of the pager, if it fills enough to be landed on. */
    private static AccessibilityNodeInfo largestSettledPage(AccessibilityNodeInfo pager) {
        Rect pagerBounds = new Rect();
        pager.getBoundsInScreen(pagerBounds);
        if (pagerBounds.height() <= 0) return null;

        AccessibilityNodeInfo page = null;
        int pageHeight = 0;
        Rect r = new Rect();
        for (int i = 0; i < pager.getChildCount(); i++) {
            AccessibilityNodeInfo child = pager.getChild(i);
            if (child == null) continue;
            child.getBoundsInScreen(r);
            if (!r.intersect(pagerBounds)) continue;
            if (r.height() > pageHeight) {
                pageHeight = r.height();
                page = child;
            }
        }
        return pageHeight >= pagerBounds.height() * SETTLED_FRACTION ? page : null;
    }

    private static String settledRow(AccessibilityNodeInfo pager) {
        AccessibilityNodeInfo page = largestSettledPage(pager);
        if (page == null) return null;
        AccessibilityNodeInfo.CollectionItemInfo item = page.getCollectionItemInfo();
        return item != null ? "row:" + item.getRowIndex() : null;
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
}
