package com.codebuzz.app.unshort;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

public class BlockingActivity extends AppCompatActivity {
    private static final long EXTRA_MS = 5 * 60 * 1000; // 5 minutes
    private static final int DEFAULT_EXTRA_USES = 3;
    private static final String EXTRA_USES_PREF = "extra_uses_"; // + yyyyMMdd + _ + pkg

    private String pkg;
    private TextView titleTv;
    private TextView messageTv;
    private TextView countdownTv;
    private Button add5Btn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateCountdown();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Consume Back so the block screen can't be dismissed. A callback rather than
        // onKeyDown(KEYCODE_BACK): from targetSdk 36, back gestures no longer arrive
        // as key events.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Intentionally empty.
            }
        });
        // Make full-screen and show over lock & turn screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

        setContentView(R.layout.activity_blocking);

        titleTv = findViewById(R.id.block_title);
        messageTv = findViewById(R.id.block_message);
        countdownTv = findViewById(R.id.block_countdown);
        add5Btn = findViewById(R.id.block_add5);

        Intent i = getIntent();
        pkg = i != null ? i.getStringExtra("pkg") : null;
        if (pkg == null) pkg = "app";

        titleTv.setText(getString(R.string.focusguard_block_title));
        messageTv.setText(getString(R.string.focusguard_block_message));

        updateAddButtonState();

        add5Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (consumeExtraUse(pkg)) {
                    TimeLimitManager.getInstance(BlockingActivity.this).grantExtraTime(pkg, EXTRA_MS);
                    // close overlay after granting
                    finish();
                }
                updateAddButtonState();
            }
        });

        handler.post(tickRunnable);
    }


    private void updateAddButtonState() {
        SharedPreferences prefs = getSharedPreferences("time_limits_prefs", Context.MODE_PRIVATE);
        int remaining = prefs.getInt(EXTRA_USES_PREF + TimeLimitManager.todayStamp() + "_" + pkg, DEFAULT_EXTRA_USES);
        add5Btn.setText(getString(R.string.add_5_min_button, remaining));
        add5Btn.setEnabled(remaining > 0);
    }

    private boolean consumeExtraUse(String pkg) {
        SharedPreferences prefs = getSharedPreferences("time_limits_prefs", Context.MODE_PRIVATE);
        String key = EXTRA_USES_PREF + TimeLimitManager.todayStamp() + "_" + pkg;
        int remaining = prefs.getInt(key, DEFAULT_EXTRA_USES);
        if (remaining <= 0) return false;
        prefs.edit().putInt(key, remaining - 1).apply();
        return true;
    }

    private void updateCountdown() {
        long now = System.currentTimeMillis();
        // countdown to midnight (reset time)
        long nextMidnight = getNextMidnightMillis();
        long diff = nextMidnight - now;
        if (diff < 0) diff = 0;
        long s = diff / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        String text = String.format(getString(R.string.reset_countdown_format), h, m, sec);
        countdownTv.setText(text);
    }

    private long getNextMidnightMillis() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.add(java.util.Calendar.DAY_OF_YEAR, 1);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tickRunnable);
        super.onDestroy();
    }
}
