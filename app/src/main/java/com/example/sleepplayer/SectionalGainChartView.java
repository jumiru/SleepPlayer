package com.example.sleepplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Visualisiert sektionsweise Normalisierungs-Gains als Balkendiagramm.
 *
 * Jeder Balken repräsentiert einen 30-Sekunden-Abschnitt:
 *   - Grün  (aufwärts): leiser Abschnitt wird angehoben (gain > 0 dB)
 *   - Orange (abwärts): lauter Abschnitt wird gedämpft  (gain < 0 dB)
 *   - Grau:             kein Eingriff                   (gain ≈ 0 dB)
 *
 * X-Achse: Zeit (Sektionen)
 * Y-Achse: Gain in dB (positiv = boost, negativ = cut)
 */
public class SectionalGainChartView extends View {

    private static final float TOLERANCE_DB = 0.5f; // Bereich um 0 dB → grau

    // Farben
    private static final int COLOR_BOOST  = 0xFF4CAF50; // grün
    private static final int COLOR_CUT    = 0xFFFF9800; // orange
    private static final int COLOR_NEUTRAL= 0xFF757575; // grau
    private static final int COLOR_AXIS   = 0xFF90A4AE; // hellblaugrau
    private static final int COLOR_LABEL  = 0xFFB0BEC5;
    private static final int COLOR_BG     = 0xFF1E1E2E; // dunkler Hintergrund
    private static final int COLOR_CURRENT_POS = 0xFFE91E63; // pink = aktuelle Position

    private final Paint barPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint positionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect    = new RectF();

    /** Sektions-Daten: float[2] = [startMs, gainMultiplier] */
    private List<float[]> sections = Collections.emptyList();

    /** Aktuelle Wiedergabeposition in ms (optional, -1 = nicht anzeigen). */
    private int currentPositionMs = -1;

    /** Maximaler absoluter Gain-Wert in dB (für Y-Skalierung). */
    private float maxAbsDb = 6f; // Minimum 6 dB, wird aus Daten angepasst

    /** Padding in Pixeln. */
    private float paddingLeft;
    private float paddingRight;
    private float paddingTop;
    private float paddingBottom;

    public SectionalGainChartView(Context context) {
        super(context);
        init();
    }

    public SectionalGainChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float dp = getResources().getDisplayMetrics().density;
        paddingLeft   = 48 * dp;
        paddingRight  = 12 * dp;
        paddingTop    = 16 * dp;
        paddingBottom = 32 * dp;

        axisPaint.setColor(COLOR_AXIS);
        axisPaint.setStrokeWidth(1.5f * dp);

        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTextSize(9 * dp);
        labelPaint.setTextAlign(Paint.Align.CENTER);

        positionPaint.setColor(COLOR_CURRENT_POS);
        positionPaint.setStrokeWidth(2f * dp);
        positionPaint.setAlpha(200);

