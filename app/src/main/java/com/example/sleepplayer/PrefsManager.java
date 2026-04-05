package com.example.sleepplayer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Verwaltet App-Einstellungen über SharedPreferences.
 * Speichert: Lautstärke, Timer-Dauer, zuletzt gespielte Titel.
 */
public class PrefsManager {

    private static final String PREFS_NAME = "sleep_player_prefs";
    private static final String KEY_VOLUME = "volume_level";
    private static final String KEY_TIMER_MINUTES = "timer_minutes";
    private static final String KEY_RECENT_TRACKS = "recent_tracks";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_FOLDER_DISPLAY = "folder_display_name";
    private static final String KEY_RANDOM_MODE = "random_mode";
    private static final int MAX_RECENT = 5;

    private final SharedPreferences prefs;

    public PrefsManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Lautstärke ---

    /**
     * Speichert die Lautstärke-Position (SeekBar 0-200).
     */
    public void saveVolume(int volumeProgress) {
        prefs.edit().putInt(KEY_VOLUME, volumeProgress).apply();
    }

    /**
     * Gibt die gespeicherte Lautstärke-Position zurück (Standard: 30).
     */
    public int getVolume() {
        return prefs.getInt(KEY_VOLUME, 30);
    }

    // --- Timer ---

    /**
     * Speichert die Timer-Dauer in Minuten.
     */
    public void saveTimerMinutes(int minutes) {
        prefs.edit().putInt(KEY_TIMER_MINUTES, minutes).apply();
    }

    /**
     * Gibt die gespeicherte Timer-Dauer zurück (Standard: 30 Min).
     */
    public int getTimerMinutes() {
        return prefs.getInt(KEY_TIMER_MINUTES, 30);
    }

    // --- Ordner-Filter ---

    /**
     * Speichert den ausgewählten Ordner-URI (als String).
     * null = kein Filter (alle Audio-Dateien).
     */
    public void saveFolderUri(String uriString) {
        prefs.edit().putString(KEY_FOLDER_URI, uriString).apply();
    }

    /**
     * Gibt den gespeicherten Ordner-URI zurück (null = kein Filter).
     */
    public String getFolderUri() {
        return prefs.getString(KEY_FOLDER_URI, null);
    }

    /**
     * Speichert den Anzeigenamen des Ordners.
     */
    public void saveFolderDisplayName(String name) {
        prefs.edit().putString(KEY_FOLDER_DISPLAY, name).apply();
    }

    /**
     * Gibt den Anzeigenamen des Ordners zurück.
     */
    public String getFolderDisplayName() {
        return prefs.getString(KEY_FOLDER_DISPLAY, null);
    }

    /**
     * Löscht den Ordner-Filter (zurück zu "alle Dateien").
     */
    public void clearFolder() {
        prefs.edit()
                .remove(KEY_FOLDER_URI)
                .remove(KEY_FOLDER_DISPLAY)
                .apply();
    }

    // --- Zufallswiedergabe ---

    /** Speichert den Random-Modus (true = Zufall, false = Reihenfolge). */
    public void saveRandomMode(boolean random) {
        prefs.edit().putBoolean(KEY_RANDOM_MODE, random).apply();
    }

    /** Gibt zurück ob Zufallswiedergabe aktiv ist (Standard: true). */
    public boolean isRandomMode() {
        return prefs.getBoolean(KEY_RANDOM_MODE, true);
    }

    // --- Zuletzt gespielte Titel ---

    /**
     * Gibt die Liste der zuletzt gespielten Track-URIs zurück.
     */
    public List<String> getRecentTracks() {
        List<String> result = new ArrayList<>();
        String json = prefs.getString(KEY_RECENT_TRACKS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(arr.getString(i));
            }
        } catch (JSONException e) {
            // Ignore corrupt data
        }
        return result;
    }

    /**
     * Fügt einen Track zur Zuletzt-Gespielt-Liste hinzu.
     * Hält maximal MAX_RECENT Einträge (älteste werden entfernt).
     */
    public void addRecentTrack(String uri) {
        List<String> recent = getRecentTracks();
        // Entferne falls schon vorhanden
        recent.remove(uri);
        // Am Ende hinzufügen
        recent.add(uri);
        // Auf MAX_RECENT begrenzen
        while (recent.size() > MAX_RECENT) {
            recent.remove(0);
        }
        // Speichern
        JSONArray arr = new JSONArray(recent);
        prefs.edit().putString(KEY_RECENT_TRACKS, arr.toString()).apply();
    }
}



