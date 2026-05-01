package com.example.sleepplayer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Steuert die 4-7-8 Atemübung.
 *
+ * Ablauf:
 *   1. Intro-Dreiklang (C-Dur Arpeggio + Akkord)
 *   2. 5 Sekunden Pause zum Einrichten
 *   3. Einatmen  – hoher Ton  (C5, 528 Hz) – 4 s
 *   4. Halten    – mittlerer Ton (G4, 396 Hz) – 7 s
 *   5. Ausatmen  – tiefer Ton (C4, 264 Hz) – 8 s
 *   → Schritt 3–5 wiederholen bis 5 Minuten vergangen
 *   6. Abschluss-Dreiklang (Arpeggio + Akkord)
 *   7. Callback → PlaybackService wechselt in Schlafmodus
 *
 * Töne: Sinuswelle mit Obertönen (Singing-Bowl-Charakter),
 *       exponentieller Decay + zwei Sekundenechos.
 */
public class MeditationController {

    private static final String TAG = "MeditationController";

    private static final int  SAMPLE_RATE            = 22050; // 44100/2 – reicht für 264–528 Hz, 4× schnellere Berechnung
    private static final long MEDITATION_DURATION_MS = 5 * 60 * 1000L;

    // --- Frequenzen Atem-Phasen ---
    private static final float FREQ_INHALE = 528f;  // C5 – hoch  (einatmen)
    private static final float FREQ_HOLD   = 396f;  // G4 – mittel (halten)
    private static final float FREQ_EXHALE = 264f;  // C4 – tief  (ausatmen)

    // --- C-Dur Dreiklang (Intro + Abschluss) ---
    private static final float FREQ_C4 = 264f;
    private static final float FREQ_E4 = 330f;
    private static final float FREQ_G4 = 396f;

    // --- Phasendauern ---
    public static final long DUR_INHALE_MS = 4_000L;
    public static final long DUR_HOLD_MS   = 7_000L;
    public static final long DUR_EXHALE_MS = 8_000L;

    /** Gesamtpufferlänge inkl. Echo-Reflexionen. Kurz genug für schnelle Berechnung. */
    private static final int TONE_TOTAL_MS = 2_500;

    private static final double ECHO_AMP_1 = 0.38;
    private static final double ECHO_AMP_2 = 0.16;

    /**
     * Basis-Abklingkonstante (wird pro Frequenz skaliert).
     * Tiefe Töne klingen länger nach (wie Gitarren-Bassaiten).
     */
    private static final double DECAY_K_BASE = 1.6;

    // --- Öffentliche Typen ---

    public enum Phase { INHALE, HOLD, EXHALE, FINISHED }

    public interface MeditationCallback {
        void onPhaseChanged(Phase phase, int cycleNumber);
        void onMeditationFinished();
    }

    // --- Felder ---

    private final MeditationCallback callback;
    private final Handler             handler       = new Handler(Looper.getMainLooper());
    // CachedThreadPool: jeder Ton bekommt seinen eigenen Thread → kein Stau durch Thread.sleep
    private final ExecutorService     audioExecutor = Executors.newCachedThreadPool();

    private volatile boolean running     = false;
    private long             startTimeMs = 0;
    private int              cycleCount  = 0;
    private float            volume      = 0.5f;

    /** Aktuell spielende AudioTracks – werden bei stop() sofort gestoppt. */
    private final CopyOnWriteArrayList<AudioTrack> activeTracks = new CopyOnWriteArrayList<>();

    /**
     * Cache für vorberechnete Samples (Key = Frequenz-Array als String).
     * Verhindert wiederholte schwere Berechnung bei jedem Ton-Aufruf.
     */
    private final ConcurrentHashMap<String, short[]> sampleCache = new ConcurrentHashMap<>();

    // ===== Konstruktor =====

    public MeditationController(MeditationCallback callback) {
        this.callback = callback;
    }

    // ===== Öffentliche API =====

