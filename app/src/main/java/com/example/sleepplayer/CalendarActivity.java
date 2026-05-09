package com.example.sleepplayer;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * Zeigt einen Monatskalender als Heatmap der Meditations-Sitzungen.
 *
 * Farbkodierung:
 *   0 Sitzungen → schwarz (#1C1C2E)
 *   1 Sitzung   → dunkelgrün
 *   2 Sitzungen → mittelgrün
 *   3+ Sitzungen → hellgrün
 */
public class CalendarActivity extends AppCompatActivity {

    private static final int COLOR_NONE   = 0xFF1C1C2E;
    private static final int COLOR_ONE    = 0xFF1B5E20;
    private static final int COLOR_TWO    = 0xFF388E3C;
    private static final int COLOR_THREE  = 0xFF81C784;
    private static final int COLOR_TODAY_BORDER = 0xFFFFFFFF;

    private static final String[] DAY_LABELS = {"Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"};

    private MeditationLog meditationLog;
    private LinearLayout llDayHeaders;
    private LinearLayout llCalendarGrid;
    private LinearLayout llLegend;
    private TextView tvMonthYear;
    private Button btnPrevMonth;
    private Button btnNextMonth;

    private int displayYear;
    private int displayMonth; // 1–12

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calendar);

        meditationLog = new MeditationLog(this);

        llDayHeaders   = findViewById(R.id.llDayHeaders);
        llCalendarGrid = findViewById(R.id.llCalendarGrid);
        llLegend       = findViewById(R.id.llLegend);
        tvMonthYear    = findViewById(R.id.tvMonthYear);
        btnPrevMonth   = findViewById(R.id.btnPrevMonth);
        btnNextMonth   = findViewById(R.id.btnNextMonth);

        LocalDate today = LocalDate.now();
        displayYear  = today.getYear();
        displayMonth = today.getMonthValue();

        findViewById(R.id.btnCalendarBack).setOnClickListener(v -> finish());
        btnPrevMonth.setOnClickListener(v -> {
            if (--displayMonth < 1) { displayMonth = 12; displayYear--; }
            buildCalendar();
        });
        btnNextMonth.setOnClickListener(v -> {
            if (++displayMonth > 12) { displayMonth = 1; displayYear++; }
            buildCalendar();
        });

        buildDayHeaders();
        buildLegend();
        buildCalendar();
    }

    // ===== Aufbau =====

    private void buildDayHeaders() {
        llDayHeaders.removeAllViews();
        for (String label : DAY_LABELS) {
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams p =
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(p);
            tv.setGravity(Gravity.CENTER);
            tv.setText(label);
            tv.setTextColor(0x9903DAC6); // halbtr. türkis
            tv.setTextSize(12f);
            tv.setTypeface(null, Typeface.BOLD);
            llDayHeaders.addView(tv);
        }
    }

    private void buildCalendar() {
        llCalendarGrid.removeAllViews();

        // Monatsname aktualisieren
        YearMonth ym = YearMonth.of(displayYear, displayMonth);
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN);
        // Erster Buchstabe groß
        monthName = monthName.substring(0, 1).toUpperCase() + monthName.substring(1);
        tvMonthYear.setText(monthName + " " + displayYear);

        LocalDate today = LocalDate.now();
        int daysInMonth   = ym.lengthOfMonth();
        // getDayOfWeek().getValue(): 1=Mo, 7=So → Offset für 0-basierte Wochenspalten
        int startOffset   = ym.atDay(1).getDayOfWeek().getValue() - 1; // 0=Mo … 6=So
        int totalCells    = startOffset + daysInMonth;
        int rows          = (int) Math.ceil(totalCells / 7.0);

        int cellSizePx = dpToPx(44);

        for (int row = 0; row < rows; row++) {
            LinearLayout weekRow = new LinearLayout(this);
            weekRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dpToPx(3), 0, dpToPx(3));
            weekRow.setLayoutParams(rowParams);

            for (int col = 0; col < 7; col++) {
                int cellIndex = row * 7 + col;
                int day = cellIndex - startOffset + 1;

                LinearLayout.LayoutParams cellParams =
                        new LinearLayout.LayoutParams(0, cellSizePx, 1f);
                cellParams.setMargins(dpToPx(2), 0, dpToPx(2), 0);

                if (day < 1 || day > daysInMonth) {
                    // Leere Zelle
                    View empty = new View(this);
                    empty.setLayoutParams(cellParams);
                    weekRow.addView(empty);
                } else {
                    LocalDate cellDate = LocalDate.of(displayYear, displayMonth, day);
                    boolean isToday    = cellDate.equals(today);
                    boolean isFuture   = cellDate.isAfter(today);
                    int count          = isFuture ? 0 : meditationLog.getCount(displayYear, displayMonth, day);

                    TextView cell = new TextView(this);
                    cell.setLayoutParams(cellParams);
                    cell.setGravity(Gravity.CENTER);
                    cell.setText(String.valueOf(day));
                    cell.setTextSize(13f);
                    cell.setTypeface(null, isToday ? Typeface.BOLD : Typeface.NORMAL);

                    // Hintergrund-Kreis
                    GradientDrawable bg = new GradientDrawable();
                    bg.setShape(GradientDrawable.OVAL);
                    bg.setColor(isFuture ? 0xFF0F0F1E : colorForCount(count));
                    if (isToday) bg.setStroke(dpToPx(2), COLOR_TODAY_BORDER);
                    cell.setBackground(bg);

                    // Textfarbe
                    if (isFuture) {
                        cell.setTextColor(0xFF444466);
                    } else if (count > 0) {
                        cell.setTextColor(Color.WHITE);
                    } else {
                        cell.setTextColor(0xFF607080);
                    }

                    weekRow.addView(cell);
                }
            }
            llCalendarGrid.addView(weekRow);
        }
    }

    private void buildLegend() {
        llLegend.removeAllViews();
        addLegendItem("0×", COLOR_NONE);
        addLegendItem("1×", COLOR_ONE);
        addLegendItem("2×", COLOR_TWO);
        addLegendItem("3×+", COLOR_THREE);
    }

    private void addLegendItem(String label, int color) {
        int squareSize = dpToPx(16);
        int margin     = dpToPx(6);

        // Farbiges Quadrat
        View square = new View(this);
        LinearLayout.LayoutParams sqP =
                new LinearLayout.LayoutParams(squareSize, squareSize);
        sqP.setMargins(dpToPx(12), 0, dpToPx(4), 0);
        square.setLayoutParams(sqP);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(3));
        bg.setColor(color);
        square.setBackground(bg);

        // Label
        TextView tv = new TextView(this);
        LinearLayout.LayoutParams tvP =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        tvP.setMargins(0, 0, margin, 0);
        tv.setLayoutParams(tvP);
        tv.setText(label);
        tv.setTextColor(0xAAFFFFFF);
        tv.setTextSize(12f);

        llLegend.addView(square);
        llLegend.addView(tv);
    }

    // ===== Hilfsmethoden =====

    private int colorForCount(int count) {
        if (count >= 3) return COLOR_THREE;
        if (count == 2) return COLOR_TWO;
        if (count == 1) return COLOR_ONE;
        return COLOR_NONE;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}


