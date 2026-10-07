package com.codebuzz.app.unshort;

import android.content.Context;

/**
 * How long a scrolling session may run before the on-screen bubbles turn from
 * green to red. Nothing is blocked when it is passed; it is only a nudge.
 */
final class ScrollLimit {

    static final int DEFAULT_MINUTES = 10;
    static final int MIN_MINUTES = 1;
    static final int MAX_MINUTES = 600;

    private static final String KEY_MINUTES = "scroll_limit_minutes";

    private ScrollLimit() {}

    static int getMinutes(Context context) {
        return ShortFormMode.prefs(context).getInt(KEY_MINUTES, DEFAULT_MINUTES);
    }

    static long getMs(Context context) {
        return getMinutes(context) * 60_000L;
    }

    static void setMinutes(Context context, int minutes) {
        int clamped = Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
        ShortFormMode.prefs(context).edit().putInt(KEY_MINUTES, clamped).apply();
    }
}
