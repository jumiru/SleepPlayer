package com.example.sleepplayer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Steuert die 4-7-8 Atemübung.
 *
 * Ablauf eines Zyklus:
 *   1. Hoher Ton (C5 ≈ 528 Hz) → 4 Sekunden einatmen
 *   2. Mittlerer Ton (G4 ≈ 396 Hz) → 7 Sekunden halten
 *   3. Tiefer Ton (C4 ≈ 264 Hz) → 8 Sekunden ausatmen
 *   → Wiederholen für ~5 Minuten
 *   → Abschluss: drei aufsteigende Töne als End-Signal
 *
 * Töne werden programmatisch als Sinuswelle mit Piano-ähnlicher ADSR-Hüllkurve erzeugt.
 * Zweite + dritte Obertöne (+20%, +10%) geben einen wärmeren, gitarrenähnlichen Klang.
 */
public class MeditationController {

    private static final String TAG = "MeditationController";

    private static final int    SAMPLE_RATE           = 44100;
    private static final long   MEDITATION_DURATION_MS = 5 * 60 * 1000L; // 5 Minuten

    // Frequenzen
    private static final float FREQ_INHALE = 528f;   // C5 – hoch (einatmen)
    private static final float FREQ_HOLD   = 396f;   // G4 – mittel (halten)
    private static final float FREQ_EXHALE = 264f;   // C4 – tief (ausatmen)

    // Phasendauern (ms)
    public static final long DUR_INHALE_MS = 4_000L;
    public static final long DUR_HOLD_MS   = 7_000L;
    public static final long DUR_EXHALE_MS = 8_000L;

    /** Dauer des einzelnen "Ping"-Tons in ms. */
    private static final int TONE_DURATION_MS = 1_400;

    public enum Phase { INHALE, HOLD, EXHALE, FINISHED }

    public interface MeditationCallback {
        /** Wird aufgerufen wenn eine neue Phase beginnt (Main-Thread). */
        void onPhaseChanged(Phase phase, int cycleNumber);
        /** Wird aufgerufen wenn die Übung beendet ist (Main-Thread). */
        void onMeditationFinished();
    }

    private final MeditationCallback callback;
    private final Handler  handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running = false;
    private long startTimeMs;
    private int  cycleCount = 0;

    /** Aktuelle Wiedergabelautstärke [0.0, 1.0]. */
    private float volume = 0.5f;

    public MeditationController(MeditationCallback callback) {
        this.callback = callback;
    }

    // ===== Öffentliche API =====

    /**
     * Startet die Atemübung.
     * @param volume Lautstärke 0.0–1.0 (passend zur aktuellen Track-Lautstärke).
     */
    public void start(float volume) {
        if (running) return;
        this.volume = Math.max(0.05f, Math.min(1.0f, volume));
        running    = true;
        startTimeMs = System.currentTimeMillis();
        cycleCount  = 0;
        Log.d(TAG, "Meditation gestartet (volume=" + this.volume + ")");
        scheduleInhale();
    }

    /** Stoppt die Übung sofort (Ende des laufenden Tons wird abgewartet). */
    public void stop() {
        Log.d(TAG, "Meditation gestoppt");
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    /** Gibt zurück ob die Übung gerade läuft. */
    public boolean isRunning() {
        return running;
    }

    /** Gibt die Dauer eines Zyklus in Millisekunden zurück. */
    public static long getCycleDurationMs() {
        return DUR_INHALE_MS + DUR_HOLD_MS + DUR_EXHALE_MS;
    }

    // ===== Private Scheduling-Methoden =====

    private void scheduleInhale() {
        if (!running) return;
        // Haben wir bereits 5 Minuten erreicht?
        if (System.currentTimeMillis() - startTimeMs >= MEDITATION_DURATION_MS) {
            playEndSequence();
            return;
        }
        cycleCount++;
        Log.d(TAG, "Zyklus " + cycleCount + " – Einatmen");
        playTone(FREQ_INHALE, TONE_DURATION_MS);
        if (callback != null) callback.onPhaseChanged(Phase.INHALE, cycleCount);
        handler.postDelayed(this::scheduleHold, DUR_INHALE_MS);
    }

    private void scheduleHold() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Halten");
        playTone(FREQ_HOLD, TONE_DURATION_MS);
        if (callback != null) callback.onPhaseChanged(Phase.HOLD, cycleCount);
        handler.postDelayed(this::scheduleExhale, DUR_HOLD_MS);
    }

