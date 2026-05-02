package com.example.sleepplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.media.audiofx.EnvironmentalReverb;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Steuert die 4-7-8 Atemübung mit aufgenommenen Audio-Samples + Reverb-Effekt.
 *
 * Ablauf:
 *   1. Intro-Chord (meditation_chord) + 5 s Einrichtungspause
 *   2. Einatmen  (meditation_inhale) – 4 s
 *   3. Halten    (meditation_hold)   – 7 s
 *   4. Ausatmen  (meditation_exhale) – 8 s
 *   → Schritt 2–4 wiederholen bis 5 Minuten vergangen
 *   5. Abschluss-Chord (meditation_chord)
 *   6. Callback → PlaybackService wechselt in Schlafmodus
 *
 * Samples in res/raw/:
 *   meditation_chord.m4a / meditation_inhale.m4a /
 *   meditation_hold.m4a  / meditation_exhale.m4a
 */
public class MeditationController {

    private static final String TAG = "MeditationController";

    private static final long MEDITATION_DURATION_MS = 5 * 60 * 1000L;

    public static final long DUR_INHALE_MS = 4_000L;
    public static final long DUR_HOLD_MS   = 7_000L;
    public static final long DUR_EXHALE_MS = 8_000L;

    private static final long INTRO_PAUSE_MS = 5_000L;
    private static final long OUTRO_PAUSE_MS = 3_000L;

    // ===== Reverb-Einstellungen (großer, warmer Saal) =====
    // Werte: Pegel in Millibel (mB), 0 mB = 0 dB; -9000 mB = -90 dB (Minimum = aus)
    private static final short REVERB_ROOM_LEVEL       = -1000;  // Gesamt-Hallpegel
    private static final short REVERB_ROOM_HF_LEVEL    = -2000;  // Dämpfung hoher Frequenzen (warm)
    private static final int   REVERB_DECAY_TIME       = 3500;   // Nachhallzeit in ms
    private static final short REVERB_DECAY_HF_RATIO   =  500;   // 50 % HF-Ratio (weich)
    private static final short REVERB_REFLECTIONS_LVL  = -2800;  // Erste Reflexionen
    private static final int   REVERB_REFLECTIONS_DLY  =   20;   // Verzögerung in ms
    private static final short REVERB_REVERB_LEVEL     = -1200;  // Diffuser Hall
    private static final int   REVERB_REVERB_DELAY     =   40;   // Delay in ms
    private static final short REVERB_DIFFUSION        = 1000;   // maximale Diffusion
    private static final short REVERB_DENSITY          =  800;   // hohe Dichte

    // --- Öffentliche Typen ---

    public enum Phase { INHALE, HOLD, EXHALE, FINISHED }

    public interface MeditationCallback {
        void onPhaseChanged(Phase phase, int cycleNumber);
        void onMeditationFinished();
    }

    // --- Felder ---

    private final Context            context;
    private final MeditationCallback callback;
    private final Handler            handler = new Handler(Looper.getMainLooper());

    private SoundPool            soundPool;
    private EnvironmentalReverb  reverb;
    private int idChord  = 0;
    private int idInhale = 0;
    private int idHold   = 0;
    private int idExhale = 0;

    private volatile boolean running     = false;
    private long             startTimeMs = 0;
    private int              cycleCount  = 0;
    private float            volume      = 0.5f;

    // ===== Konstruktor =====

    public MeditationController(Context context, MeditationCallback callback) {
        this.context  = context.getApplicationContext();
        this.callback = callback;
        initSoundPool();
    }

    // ===== Initialisierung =====