        setBackgroundColor(COLOR_BG);
    }

    /**
     * Setzt die anzuzeigenden Sektions-Gain-Daten.
     * @param sections Liste von float[2]: [sectionStartMs, gainMultiplier]
     */
    public void setSections(List<float[]> sections) {
        this.sections = sections != null ? sections : Collections.emptyList();

        // Maximalen absoluten dB-Wert ermitteln
        maxAbsDb = 6f;
        for (float[] s : this.sections) {
            float db = gainToDb(s[1]);
            maxAbsDb = Math.max(maxAbsDb, Math.abs(db));
        }
        maxAbsDb = (float) Math.ceil(maxAbsDb / 3f) * 3f + 3f; // auf nächste 3 aufrunden + Puffer

        invalidate();
    }

    /**
     * Setzt die aktuelle Wiedergabeposition (für die Positionsmarkierung).
     * @param positionMs Position in ms, oder -1 zum Ausblenden.
     */
    public void setCurrentPosition(int positionMs) {
        this.currentPositionMs = positionMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (sections.isEmpty()) {
            drawEmptyState(canvas);
            return;
        }

        float w = getWidth();
        float h = getHeight();
        float chartLeft   = paddingLeft;
        float chartRight  = w - paddingRight;
        float chartTop    = paddingTop;
        float chartBottom = h - paddingBottom;
        float chartW = chartRight - chartLeft;
        float chartH = chartBottom - chartTop;
        float zeroY  = chartTop + chartH / 2f; // Y-Koordinate für 0 dB

        drawGrid(canvas, chartLeft, chartRight, chartTop, chartBottom, zeroY);
        drawBars(canvas, chartLeft, chartW, chartTop, chartBottom, zeroY, chartH);
        drawAxes(canvas, chartLeft, chartRight, chartTop, chartBottom, zeroY);
        drawTimeLabels(canvas, chartLeft, chartW, chartBottom);
        drawYLabels(canvas, chartLeft, chartTop, chartBottom, zeroY, chartH);
        drawCurrentPosition(canvas, chartLeft, chartW, chartTop, chartBottom);
        drawLegend(canvas, chartLeft, chartTop);
    }

    private void drawEmptyState(Canvas canvas) {
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(14 * getResources().getDisplayMetrics().density);
        canvas.drawText("Keine Sektionsdaten", getWidth() / 2f, getHeight() / 2f, labelPaint);
    }

    private void drawGrid(Canvas canvas, float left, float right, float top, float bottom, float zeroY) {
        Paint gridPaint = new Paint();
        gridPaint.setColor(0xFF2A2A3E);
        gridPaint.setStrokeWidth(1f);

        // Horizontale Gitternetzlinien bei ±3, ±6, ±9... dB
        for (float db = 3f; db <= maxAbsDb; db += 3f) {
            float yPos = dbToY(db, zeroY, (bottom - top) / 2f);
            float yNeg = dbToY(-db, zeroY, (bottom - top) / 2f);
            canvas.drawLine(left, yPos, right, yPos, gridPaint);
            canvas.drawLine(left, yNeg, right, yNeg, gridPaint);
        }
    }

    private void drawBars(Canvas canvas, float chartLeft, float chartW,
            float chartTop, float chartBottom, float zeroY, float chartH) {
        if (sections.isEmpty()) return;

        float totalMs = sections.get(sections.size() - 1)[0]
                + (sections.size() > 1
                    ? (sections.get(1)[0] - sections.get(0)[0])
                    : 30_000f);
        float barSpacing = 2f;
        float barW = chartW / sections.size() - barSpacing;
        float halfH = chartH / 2f;

        for (int i = 0; i < sections.size(); i++) {
            float startMs = sections.get(i)[0];
            float gain = sections.get(i)[1];
            float db = gainToDb(gain);

            float x = chartLeft + (startMs / totalMs) * chartW;
            float barH = Math.abs(db) / maxAbsDb * halfH;
            barH = Math.max(barH, 2f); // Mindesthöhe

            int color;
            if (Math.abs(db) < TOLERANCE_DB) {
                color = COLOR_NEUTRAL;
            } else if (db > 0) {
                color = COLOR_BOOST;  // boost: Balken nach oben
            } else {
                color = COLOR_CUT;    // cut:   Balken nach unten
            }
            barPaint.setColor(color);
            barPaint.setAlpha(210);

            if (db >= 0) {
                barRect.set(x + barSpacing / 2f, zeroY - barH, x + barW, zeroY);
            } else {
                barRect.set(x + barSpacing / 2f, zeroY, x + barW, zeroY + barH);
            }
            float dp = getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(barRect, 2 * dp, 2 * dp, barPaint);

            // dB-Wert auf Balken schreiben (nur wenn Balken groß genug)
            if (barH > 12 * dp) {
                Paint valPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                valPaint.setColor(Color.WHITE);
                valPaint.setTextSize(7 * dp);
                valPaint.setTextAlign(Paint.Align.CENTER);
                valPaint.setAlpha(180);
                String label = String.format(Locale.getDefault(), "%+.0f", db);
                float textY = db >= 0 ? zeroY - barH / 2f + 3 * dp : zeroY + barH / 2f + 4 * dp;
                canvas.drawText(label, x + barW / 2f, textY, valPaint);
            }
        }
    }

    private void drawAxes(Canvas canvas, float left, float right, float top, float bottom, float zeroY) {
        // Null-Linie
        axisPaint.setAlpha(255);
        canvas.drawLine(left, zeroY, right, zeroY, axisPaint);
        // Y-Achse
        canvas.drawLine(left, top, left, bottom, axisPaint);
    }

    private void drawTimeLabels(Canvas canvas, float chartLeft, float chartW, float chartBottom) {
        if (sections.isEmpty()) return;
        float totalMs = sections.get(sections.size() - 1)[0]
                + (sections.size() > 1
                    ? (sections.get(1)[0] - sections.get(0)[0])
                    : 30_000f);

        labelPaint.setTextAlign(Paint.Align.CENTER);
        float dp = getResources().getDisplayMetrics().density;
        // Jede n-te Sektion beschriften (damit es nicht zu eng wird)
        int step = Math.max(1, sections.size() / 8);
        for (int i = 0; i < sections.size(); i += step) {
            float startMs = sections.get(i)[0];
            float x = chartLeft + (startMs / totalMs) * chartW;
            String timeLabel = formatMs((long) startMs);
            canvas.drawText(timeLabel, x, chartBottom + 12 * dp, labelPaint);
        }
    }

    private void drawYLabels(Canvas canvas, float chartLeft, float chartTop,
            float chartBottom, float zeroY, float chartH) {
        Paint yLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        yLabelPaint.setColor(COLOR_LABEL);
        float dp = getResources().getDisplayMetrics().density;
        yLabelPaint.setTextSize(8 * dp);
        yLabelPaint.setTextAlign(Paint.Align.RIGHT);

        float halfH = chartH / 2f;
        for (float db = 0f; db <= maxAbsDb; db += 3f) {
            // Positiv
            float yPos = dbToY(db, zeroY, halfH);
            canvas.drawText(String.format(Locale.getDefault(), "%+.0fdB", db),
                    chartLeft - 3 * dp, yPos + 3 * dp, yLabelPaint);
            if (db > 0) {
                float yNeg = dbToY(-db, zeroY, halfH);
                canvas.drawText(String.format(Locale.getDefault(), "%+.0fdB", -db),
                        chartLeft - 3 * dp, yNeg + 3 * dp, yLabelPaint);
            }
        }
    }

    private void drawCurrentPosition(Canvas canvas, float chartLeft, float chartW,
            float chartTop, float chartBottom) {
        if (currentPositionMs < 0 || sections.isEmpty()) return;
        float totalMs = sections.get(sections.size() - 1)[0]
                + (sections.size() > 1
                    ? (sections.get(1)[0] - sections.get(0)[0])
                    : 30_000f);
        float x = chartLeft + (currentPositionMs / totalMs) * chartW;
        canvas.drawLine(x, chartTop, x, chartBottom, positionPaint);
    }

    private void drawLegend(Canvas canvas, float chartLeft, float chartTop) {
        float dp = getResources().getDisplayMetrics().density;
        float x = chartLeft + 4 * dp;
        float y = chartTop + 10 * dp;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(8 * dp);

        p.setColor(COLOR_BOOST);
        canvas.drawText("▲ angehoben", x, y, p);
        p.setColor(COLOR_CUT);
        canvas.drawText("▼ gedämpft", x + 70 * dp, y, p);
        p.setColor(COLOR_CURRENT_POS);
        canvas.drawText("| Position", x + 140 * dp, y, p);
    }

    // ===== Hilfsmethoden =====

    private float gainToDb(float gain) {
        if (gain <= 0) return 0f;
        return (float) (20.0 * Math.log10(gain));
    }

    /** Rechnet einen dB-Wert in eine Y-Koordinate um (positiv dB = oben). */
    private float dbToY(float db, float zeroY, float halfH) {
        return zeroY - (db / maxAbsDb) * halfH;
    }

    private String formatMs(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s = s % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }
}

