package com.example.sleepplayer;

/**
 * Hilfsklasse für logarithmische Lautstärkeregelung.
 *
 * Mappt eine lineare SeekBar-Position (0–200) auf einen
 * float-Wert (0.0–1.0) mit exponentieller Kurve.
 * Dadurch hat man im leisen Bereich (zum Einschlafen)
 * viel feinere Kontrolle.
 *
 * Beispiel: Bei Basis 50:
 * - SeekBar 0%   → Volume 0.000
 * - SeekBar 25%  → Volume 0.005
 * - SeekBar 50%  → Volume 0.031
 * - SeekBar 75%  → Volume 0.163
 * - SeekBar 100% → Volume 1.000
 */
public class VolumeHelper {

    // Höherer Wert = mehr Auflösung im leisen Bereich
    private static final double BASE = 50.0;

    // SeekBar max value (200 statt 100 für noch feinere Schritte)
    public static final int SEEK_BAR_MAX = 200;

    /**
     * Wandelt eine SeekBar-Position (0 bis SEEK_BAR_MAX) in
     * einen MediaPlayer-Volume-Wert (0.0 bis 1.0) um.
     */
    public static float progressToVolume(int progress) {
        if (progress <= 0) return 0.0f;
        if (progress >= SEEK_BAR_MAX) return 1.0f;

        double normalized = (double) progress / SEEK_BAR_MAX;
        double volume = (Math.pow(BASE, normalized) - 1.0) / (BASE - 1.0);
        return (float) Math.min(1.0, Math.max(0.0, volume));
    }

    /**
     * Wandelt einen Volume-Wert (0.0–1.0) zurück in eine SeekBar-Position.
     */
    public static int volumeToProgress(float volume) {
        if (volume <= 0.0f) return 0;
        if (volume >= 1.0f) return SEEK_BAR_MAX;

        double normalized = Math.log(volume * (BASE - 1.0) + 1.0) / Math.log(BASE);
        return (int) Math.round(normalized * SEEK_BAR_MAX);
    }

    /**
     * Gibt einen Prozentwert (0-100) für die Anzeige zurück.
     */
    public static int progressToPercent(int progress) {
        return Math.round(progressToVolume(progress) * 100);
    }
}

