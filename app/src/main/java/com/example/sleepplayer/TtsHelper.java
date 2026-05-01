package com.example.sleepplayer;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Hilfklasse für Text-to-Speech-Ansagen (Sprachsynthese).
 *
 * Verwendet die im Gerät installierte TTS-Engine (keine externe Abhängigkeit).
 * Initialisierung ist asynchron – Ansagen werden gepuffert bis die Engine bereit ist.
 * Unterstützt einen "onDone"-Callback und Lautstärkenregelung.
 */
public class TtsHelper implements TextToSpeech.OnInitListener {

    private static final String TAG = "TtsHelper";
    private static final String UTTERANCE_ID = "sleep_player_tts";

    private TextToSpeech tts;
    private boolean ready = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Puffer für Ansagen, die vor der Initialisierung angefordert wurden. */
    private String pendingText = null;
    private float pendingVolume = 1.0f;
    private Runnable pendingOnDone = null;

    /** Aktuelle Lautstärke (0.0–1.0, entspricht MediaPlayer-Lautstärke). */
    private float currentVolume = 1.0f;

    /** Callback der nach Abschluss der Ansage auf dem Main-Thread ausgeführt wird. */
    private Runnable onDoneCallback = null;

    public TtsHelper(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    // ===== TextToSpeech.OnInitListener =====

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Deutsch bevorzugen, Fallback auf Gerätesprache
            int result = tts.setLanguage(Locale.GERMAN);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Deutsch nicht verfügbar – verwende Gerätesprache");
                tts.setLanguage(Locale.getDefault());
            }

            // Listener für Abschluss-Callback registrieren
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { /* nichts */ }

                @Override
                public void onDone(String utteranceId) {
                    fireOnDone();
                }

                @Override
                public void onError(String utteranceId) {
                    // Bei Fehler trotzdem Callback auslösen, damit Track startet
                    Log.w(TAG, "TTS-Fehler für Utterance: " + utteranceId);
                    fireOnDone();
                }
            });

            ready = true;
            Log.d(TAG, "TTS bereit");

            // Gepufferte Ansage nachholen
            if (pendingText != null) {
                speakNow(pendingText, pendingVolume, pendingOnDone);
                pendingText = null;
                pendingVolume = 1.0f;
                pendingOnDone = null;
            }
        } else {
            Log.e(TAG, "TTS-Initialisierung fehlgeschlagen (status=" + status + ")");
            // Trotzdem Callback auslösen damit Track startet
            fireOnDone();
        }
    }

    // ===== Öffentliche API =====

    /**
     * Liest die aktuelle Uhrzeit vor und ruft danach {@code onDone} auf dem Main-Thread auf.
     *
     * @param volume  Lautstärke (0.0–1.0), passend zur aktuellen Track-Lautstärke
     * @param onDone  Runnable, das nach Abschluss der Ansage ausgeführt wird (darf null sein)
     */
    public void speakCurrentTime(float volume, Runnable onDone) {
        Calendar cal = Calendar.getInstance();
        int hour   = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        String text;
        if (minute == 0) {
            text = "Es ist " + hour + " Uhr";
        } else {
            text = "Es ist " + hour + " Uhr " + minute;
        }

        Log.d(TAG, "Ansage (volume=" + volume + "): " + text);
        speak(text, volume, onDone);
    }

    /**
     * Liest die aktuelle Uhrzeit vor (ohne Callback, ohne spezielle Lautstärke).
     * Kompatibilitätsmethode – bevorzuge {@link #speakCurrentTime(float, Runnable)}.
     */
    public void speakCurrentTime() {
        speakCurrentTime(1.0f, null);
    }

    /**
     * Liest einen beliebigen Text vor.
     *
     * @param text    Zu sprechender Text
     * @param volume  Lautstärke (0.0–1.0)
     * @param onDone  Runnable nach Abschluss (darf null sein)
     */
    public void speak(String text, float volume, Runnable onDone) {
        if (text == null || text.isEmpty()) {
            if (onDone != null) mainHandler.post(onDone);
            return;
        }
        if (ready && tts != null) {
            speakNow(text, volume, onDone);
        } else {
            // TTS noch nicht initialisiert – puffern
            pendingText = text;
            pendingVolume = volume;
            pendingOnDone = onDone;
        }
    }

    /**
     * Liest einen beliebigen Text vor (ohne Callback).
     */
    public void speak(String text) {
        speak(text, 1.0f, null);
    }

    /**
     * Stoppt laufende Ansagen und gibt die TTS-Engine frei.
     * Muss in onDestroy() des Service aufgerufen werden.
     */
    public void release() {
        onDoneCallback = null;
        pendingOnDone = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        ready = false;
        pendingText = null;
        Log.d(TAG, "TTS freigegeben");
    }

    // ===== Intern =====

    private void speakNow(String text, float volume, Runnable onDone) {
        // Vorherigen Callback ersetzen (falls TTS unterbrochen wird)
        onDoneCallback = onDone;

        Bundle params = new Bundle();
        // KEY_PARAM_VOLUME: 0.0 (still) bis 1.0 (volle Stream-Lautstärke)
        // Wir clampen auf [0.05, 1.0] damit die Ansage immer hörbar bleibt
        float ttsVolume = Math.max(0.05f, Math.min(1.0f, volume));
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsVolume);

        // QUEUE_FLUSH: unterbricht ggf. laufende Ansage
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID);
    }

    /** Löst den gespeicherten onDone-Callback auf dem Main-Thread aus (einmalig). */
    private void fireOnDone() {
        Runnable cb = onDoneCallback;
        onDoneCallback = null;
        if (cb != null) {
            mainHandler.post(cb);
        }
    }
}