    /** Startet die Atemübung: Samples vorberechnen → Intro → 5 s Pause → erste Atemphase. */
    public void start(float vol) {
        if (running) return;
        this.volume = Math.max(0.05f, Math.min(1.0f, vol));
        running     = true;
        startTimeMs = System.currentTimeMillis();
        cycleCount  = 0;
        sampleCache.clear();
        Log.d(TAG, "Meditation gestartet (volume=" + this.volume + ")");

        // Alle benötigten Samples VORAB auf einem Background-Thread berechnen,
        // damit die Wiedergabe danach sofort und ohne Verzögerung starten kann.
        audioExecutor.execute(() -> {
            Log.d(TAG, "Vorberechnung der Samples...");
            cachedSamples(FREQ_C4);
            cachedSamples(FREQ_E4);
            cachedSamples(FREQ_G4);
            cachedSamples(FREQ_INHALE);
            cachedSamples(FREQ_HOLD);
            cachedSamples(FREQ_EXHALE);
            cachedChordSamples(new float[]{FREQ_C4, FREQ_E4, FREQ_G4});
            Log.d(TAG, "Vorberechnung abgeschlossen");

            // Intro auf dem Main-Thread starten (Samples sind jetzt im Cache)
            handler.post(() -> {
                if (!running) return;
                playTone(FREQ_C4);
                handler.postDelayed(() -> playTone(FREQ_E4),                                  600);
                handler.postDelayed(() -> playTone(FREQ_G4),                                1_200);
                handler.postDelayed(() -> playChord(new float[]{FREQ_C4, FREQ_E4, FREQ_G4}), 2_000);
                handler.postDelayed(this::scheduleInhale,                                   5_000);
            });
        });
    }

