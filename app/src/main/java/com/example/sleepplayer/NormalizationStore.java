package com.example.sleepplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Speichert Normalisierungs-Daten für Tracks in SharedPreferences.
 *
 * Konzept:
 *   - Ein Track wird als "Referenz" markiert.
 *   - Alle anderen Tracks werden relativ zur Referenz analysiert.
 *   - Der gespeicherte Gain-Multiplier wird beim Abspielen auf die
 *     aktuelle Lautstärke aufgerechnet: effectiveVol = baseVol * gain
 *
 * Gain-Berechnung:
 *   refRms  = RMS des Referenz-Tracks in dBFS
 *   trackRms = RMS des Tracks in dBFS
 *   gainDb  = refRms - trackRms
 *   gainMul = 10^(gainDb / 20)
 *
 * Beispiel:
 *   Referenz: -20 dBFS, lauter Track: -14 dBFS → gainDb = -6 dB → gainMul ≈ 0.50
 *   Referenz: -20 dBFS, leiser Track: -26 dBFS → gainDb = +6 dB → gainMul ≈ 2.00
 *
 * Maximaler Boost/Cut: ±18 dB (= Faktor 0.125 bis 8.0)
 */
public class NormalizationStore {

    private static final String TAG = "NormalizationStore";

    private static final String PREFS_NAME  = "sleep_player_normalization";
    private static final String KEY_REF_URI   = "reference_uri";
    private static final String KEY_REF_TITLE = "reference_title";
    private static final String KEY_GAINS_JSON = "gains_json";
    private static final String KEY_SECTIONAL_JSON = "sectional_gains_json";

    /** Maximaler Gain-Boost in dB (verhindert extremes Aufdrehen leiser Tracks). */
    public static final float MAX_GAIN_DB = 18f;

    private final SharedPreferences prefs;

    public NormalizationStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ===== Referenz-Track =====

    /** Setzt den Referenz-Track (URI + Anzeigename). */
    public void saveReferenceTrack(String uri, String title) {
        prefs.edit()
                .putString(KEY_REF_URI, uri)
                .putString(KEY_REF_TITLE, title)
                .apply();
        Log.d(TAG, "Referenz-Track gesetzt: " + title);
    }

    /** URI des Referenz-Tracks, oder null wenn keiner gesetzt. */
    public String getReferenceTrackUri() {
        return prefs.getString(KEY_REF_URI, null);
    }

    /** Anzeigename des Referenz-Tracks, oder null. */
    public String getReferenceTrackTitle() {
        return prefs.getString(KEY_REF_TITLE, null);
    }

    /** Gibt zurück ob ein Referenz-Track gesetzt ist. */
    public boolean hasReferenceTrack() {
        return prefs.getString(KEY_REF_URI, null) != null;
    }

    // ===== Gain-Offsets =====

    /**
     * Speichert den Gain-Multiplier für einen Track.
     * Der Wert wird auf [0.125, 8.0] (= ±18 dB) begrenzt.
     *
     * @param uri        Track-URI als String
     * @param gainMul    Gain-Faktor (1.0 = keine Änderung, 0.5 = -6 dB, 2.0 = +6 dB)
     */
    public void saveGain(String uri, float gainMul) {
        // Auf maximal ±18 dB begrenzen
        float maxMul = (float) Math.pow(10, MAX_GAIN_DB / 20f);
        float minMul = 1f / maxMul;
        gainMul = Math.max(minMul, Math.min(maxMul, gainMul));

        Map<String, Float> gains = getAllGains();
        gains.put(uri, gainMul);
        saveAllGains(gains);
    }

    /**
     * Gibt den gespeicherten Gain-Multiplier für einen Track zurück.
     * Gibt 1.0 zurück wenn kein Gain gespeichert ist.
     */
    public float getGain(String uri) {
        Float val = getAllGains().get(uri);
        return val != null ? val : 1.0f;
    }

    /** Gibt zurück ob für diesen Track ein Gain gespeichert ist. */
    public boolean hasGain(String uri) {
        return getAllGains().containsKey(uri);
    }

