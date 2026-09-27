package com.codebuzz.app.unshort;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int DAYS = 7;

    // Top-of-axis choices for the minutes chart, so the top and middle gridlines
    // land on round durations (e.g. 3h / 1.5h rather than 200m / 100m).
    private static final float[] MINUTE_AXIS_STEPS = {
            4, 10, 20, 30, 60, 120, 180, 240, 360, 480, 720, 960, 1440};

    private static final int[] APP_LABELS = {
            R.string.app_label_instagram, R.string.app_label_youtube, R.string.app_label_tiktok};
    private static final int[] APP_ROWS = {R.id.rowInstagram, R.id.rowYoutube, R.id.rowTiktok};

    // Asked for when a Focus Session starts; once granted, re-post the session's
    // notification, which was dropped while the permission was missing.
    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                TimeLimitManager tlm = TimeLimitManager.getInstance(this);
                if (granted && tlm.isFocusSessionActive()) tlm.startFocusSession();
            });

    private ScrollStats stats;
    private int[] appColors;
    private WeeklyChartView chart;
    private boolean chartShowsSwipes = false;
    private int selectedDaysAgo = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // The app targets SDK 35+, which draws edge-to-edge; keep content clear of the bars.
        View root = findViewById(R.id.dashboardRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        stats = ScrollStats.getInstance(this);
        appColors = new int[]{
                ContextCompat.getColor(this, R.color.series_instagram),
                ContextCompat.getColor(this, R.color.series_youtube),
                ContextCompat.getColor(this, R.color.series_tiktok),
        };

        findViewById(R.id.openSettingsButton).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        chart = findViewById(R.id.weeklyChart);
        chart.setOnDaySelectedListener(index -> {
            selectedDaysAgo = DAYS - 1 - index;
            renderBreakdown();
        });

        MaterialButtonToggleGroup metricToggle = findViewById(R.id.metricToggle);
        metricToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            chartShowsSwipes = checkedId == R.id.metricSwipes;
            renderChart();
        });

        buildLegend();

        Button focusSessionButton = findViewById(R.id.focusSessionButton);
        TimeLimitManager tlm = TimeLimitManager.getInstance(this);
        focusSessionButton.setOnClickListener(v -> {
            boolean active = tlm.isFocusSessionActive();
            if (active) {
                tlm.endFocusSession();
                Toast.makeText(this, "Focus Session ended", Toast.LENGTH_SHORT).show();
            } else {
                tlm.startFocusSession();
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                }
                Toast.makeText(this, "Focus Session started! Deep Work Mode ON", Toast.LENGTH_SHORT).show();
            }
            updateFocusSessionButton(focusSessionButton, !active);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        renderToday();
        renderChart();
        renderBreakdown();
        updateFocusSessionButton(findViewById(R.id.focusSessionButton),
                TimeLimitManager.getInstance(this).isFocusSessionActive());
    }

    private void renderStatus() {
        View card = findViewById(R.id.statusCard);
        TextView title = findViewById(R.id.statusTitle);
        TextView body = findViewById(R.id.statusBody);
        Button action = findViewById(R.id.statusAction);

        if (!isServiceEnabled()) {
            title.setText(R.string.dash_status_service_title);
            body.setText(R.string.dash_status_service_body);
            action.setText(R.string.dash_status_service_action);
            action.setOnClickListener(v -> AccessibilityDisclosure.showThenOpenSettings(this));
            card.setVisibility(View.VISIBLE);
        } else if (!ShortFormMode.TRACK.equals(ShortFormMode.get(this))) {
            title.setText(R.string.dash_status_block_title);
            body.setText(R.string.dash_status_block_body);
            action.setText(R.string.dash_status_block_action);
            action.setOnClickListener(v -> {
                ShortFormMode.set(this, ShortFormMode.TRACK);
                renderStatus();
            });
            card.setVisibility(View.VISIBLE);
        } else {
            card.setVisibility(View.GONE);
        }
    }

    private void renderToday() {
        int swipes = stats.totalSwipes(0);
        long timeMs = stats.totalTimeMs(0);
        long yesterdayMs = stats.totalTimeMs(1);

        ((TextView) findViewById(R.id.todaySwipes)).setText(String.valueOf(swipes));
        ((TextView) findViewById(R.id.todayTime)).setText(formatDuration(timeMs));

        TextView compare = findViewById(R.id.todayCompare);
        if (timeMs < 1000 && swipes == 0) {
            compare.setText(R.string.dash_compare_none);
        } else {
            long diff = timeMs - yesterdayMs;
            if (Math.abs(diff) < 60_000) {
                compare.setText(R.string.dash_compare_same);
            } else if (diff > 0) {
                compare.setText(getString(R.string.dash_compare_more, formatDuration(diff)));
            } else {
                compare.setText(getString(R.string.dash_compare_less, formatDuration(-diff)));
            }
        }

        TextView pace = findViewById(R.id.todayPace);
        if (swipes > 0) {
            pace.setText(getString(R.string.dash_pace, formatDuration(timeMs / swipes)));
            pace.setVisibility(View.VISIBLE);
        } else {
            pace.setVisibility(View.GONE);
        }
    }

    private void renderChart() {
        int series = ScrollStats.APPS.length;
        float[][] values = new float[DAYS][series];
        String[] labels = new String[DAYS];
        SimpleDateFormat dayName = new SimpleDateFormat("EEE", Locale.getDefault());
        StringBuilder description = new StringBuilder(getString(R.string.dash_week_title));

        for (int i = 0; i < DAYS; i++) {
            int daysAgo = DAYS - 1 - i;
            labels[i] = dayName.format(ScrollStats.dayDate(daysAgo));
            float total = 0;
            for (int s = 0; s < series; s++) {
                String app = ScrollStats.APPS[s];
                values[i][s] = chartShowsSwipes
                        ? stats.swipes(daysAgo, app)
                        : stats.timeMs(daysAgo, app) / 60_000f;
                total += values[i][s];
            }
            description.append(", ").append(labels[i]).append(' ')
                    .append(chartShowsSwipes ? Math.round(total) + " swipes" : formatDuration((long) (total * 60_000)));
        }

        if (chartShowsSwipes) {
            WeeklyChartView.ValueFormatter count = v -> String.valueOf(Math.round(v));
            chart.setData(values, labels, appColors, count, count, 10f, null);
        } else {
            chart.setData(values, labels, appColors,
                    MainActivity::formatAxisMinutes,
                    v -> formatDuration((long) (v * 60_000)),
                    4f, MINUTE_AXIS_STEPS);
        }
        chart.setSelected(DAYS - 1 - selectedDaysAgo);
        chart.setContentDescription(description);
    }

    private void renderBreakdown() {
        TextView title = findViewById(R.id.breakdownTitle);
        if (selectedDaysAgo == 0) {
            title.setText(R.string.dash_breakdown_today);
        } else if (selectedDaysAgo == 1) {
            title.setText(R.string.dash_breakdown_yesterday);
        } else {
            String day = new SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                    .format(ScrollStats.dayDate(selectedDaysAgo));
            title.setText(getString(R.string.dash_breakdown_day, day));
        }

        long dayTotalMs = stats.totalTimeMs(selectedDaysAgo);
        for (int s = 0; s < ScrollStats.APPS.length; s++) {
            String app = ScrollStats.APPS[s];
            View row = findViewById(APP_ROWS[s]);
            long ms = stats.timeMs(selectedDaysAgo, app);

            row.findViewById(R.id.appDot).setBackgroundTintList(ColorStateList.valueOf(appColors[s]));
            ((TextView) row.findViewById(R.id.appName)).setText(APP_LABELS[s]);
            ((TextView) row.findViewById(R.id.appValue)).setText(getString(R.string.dash_app_value,
                    stats.swipes(selectedDaysAgo, app), formatDuration(ms)));

            LinearProgressIndicator share = row.findViewById(R.id.appShare);
            share.setIndicatorColor(appColors[s]);
            share.setProgressCompat(dayTotalMs > 0 ? (int) (ms * 100 / dayTotalMs) : 0, true);
        }
    }

    private void buildLegend() {
        LinearLayout legend = findViewById(R.id.chartLegend);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int s = 0; s < ScrollStats.APPS.length; s++) {
            View item = inflater.inflate(R.layout.item_app_stat, legend, false);
            // Reuse the stat row's dot + name only.
            item.findViewById(R.id.appValue).setVisibility(View.GONE);
            item.findViewById(R.id.appShare).setVisibility(View.GONE);
            item.findViewById(R.id.appDot).setBackgroundTintList(ColorStateList.valueOf(appColors[s]));
            TextView name = item.findViewById(R.id.appName);
            name.setText(APP_LABELS[s]);
            name.setTextSize(12);
            name.setTextColor(ContextCompat.getColor(this, R.color.dash_text_secondary));
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(0, 0, 0, 0);
            legend.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName ours = new ComponentName(this, ScrollBlockerService.class);
        for (String s : enabled.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (ours.equals(c)) return true;
        }
        return false;
    }

    private void updateFocusSessionButton(Button btn, boolean active) {
        btn.setText(active ? R.string.dash_focus_end : R.string.dash_focus_start);
    }

    // "1h 12m", "18m", "42s"
    private static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        if (h > 0) return String.format(Locale.getDefault(), "%dh %dm", h, m);
        if (m > 0) return String.format(Locale.getDefault(), "%dm", m);
        return String.format(Locale.getDefault(), "%ds", totalSec);
    }

    // Axis labels for the minutes chart: "20m", "1h", "1.5h". Only ever called with
    // a MINUTE_AXIS_STEPS value or half of one, so these stay round.
    private static String formatAxisMinutes(float minutes) {
        if (minutes < 60) return String.format(Locale.getDefault(), "%dm", Math.round(minutes));
        float hours = minutes / 60f;
        return hours == Math.round(hours)
                ? String.format(Locale.getDefault(), "%dh", Math.round(hours))
                : String.format(Locale.getDefault(), "%.1fh", hours);
    }
}