    private void scheduleExhale() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Ausatmen");
        playTone(FREQ_EXHALE, TONE_DURATION_MS);
        if (callback != null) callback.onPhaseChanged(Phase.EXHALE, cycleCount);
        handler.postDelayed(this::scheduleInhale, DUR_EXHALE_MS);
    }

    /** Spielt drei aufsteigende Töne als Abschluss-Signal. */
    private void playEndSequence() {
        running = false;
        Log.d(TAG, "Abschluss-Sequenz");
        playTone(FREQ_EXHALE, 600);
        handler.postDelayed(() -> playTone(FREQ_HOLD,   600), 700);
        handler.postDelayed(() -> playTone(FREQ_INHALE, 900), 1_400);
        handler.postDelayed(() -> {
            if (callback != null) {
                callback.onPhaseChanged(Phase.FINISHED, cycleCount);
                callback.onMeditationFinished();
            }
        }, 2_600);
    }

    // ===== Ton-Generierung =====

    /**
     * Generiert und spielt einen einzelnen "Piano-Ping"-Ton asynchron.
     *
     * Klang-Formel:
     *   sample = 0.70 * sin(f) + 0.20 * sin(2f) + 0.10 * sin(3f)
     * Das verleiht dem Ton eine leichte Oberton-Wärme (gitarren-/pianoähnlich).
     *
     * ADSR-Hüllkurve:
     *   Attack  30 ms   → 1.0
     *   Decay   150 ms  → 0.55
     *   Sustain         0.55
     *   Release letzte 350 ms → 0.0
     */
    private void playTone(float frequency, int durationMs) {
        final float vol = this.volume; // lokale Kopie (Thread-Sicherheit)
        audioExecutor.execute(() -> {
            try {
                int numSamples = SAMPLE_RATE * durationMs / 1000;
                short[] samples = generateSamples(frequency, numSamples);

                int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(minBuf, numSamples * 2);

                AudioTrack audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                audioTrack.write(samples, 0, numSamples);
                audioTrack.setVolume(vol);
                audioTrack.play();

                // Warten bis der Ton abgespielt ist
                Thread.sleep(durationMs + 80L);
                audioTrack.stop();
                audioTrack.release();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Fehler beim Ton-Abspielen (freq=" + frequency + ")", e);
            }
        });
    }

    /**
     * Erzeugt die PCM-Samples für einen Ton mit ADSR-Hüllkurve.
     */
    private static short[] generateSamples(float frequency, int numSamples) {
        short[] samples = new short[numSamples];

        int attackSamples  = (int) (SAMPLE_RATE * 0.030); // 30 ms
        int decaySamples   = (int) (SAMPLE_RATE * 0.150); // 150 ms
        int releaseSamples = Math.min((int) (SAMPLE_RATE * 0.350), numSamples / 3);
        double sustainLevel = 0.55;

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double angle = 2.0 * Math.PI * frequency * t;

            // Grundton + 2. + 3. Oberton
            double wave = 0.70 * Math.sin(angle)
                        + 0.20 * Math.sin(2 * angle)
                        + 0.10 * Math.sin(3 * angle);

            // ADSR
            double env;
            if (i < attackSamples) {
                env = (double) i / attackSamples;
            } else if (i < attackSamples + decaySamples) {
                double p = (double) (i - attackSamples) / decaySamples;
                env = 1.0 - (1.0 - sustainLevel) * p;
            } else if (i >= numSamples - releaseSamples) {
                double p = (double) (i - (numSamples - releaseSamples)) / releaseSamples;
                env = sustainLevel * (1.0 - p);
            } else {
                env = sustainLevel;
            }

            samples[i] = (short) (wave * env * Short.MAX_VALUE * 0.9);
        }
        return samples;
    }
}

