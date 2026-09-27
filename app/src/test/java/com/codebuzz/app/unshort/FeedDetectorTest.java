package com.codebuzz.app.unshort;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FeedDetectorTest {

    @Test
    public void recognisesWebFeeds() {
        assertEquals(ScrollStats.APP_INSTAGRAM, FeedDetector.webAppFor("instagram.com/reels/C8abc123/"));
        assertEquals(ScrollStats.APP_INSTAGRAM, FeedDetector.webAppFor("instagram.com/reel/C8abc123/"));
        assertEquals(ScrollStats.APP_INSTAGRAM, FeedDetector.webAppFor("instagram.com/someone/reel/C8abc123/"));
        assertEquals(ScrollStats.APP_YOUTUBE, FeedDetector.webAppFor("m.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals(ScrollStats.APP_X, FeedDetector.webAppFor("x.com/someone/status/1234567890/video/1"));
        assertEquals(ScrollStats.APP_X, FeedDetector.webAppFor("mobile.twitter.com/someone/status/1234567890/video/1"));
        assertEquals(ScrollStats.APP_TIKTOK, FeedDetector.webAppFor("tiktok.com/@someone/video/7300000000000000000"));
        assertEquals(ScrollStats.APP_TIKTOK, FeedDetector.webAppFor("tiktok.com/foryou"));
    }

    @Test
    public void ignoresOtherPages() {
        assertNull(FeedDetector.webAppFor("instagram.com/"));
        assertNull(FeedDetector.webAppFor("instagram.com/someone/"));
        assertNull(FeedDetector.webAppFor("m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertNull(FeedDetector.webAppFor("x.com/home"));
        assertNull(FeedDetector.webAppFor("x.com/someone/status/1234567890"));
        assertNull(FeedDetector.webAppFor("x.com/someone/status/1234567890/photo/1"));
        assertNull(FeedDetector.webAppFor("tiktok.com/legal/page/terms"));
        assertNull(FeedDetector.webAppFor("google.com/search?q=instagram.com/reels/"));
    }
}
