package com.example.sleepplayer;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Hilfklasse für Text-to-Speech-Ansagen (Sprachsynthese).
 *
 * Verwendet die im Gerät installierte TTS-Engine (keine externe Abhängigkeit).
 * Initialisierung ist asynchron – Ansagen werden gepuffert bis die Engine bereit ist.
 */
public class TtsHelper implements TextToSpeech.OnInitListener {

    private static final String TAG = "TtsHelper";
    private static final String UTTERANCE_ID = "sleep_player_tts";

    private TextToSpeech tts;
    private boolean ready = false;

    /** Puffer für Ansagen, die vor der Initialisierung angefordert wurden. */
    private String pendingText = null;

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
            ready = true;
            Log.d(TAG, "TTS bereit");

            // Gepufferte Ansage nachholen
            if (pendingText != null) {
                speakNow(pendingText);
                pendingText = null;
            }
        } else {
            Log.e(TAG, "TTS-Initialisierung fehlgeschlagen (status=" + status + ")");
        }
    }

    // ===== Öffentliche API =====

    /**
     * Liest die aktuelle Uhrzeit vor.
     * Beispiel: "Es ist 22 Uhr 35" bzw. "Es ist 7 Uhr"
     */
    public void speakCurrentTime() {
        Calendar cal = Calendar.getInstance();
        int hour   = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        String text;
        if (minute == 0) {
            text = "Es ist " + hour + " Uhr";
        } else {
            text = "Es ist " + hour + " Uhr " + minute;
        }

        Log.d(TAG, "Ansage: " + text);
        speak(text);
    }

    /**
     * Liest einen beliebigen Text vor.
     */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (ready && tts != null) {
            speakNow(text);
        } else {
            // TTS noch nicht initialisiert – puffern
            pendingText = text;
        }
    }

    /**
     * Stoppt laufende Ansagen und gibt die TTS-Engine frei.
     * Muss in onDestroy() des Service aufgerufen werden.
     */
    public void release() {
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

    private void speakNow(String text) {
        // QUEUE_FLUSH: unterbricht ggf. laufende Ansage
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID);
    }
}