    /** Gibt alle gespeicherten Gain-Werte zurück (URI → Multiplier). */
    public Map<String, Float> getAllGains() {
        Map<String, Float> result = new HashMap<>();
        String json = prefs.getString(KEY_GAINS_JSON, "{}");
        try {
            JSONObject obj = new JSONObject(json);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, (float) obj.getDouble(key));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Fehler beim Lesen der Gain-Daten", e);
        }
        return result;
    }

    /** Löscht alle Gain-Offsets (Normalisierungsdaten zurücksetzen). */
    public void clearGains() {
        prefs.edit().remove(KEY_GAINS_JSON).apply();
        Log.d(TAG, "Alle Gain-Daten gelöscht");
    }

    /** Löscht Referenz-Track + alle Gains. */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Alle Normalisierungs-Daten gelöscht");
    }

    // ===== Sektionsweise Gains =====

    /**
     * Speichert sektionsweise Gain-Daten für einen Track.
     * sections: Liste von float[2] = [sectionStartMs, gainMultiplier]
     */
    public void saveSectionalGains(String uri, List<float[]> sections) {
        JSONObject root;
        String existing = prefs.getString(KEY_SECTIONAL_JSON, "{}");
        try { root = new JSONObject(existing); } catch (JSONException e) { root = new JSONObject(); }
        try {
            JSONArray arr = new JSONArray();
            for (float[] s : sections) {
                JSONArray pair = new JSONArray();
                pair.put(s[0]);
                pair.put(s[1]);
                arr.put(pair);
            }
            root.put(uri, arr);
            prefs.edit().putString(KEY_SECTIONAL_JSON, root.toString()).apply();
            Log.d(TAG, "Sektionsdaten gespeichert: " + sections.size() + " Sektionen für " + uri);
        } catch (JSONException e) {
            Log.e(TAG, "Fehler beim Speichern der Sektionsdaten", e);
        }
    }

    /**
     * Gibt die gespeicherten Sektions-Gain-Daten für einen Track zurück.
     * @return Liste von float[2] = [sectionStartMs, gainMultiplier], oder null wenn nicht vorhanden.
     */
    public List<float[]> getSectionalGains(String uri) {
        String json = prefs.getString(KEY_SECTIONAL_JSON, "{}");
        try {
            JSONObject root = new JSONObject(json);
            if (!root.has(uri)) return null;
            JSONArray arr = root.getJSONArray(uri);
            List<float[]> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONArray pair = arr.getJSONArray(i);
                result.add(new float[]{(float) pair.getDouble(0), (float) pair.getDouble(1)});
            }
            return result;
        } catch (JSONException e) {
            Log.w(TAG, "Fehler beim Lesen der Sektionsdaten", e);
            return null;
        }
    }

    /** Gibt zurück ob sektionsweise Gains für diesen Track gespeichert sind. */
    public boolean hasSectionalGains(String uri) {
        String json = prefs.getString(KEY_SECTIONAL_JSON, "{}");
        try {
            return new JSONObject(json).has(uri);
        } catch (JSONException e) {
            return false;
        }
    }

    /** Löscht sektionsweise Gains für einen bestimmten Track. */
    public void clearSectionalGains(String uri) {
        String json = prefs.getString(KEY_SECTIONAL_JSON, "{}");
        try {
            JSONObject root = new JSONObject(json);
            root.remove(uri);
            prefs.edit().putString(KEY_SECTIONAL_JSON, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Fehler beim Löschen der Sektionsdaten", e);
        }
    }

    /**
     * Berechnet den Gain-Multiplier aus zwei dBFS-Werten.
     */
    public static float computeGainMultiplier(float refDb, float trackDb) {
        float gainDb = refDb - trackDb;
        return (float) Math.pow(10.0, gainDb / 20.0);
    }

    // ===== Intern =====

    private void saveAllGains(Map<String, Float> gains) {        JSONObject obj = new JSONObject();
        try {
            for (Map.Entry<String, Float> entry : gains.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException e) {
            Log.e(TAG, "Fehler beim Schreiben der Gain-Daten", e);
        }
        prefs.edit().putString(KEY_GAINS_JSON, obj.toString()).apply();
    }
}


