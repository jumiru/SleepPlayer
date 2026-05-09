package com.example.sleepplayer;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;

/**
 * Speichert Meditations-Sitzungen pro Tag in SharedPreferences.
 * Key = ISO-Datum ("2026-05-09"), Value = Anzahl der Sitzungen.
 */
public class MeditationLog {

    private static final String PREFS_NAME = "meditation_log_prefs";
    private final SharedPreferences prefs;

    public MeditationLog(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Zählt eine Sitzung für heute hoch. */
    public void logToday() {
        String key = LocalDate.now().toString();
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
    }

    /** Gibt die Anzahl der Sitzungen für einen bestimmten Tag zurück. */
    public int getCount(int year, int month, int day) {
        String key = LocalDate.of(year, month, day).toString();
        return prefs.getInt(key, 0);
    }
}

