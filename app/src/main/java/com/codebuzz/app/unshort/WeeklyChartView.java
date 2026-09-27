package com.codebuzz.app.unshort;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Stacked bar chart of the last N days, one stack segment per app. Tapping a
 * day selects it; unselected days are dimmed and the selected day's total is
 * labelled above its bar.
 */
public class WeeklyChartView extends View {

    interface OnDaySelectedListener {
        void onDaySelected(int index);
    }

    interface ValueFormatter {
        String format(float value);
    }

    private static final int DIMMED_ALPHA = 90;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint totalTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];

    private final int colorTextPrimary;
    private final int colorTextMuted;

    // values[day][series], oldest day first.
    private float[][] values = new float[0][0];
    private String[] dayLabels = new String[0];
    private int[] seriesColors = new int[0];
    private ValueFormatter axisFormatter = v -> String.valueOf(Math.round(v));
    private ValueFormatter totalFormatter = axisFormatter;
    private float minAxisMax = 1f;
    @Nullable private float[] axisSteps;
    private int selected = -1;
    private OnDaySelectedListener listener;

    public WeeklyChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        colorTextPrimary = ContextCompat.getColor(context, R.color.dash_text_primary);
        colorTextMuted = ContextCompat.getColor(context, R.color.dash_text_muted);

        gridPaint.setColor(ContextCompat.getColor(context, R.color.dash_grid));
        gridPaint.setStrokeWidth(dp(1));

        axisTextPaint.setColor(colorTextMuted);
        axisTextPaint.setTextSize(sp(11));

        dayTextPaint.setTextSize(sp(12));
        dayTextPaint.setTextAlign(Paint.Align.CENTER);

        totalTextPaint.setColor(ContextCompat.getColor(context, R.color.dash_text_secondary));
        totalTextPaint.setTextSize(sp(12));
        totalTextPaint.setTextAlign(Paint.Align.CENTER);
        totalTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /**
     * @param axisFormatter  labels for the gridlines
     * @param totalFormatter label above the selected day's bar
     * @param minAxisMax     smallest top-of-axis value, so near-empty weeks don't
     *                       blow tiny values up to full height
     * @param axisSteps      ascending top-of-axis values to pick from (e.g. round
     *                       durations), or null for 1/2/5 x 10^n
     */
    void setData(float[][] values, String[] dayLabels, int[] seriesColors,
                 ValueFormatter axisFormatter, ValueFormatter totalFormatter,
                 float minAxisMax, @Nullable float[] axisSteps) {
        this.values = values;
        this.dayLabels = dayLabels;
        this.seriesColors = seriesColors;
        this.axisFormatter = axisFormatter;
        this.totalFormatter = totalFormatter;
        this.minAxisMax = minAxisMax;
        this.axisSteps = axisSteps;
        if (selected < 0 || selected >= values.length) selected = values.length - 1;
        invalidate();
    }

    void setSelected(int index) {
        selected = index;
        invalidate();
    }

    void setOnDaySelectedListener(OnDaySelectedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int days = values.length;
        if (days == 0) return;

        float axisMax = axisMax();
        String topLabel = axisFormatter.format(axisMax);
        float axisLabelWidth = axisTextPaint.measureText(topLabel);

        float left = getPaddingLeft() + axisLabelWidth + dp(8);
        float right = getWidth() - getPaddingRight();
        float top = getPaddingTop() + dp(20);                         // room for the total label
        float baseline = getHeight() - getPaddingBottom() - dp(24);   // room for day labels
        float plotH = baseline - top;
        float slotW = (right - left) / days;

        // Recessive grid: baseline, midline, top line, labelled at the left.
        for (int i = 0; i <= 2; i++) {
            float y = baseline - plotH * i / 2f;
            canvas.drawLine(left, y, right, y, gridPaint);
            String label = i == 0 ? "0" : axisFormatter.format(axisMax * i / 2f);
            canvas.drawText(label, getPaddingLeft(), y + axisTextPaint.getTextSize() / 3f, axisTextPaint);
        }

        float barW = Math.min(slotW * 0.56f, dp(28));
        float gap = dp(2);
        float corner = dp(4);

        for (int d = 0; d < days; d++) {
            float cx = left + slotW * d + slotW / 2f;
            boolean isSelected = d == selected;
            int alpha = isSelected ? 255 : DIMMED_ALPHA;

            int topSeries = -1;
            for (int s = 0; s < values[d].length; s++) {
                if (values[d][s] > 0) topSeries = s;
            }

            // Stack from the baseline up, with a gap between segments.
            float y = baseline;
            for (int s = 0; s <= topSeries; s++) {
                float v = values[d][s];
                if (v <= 0) continue;
                float h = Math.max(plotH * v / axisMax, dp(2));
                rect.set(cx - barW / 2f, y - h, cx + barW / 2f, y);
                barPaint.setColor(seriesColors[s]);
                barPaint.setAlpha(alpha);
                if (s == topSeries) {
                    // Rounded data-end on top only; flat where it meets the baseline.
                    float r = Math.min(corner, h);
                    radii[0] = radii[1] = radii[2] = radii[3] = r;
                    radii[4] = radii[5] = radii[6] = radii[7] = 0;
                    path.reset();
                    path.addRoundRect(rect, radii, Path.Direction.CW);
                    canvas.drawPath(path, barPaint);
                } else {
                    canvas.drawRect(rect, barPaint);
                }
                y -= h + gap;
            }

            if (isSelected) {
                float total = total(d);
                if (total > 0) {
                    canvas.drawText(totalFormatter.format(total), cx, y - dp(6) + gap, totalTextPaint);
                }
            }

            dayTextPaint.setColor(isSelected ? colorTextPrimary : colorTextMuted);
            dayTextPaint.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(dayLabels[d], cx, baseline + dp(18), dayTextPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int days = values.length;
        if (days == 0) return false;
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float axisLabelWidth = axisTextPaint.measureText(
                    axisFormatter.format(axisMax()));
            float left = getPaddingLeft() + axisLabelWidth + dp(8);
            float slotW = (getWidth() - getPaddingRight() - left) / days;
            int index = (int) ((event.getX() - left) / slotW);
            if (index >= 0 && index < days) {
                selected = index;
                invalidate();
                if (listener != null) listener.onDaySelected(index);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private float total(int day) {
        float sum = 0;
        for (float v : values[day]) sum += v;
        return sum;
    }

    private float maxTotal() {
        float max = 0;
        for (int d = 0; d < values.length; d++) max = Math.max(max, total(d));
        return max;
    }

    private float axisMax() {
        float x = Math.max(maxTotal(), minAxisMax);
        if (axisSteps == null) return niceCeil(x);
        for (float step : axisSteps) {
            if (step >= x) return step;
        }
        float last = axisSteps[axisSteps.length - 1];
        return (float) Math.ceil(x / last) * last;
    }

    // Rounds up to 1, 2 or 5 times a power of ten, so axis labels stay round.
    private static float niceCeil(float x) {
        double exp = Math.pow(10, Math.floor(Math.log10(x)));
        double f = x / exp;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return (float) (nice * exp);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
