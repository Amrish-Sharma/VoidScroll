package com.codebuzz.app.unshort;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Share target: when a user shares a link/post out of a distracting app (e.g. "Share" on an
 * Instagram Reel), this identifies the sending app via the referrer and adds it to the
 * Social Media category so FocusGuard's limits apply to it going forward.
 */
public class QuickBlockActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_SEND.equals(intent.getAction())) {
            String pkg = getSendingPackage();
            if (pkg != null) {
                TimeLimitManager.getInstance(this).setAppCategory(pkg, TimeLimitManager.CATEGORY_SOCIAL);
                Toast.makeText(this, getAppLabel(pkg) + " added to Social Media limits", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Couldn't detect the source app to block", Toast.LENGTH_SHORT).show();
            }
        }
        finish();
    }

    private String getSendingPackage() {
        Uri referrer = getReferrer();
        if (referrer == null || !"android-app".equals(referrer.getScheme())) return null;
        return referrer.getHost();
    }

    private String getAppLabel(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}

