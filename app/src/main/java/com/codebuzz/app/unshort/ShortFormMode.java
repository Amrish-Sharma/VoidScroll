package com.codebuzz.app.unshort;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What the accessibility service does with short-form video feeds (Reels,
 * Shorts, TikTok, X videos): either block them outright, or let the user scroll while
 * tracking swipes and time with on-screen bubbles.
 */
final class ShortFormMode {

    static final String PREFS_NAME = "app_settings";
    static final String KEY_MODE = "short_form_mode";

    static final String BLOCK = "block";
    static final String TRACK = "track";

    private ShortFormMode() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static String get(Context context) {
        return prefs(context).getString(KEY_MODE, BLOCK);
    }

    static void set(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }
}