    private void initSoundPool() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attrs)
                .build();

        idChord  = safeLoad(R.raw.meditation_chord);
        idInhale = safeLoad(R.raw.meditation_inhale);
        idHold   = safeLoad(R.raw.meditation_hold);
        idExhale = safeLoad(R.raw.meditation_exhale);

        Log.d(TAG, "SoundPool – chord=" + idChord + " inhale=" + idInhale
                + " hold=" + idHold + " exhale=" + idExhale);

        // Reverb-Effekt an die Audio-Session des SoundPool binden
        initReverb();
    }

    private void initReverb() {
        try {
            // Session-ID 0 = globaler Effekt (gilt für alle Audio-Ausgaben des Prozesses,
            // solange kein anderer Effekt höhere Priorität hat)
            reverb = new EnvironmentalReverb(0, 0);
            reverb.setRoomLevel(REVERB_ROOM_LEVEL);
            reverb.setRoomHFLevel(REVERB_ROOM_HF_LEVEL);
            reverb.setDecayTime(REVERB_DECAY_TIME);
            reverb.setDecayHFRatio(REVERB_DECAY_HF_RATIO);
            reverb.setReflectionsLevel(REVERB_REFLECTIONS_LVL);
            reverb.setReflectionsDelay(REVERB_REFLECTIONS_DLY);
            reverb.setReverbLevel(REVERB_REVERB_LEVEL);
            reverb.setReverbDelay(REVERB_REVERB_DELAY);
            reverb.setDiffusion(REVERB_DIFFUSION);
            reverb.setDensity(REVERB_DENSITY);
            reverb.setEnabled(true);
            Log.d(TAG, "EnvironmentalReverb aktiv (global)");
        } catch (Exception e) {
            // Gerät unterstützt keinen EnvironmentalReverb – kein Problem, Ton spielt ohne Effekt
            Log.w(TAG, "EnvironmentalReverb nicht verfügbar: " + e.getMessage());
            reverb = null;
        }
    }

    private int safeLoad(int resId) {
        try {
            return soundPool.load(context, resId, 1);
        } catch (Exception e) {
            Log.w(TAG, "Sample nicht gefunden resId=" + resId + " – wird übersprungen");
            return 0;
        }
    }

    // ===== Öffentliche API =====

    /** Startet die Atemübung. */
    public void start(float vol) {
        if (running) return;
        this.volume = Math.max(0.05f, Math.min(1.0f, vol));
        running     = true;
        startTimeMs = System.currentTimeMillis();
        cycleCount  = 0;
        Log.d(TAG, "Meditation gestartet (volume=" + this.volume + ")");

        playSound(idChord);
        handler.postDelayed(this::scheduleInhale, INTRO_PAUSE_MS);
    }

    /** Stoppt die Übung sofort. */
    public void stop() {
        Log.d(TAG, "Meditation gestoppt");
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (soundPool != null) soundPool.autoPause();
    }

    /** Gibt zurück ob die Übung gerade läuft. */
    public boolean isRunning() {
        return running;
    }

    /** Gesamtdauer eines Zyklus in ms. */
    public static long getCycleDurationMs() {
        return DUR_INHALE_MS + DUR_HOLD_MS + DUR_EXHALE_MS;
    }

    /** Gibt SoundPool-Ressourcen frei. */
    public void release() {
        stop();
        if (reverb != null) {
            try { reverb.setEnabled(false); reverb.release(); } catch (Exception ignored) {}
            reverb = null;
        }
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }

    // ===== Private Scheduling-Methoden =====

    private void scheduleInhale() {
        if (!running) return;
        if (System.currentTimeMillis() - startTimeMs >= MEDITATION_DURATION_MS) {
            playEndSequence();
            return;
        }
        cycleCount++;
        Log.d(TAG, "Zyklus " + cycleCount + " – Einatmen");
        playSound(idInhale);
        if (callback != null) callback.onPhaseChanged(Phase.INHALE, cycleCount);
        handler.postDelayed(this::scheduleHold, DUR_INHALE_MS);
    }

    private void scheduleHold() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Halten");
        playSound(idHold);
        if (callback != null) callback.onPhaseChanged(Phase.HOLD, cycleCount);
        handler.postDelayed(this::scheduleExhale, DUR_HOLD_MS);
    }

    private void scheduleExhale() {
        if (!running) return;
        Log.d(TAG, "Zyklus " + cycleCount + " – Ausatmen");
        playSound(idExhale);
        if (callback != null) callback.onPhaseChanged(Phase.EXHALE, cycleCount);
        handler.postDelayed(this::scheduleInhale, DUR_EXHALE_MS);
    }

    private void playEndSequence() {
        running = false;
        Log.d(TAG, "Abschluss-Sequenz");
        playSound(idChord);
        handler.postDelayed(() -> {
            if (callback != null) {
                callback.onPhaseChanged(Phase.FINISHED, cycleCount);
                callback.onMeditationFinished();
            }
        }, OUTRO_PAUSE_MS);
    }

    private void playSound(int soundId) {
        if (soundPool == null || soundId == 0) return;
        soundPool.play(soundId, volume, volume, 1, 0, 1.0f);
    }
}
