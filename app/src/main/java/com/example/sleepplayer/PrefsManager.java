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
    private static final String KEY_TTS_ENABLED = "tts_enabled";

    // --- Audioausgabe stummschalten (pro Gerätetyp) ---
    private static final String KEY_SUPPRESS_SPEAKER   = "suppress_speaker";
    private static final String KEY_SUPPRESS_WIRED      = "suppress_wired";
    private static final String KEY_SUPPRESS_BLUETOOTH  = "suppress_bluetooth";

    // --- Kompressor ---
    private static final String KEY_COMP_ENABLED   = "comp_enabled";
    private static final String KEY_COMP_THRESHOLD = "comp_threshold"; // dB, -40…0
    private static final String KEY_COMP_RATIO     = "comp_ratio";     // 1…10 (×10 gespeichert)
    private static final String KEY_COMP_MAKEUP    = "comp_makeup";    // dB, 0…18

    // --- Meditations-Effekte ---
    private static final String KEY_MED_REVERB_ENABLED = "med_reverb_enabled";
    private static final String KEY_MED_REVERB_DECAY   = "med_reverb_decay";   // ms
    private static final String KEY_MED_DELAY_ENABLED  = "med_delay_enabled";
    private static final String KEY_MED_DELAY_MS       = "med_delay_ms";       // ms
    private static final String KEY_MED_DELAY_LEVEL    = "med_delay_level";    // 0–100 %

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

    // --- Zeitansage (TTS) ---

    /** Speichert ob die Zeitansage aktiviert ist. */
    public void saveTtsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TTS_ENABLED, enabled).apply();
    }

    /** Gibt zurück ob die Zeitansage aktiviert ist (Standard: true). */
    public boolean isTtsEnabled() {
        return prefs.getBoolean(KEY_TTS_ENABLED, true);
    }


    // --- Audioausgabe stummschalten ---

    public void saveSuppressSpeaker(boolean suppress)    { prefs.edit().putBoolean(KEY_SUPPRESS_SPEAKER, suppress).apply(); }
    public boolean isSuppressSpeaker()                   { return prefs.getBoolean(KEY_SUPPRESS_SPEAKER, false); }

    public void saveSuppressWired(boolean suppress)       { prefs.edit().putBoolean(KEY_SUPPRESS_WIRED, suppress).apply(); }
    public boolean isSuppressWired()                      { return prefs.getBoolean(KEY_SUPPRESS_WIRED, false); }

    public void saveSuppressBluetooth(boolean suppress)   { prefs.edit().putBoolean(KEY_SUPPRESS_BLUETOOTH, suppress).apply(); }
    public boolean isSuppressBluetooth()                   { return prefs.getBoolean(KEY_SUPPRESS_BLUETOOTH, false); }

    /** Gibt zurück ob die Ausgabe auf dem angegebenen Gerätetyp stummgeschaltet ist. */
    public boolean isOutputSuppressed(AudioOutputHelper.OutputType type) {
        switch (type) {
            case SPEAKER:   return isSuppressSpeaker();
            case WIRED:     return isSuppressWired();
            case BLUETOOTH: return isSuppressBluetooth();
            default:        return false;
        }
    }

    // --- Meditations-Effekte ---

    public void saveMeditationReverbEnabled(boolean on) { prefs.edit().putBoolean(KEY_MED_REVERB_ENABLED, on).apply(); }
    public boolean isMeditationReverbEnabled()          { return prefs.getBoolean(KEY_MED_REVERB_ENABLED, true); }

    /** Nachhallzeit in ms (500–6000). Standard: 3500 ms */
    public void saveMeditationReverbDecay(int ms) { prefs.edit().putInt(KEY_MED_REVERB_DECAY, ms).apply(); }
    public int getMeditationReverbDecay()          { return prefs.getInt(KEY_MED_REVERB_DECAY, 3500); }

    public void saveMeditationDelayEnabled(boolean on) { prefs.edit().putBoolean(KEY_MED_DELAY_ENABLED, on).apply(); }
    public boolean isMeditationDelayEnabled()          { return prefs.getBoolean(KEY_MED_DELAY_ENABLED, true); }

    /** Delay-Zeit in ms (100–2000). Standard: 1000 ms */
    public void saveMeditationDelayMs(int ms) { prefs.edit().putInt(KEY_MED_DELAY_MS, ms).apply(); }
    public int getMeditationDelayMs()          { return prefs.getInt(KEY_MED_DELAY_MS, 1000); }

    /** Delay-Lautstärke 0–100 %. Standard: 35 % */
    public void saveMeditationDelayLevel(int percent) { prefs.edit().putInt(KEY_MED_DELAY_LEVEL, percent).apply(); }
    public int getMeditationDelayLevel()               { return prefs.getInt(KEY_MED_DELAY_LEVEL, 35); }

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

    // --- Kompressor ---

    public void saveCompressorEnabled(boolean on)    { prefs.edit().putBoolean(KEY_COMP_ENABLED, on).apply(); }
    /** Standard: aus – opt-in Feature. */
    public boolean isCompressorEnabled()             { return prefs.getBoolean(KEY_COMP_ENABLED, false); }

    /** Schwellenwert in dB (–40…0). Standard: –20 dB */
    public void saveCompressorThreshold(int db)      { prefs.edit().putInt(KEY_COMP_THRESHOLD, db).apply(); }
    public int  getCompressorThreshold()             { return prefs.getInt(KEY_COMP_THRESHOLD, -20); }

    /** Ratio ×10 gespeichert, also 20 = 2.0:1 … 80 = 8.0:1. Standard: 40 = 4:1 */
    public void saveCompressorRatio(int ratioTimes10) { prefs.edit().putInt(KEY_COMP_RATIO, ratioTimes10).apply(); }
    public int  getCompressorRatio()                  { return prefs.getInt(KEY_COMP_RATIO, 40); }

    /** Makeup-Gain in dB (0…18). Standard: 6 dB */
    public void saveCompressorMakeup(int db)         { prefs.edit().putInt(KEY_COMP_MAKEUP, db).apply(); }
    public int  getCompressorMakeup()                { return prefs.getInt(KEY_COMP_MAKEUP, 6); }
}