    /** Stoppt die Übung sofort und bricht alle laufenden Töne ab. */
    public void stop() {
        Log.d(TAG, "Meditation gestoppt");
        running = false;
        handler.removeCallbacksAndMessages(null);
        // Alle aktiven AudioTracks sofort stoppen
        for (AudioTrack track : activeTracks) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
        }
        activeTracks.clear();
    }

    /** Gibt zurück ob die Übung gerade läuft. */
    public boolean isRunning() {
        return running;
    }

    /** Gesamtdauer eines Zyklus in ms. */
    public static long getCycleDurationMs() {
        return DUR_INHALE_MS + DUR_HOLD_MS + DUR_EXHALE_MS;
    }

    // ===== Private Scheduling-Methoden =====

    /** Einatmen – hoher Ton (C5), 4 s. */
    private void scheduleInhale() {
        if (!running) return;
        if (System.currentTimeMillis() - startTimeMs >= MEDITATION_DURATION_MS) {
            playEndSequence();
            return;
        }
        cycleCount++;
        Log.d(TAG, "Zyklus " + cycleCount + " – Einatmen");
        playTone(FREQ_INHALE);
        if (callback != null) callback.onPhaseChanged(Phase.INHALE, cycleCount);
        handler.postDelayed(this::scheduleHold, DUR_INHALE_MS);
    }

    /** Halten – mittlerer Ton (G4), 7 s. */
    private void scheduleHold() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Halten");
        playTone(FREQ_HOLD);
        if (callback != null) callback.onPhaseChanged(Phase.HOLD, cycleCount);
        handler.postDelayed(this::scheduleExhale, DUR_HOLD_MS);
    }

    /** Ausatmen – tiefer Ton (C4), 8 s. */
    private void scheduleExhale() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Ausatmen");
        playTone(FREQ_EXHALE);
        if (callback != null) callback.onPhaseChanged(Phase.EXHALE, cycleCount);
        handler.postDelayed(this::scheduleInhale, DUR_EXHALE_MS);
    }

    /** Abschluss: Arpeggio + Akkord, dann Callback → Schlafmodus. */
    private void playEndSequence() {
        running = false;
        Log.d(TAG, "Abschluss-Sequenz");

        playTone(FREQ_C4);
        handler.postDelayed(() -> playTone(FREQ_E4),                              600);
        handler.postDelayed(() -> playTone(FREQ_G4),                            1_200);
        handler.postDelayed(() -> playChord(new float[]{FREQ_C4, FREQ_E4, FREQ_G4}), 2_000);

        // Callback nach Arpeggio + Akkord-Ausklingen
        handler.postDelayed(() -> {
            if (callback != null) {
                callback.onPhaseChanged(Phase.FINISHED, cycleCount);
                callback.onMeditationFinished();
            }
        }, 5_500);
    }

    // ===== Ton-Generierung =====

    private void playTone(float frequency) {
        final float vol = this.volume;
        audioExecutor.execute(() -> playPcm(cachedSamples(frequency), vol));
    }

    private void playChord(float[] frequencies) {
        final float vol = this.volume;
        audioExecutor.execute(() -> playPcm(cachedChordSamples(frequencies), vol));
    }

    /** Gibt gecachte Samples für einen einzelnen Ton zurück (berechnet bei Bedarf). */
    private short[] cachedSamples(float frequency) {
        String key = String.valueOf(frequency);
        return sampleCache.computeIfAbsent(key,
                k -> generateChordSamples(new float[]{frequency}));
    }

    /** Gibt gecachte Samples für einen Akkord zurück (berechnet bei Bedarf). */
    private short[] cachedChordSamples(float[] frequencies) {
        String key = Arrays.toString(frequencies);
        return sampleCache.computeIfAbsent(key, k -> generateChordSamples(frequencies));
    }

    /** Gemeinsame AudioTrack-Wiedergabe. */
    private void playPcm(short[] samples, float vol) {
        AudioTrack track = null;
        try {
            int numSamples = samples.length;
            int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, numSamples * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            track.write(samples, 0, numSamples);
            track.setVolume(vol);
            activeTracks.add(track);
            track.play();
            Thread.sleep(TONE_TOTAL_MS + 80L);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Abbruch durch stop() – kein Fehler
        } catch (Exception e) {
            Log.e(TAG, "Fehler bei PCM-Wiedergabe", e);
        } finally {
            if (track != null) {
                activeTracks.remove(track);
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Erzeugt akustisch-gitarrenartige PCM-Samples für einen oder mehrere Töne.
     *
     * Klang-Modell (angelehnt an Akustikgitarre / gezupfte Saite):
     *   - 6 Obertöne mit abnehmenden Amplituden (0.42, 0.24, 0.14, 0.09, 0.06, 0.05)
     *   - Höhere Obertöne klingen schneller ab (partialDecay × n)
     *   - Frequenzabhängiger Gesamt-Decay: tiefe Töne länger (Bassaite), hohe kürzer
     *
     * Echo-Modell (4 unregelmäßige Reflexionen, natürlicher Raumklang):
     *   +750 ms  × 0.42  – erste starke Reflexion
     *   +1550 ms × 0.22  – zweite Reflexion
     *   +2300 ms × 0.10  – dritte Reflexion (ferne Wand)
     *   +3100 ms × 0.04  – viertes, kaum hörbares Nachklingen
     */
    private static short[] generateChordSamples(float[] frequencies) {
        int      numSamples    = SAMPLE_RATE * TONE_TOTAL_MS / 1000;
        double[] buf           = new double[numSamples];
        int      attackSamples = (int) (SAMPLE_RATE * 0.008); // 8 ms – Gitarren-Anschlag

        // Echo-Abstände (3 Reflexionen – ausreichend für natürlichen Klang)
        int[]    echoOffsets = {
                0,
                (int) (SAMPLE_RATE * 0.600),
                (int) (SAMPLE_RATE * 1.300)
        };
        double[] echoAmps = {1.0, ECHO_AMP_1, ECHO_AMP_2};

        // 4 Obertöne (reicht für warmen Gitarren-Charakter, deutlich schneller)
        double[] harmonicAmps = {0.45, 0.28, 0.16, 0.11};

        for (float freq : frequencies) {
            // Frequenzabhängiger Decay: tiefe Töne langsamer (Bassaite ≈ 1.0), hohe schneller
            // Referenz: 264 Hz → k=1.0 × BASE; 528 Hz → k=1.6 × BASE
            double freqFactor = 0.7 + (freq / 264f) * 0.45;
            double decayK     = DECAY_K_BASE * freqFactor;

            for (int e = 0; e < echoOffsets.length; e++) {
                int    offset   = echoOffsets[e];
                double echoAmp  = echoAmps[e];

                for (int i = offset; i < numSamples; i++) {
                    int    li    = i - offset;
                    double t     = (double) li / SAMPLE_RATE;
                    double tDecay = Math.max(0, t - (double) attackSamples / SAMPLE_RATE);

                    // Globale Hüllkurve: kurzes Attack → exp. Decay
                    double env;
                    if (li < attackSamples) {
                        env = (double) li / attackSamples;
                    } else {
                        env = Math.exp(-decayK * tDecay);
                    }

                    // Oberton-Summe: jeder Partial klingt etwas schneller ab (× 1 + n*0.4)
                    double wave = 0.0;
                    for (int n = 1; n <= harmonicAmps.length; n++) {
                        double partialDecay = Math.exp(-decayK * tDecay * (1.0 + (n - 1) * 0.4));
                        double angle = 2.0 * Math.PI * freq * n * t;
                        wave += harmonicAmps[n - 1] * Math.sin(angle) * partialDecay;
                    }

                    buf[i] += wave * env * echoAmp;
                }
            }
        }

        // Normalisieren (verhindert Clipping bei Akkorden / vielen Echos)
        double maxVal = 0.0;
        for (double v : buf) if (Math.abs(v) > maxVal) maxVal = Math.abs(v);
        double scale = (maxVal > 0.001) ? (0.90 / maxVal) : 0.90;

        short[] samples = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            samples[i] = (short) (buf[i] * scale * Short.MAX_VALUE);
        }
        return samples;
    }
}









