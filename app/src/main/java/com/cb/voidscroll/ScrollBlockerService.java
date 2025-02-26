package com.cb.voidscroll;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

public class ScrollBlockerService extends AccessibilityService {

    private static final String TAG = "ScrollBlockerService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() != null && event.getPackageName().equals("com.android.chrome")) {
            AccessibilityNodeInfo nodeInfo = event.getSource();
            if (nodeInfo != null) {
                Log.d("ScrollBlocker", "Chrome detected - Checking WebView");

                // Detect WebView in Chrome and try to block scrolling
                for (int i = 0; i < nodeInfo.getChildCount(); i++) {
                    AccessibilityNodeInfo child = nodeInfo.getChild(i);
                    if (child != null && child.getClassName().equals("android.webkit.WebView")) {
                        Log.d("ScrollBlocker", "Blocking scroll inside WebView");
                        child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                        return;
                    }
                }
            }
        }
    }


    private void blockReels(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("reels")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            Log.d(TAG, "Blocked Instagram Reels");
        }
    }

    private void blockShorts(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("shorts")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            Log.d(TAG, "Blocked YouTube Shorts");
        }
    }

    private void blockXVideos(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("video")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            Log.d(TAG, "Blocked X Videos");
        }
    }

    private void blockChromeVideos(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains("reels")) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            Log.d(TAG, "Blocked Chrome Instagram Reels");
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility Service Interrupted");
    }
}

