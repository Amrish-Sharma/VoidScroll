package com.codebuzz.app.unshort;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

/**
 * Google Play requires a prominent in-app disclosure, and the user's explicit
 * agreement, before sending them to turn on an AccessibilityService. Every
 * "turn on the service" entry point goes through here.
 */
final class AccessibilityDisclosure {

    static final String PRIVACY_POLICY_URL = "https://amrish-sharma.github.io/VoidScroll/";

    private AccessibilityDisclosure() {}

    static void showThenOpenSettings(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.a11y_disclosure_title)
                .setMessage(R.string.a11y_disclosure_message)
                .setCancelable(false)
                .setPositiveButton(R.string.a11y_disclosure_agree, (d, w) ->
                        activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(R.string.a11y_disclosure_decline, null)
                .setNeutralButton(R.string.a11y_disclosure_policy, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL)));
                    } catch (ActivityNotFoundException ignored) {
                        // No browser installed; nothing else to do.
                    }
                })
                .show();
    }
}
