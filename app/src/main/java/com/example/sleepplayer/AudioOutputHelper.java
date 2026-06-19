package com.example.sleepplayer;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * Erkennt das aktuell aktive Audio-Ausgabegerät (Lautsprecher / kabelgebunden / Bluetooth).
 * Wird sowohl von der UI (Icon-Anzeige) als auch vom PlaybackService
 * (Stummschalt-Logik, explizites Routing) verwendet.
 */
public final class AudioOutputHelper {

    public enum OutputType { SPEAKER, WIRED, BLUETOOTH }

    /** Erkanntes Ausgabegerät: Typ + zugehöriges AudioDeviceInfo (null bei SPEAKER). */
    public static class Result {
        public final OutputType type;
        public final AudioDeviceInfo device;

        Result(OutputType type, AudioDeviceInfo device) {
            this.type = type;
            this.device = device;
        }
    }

    private AudioOutputHelper() {}

    /**
     * Bluetooth hat Vorrang vor kabelgebunden falls beide gleichzeitig verbunden sind,
     * da Bluetooth typischerweise die aktiv genutzte Verbindung ist.
     */
    public static Result detect(AudioManager audioManager) {
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo wired = null;
        for (AudioDeviceInfo d : devices) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                return new Result(OutputType.BLUETOOTH, d);
            }
            if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || t == AudioDeviceInfo.TYPE_USB_HEADSET || t == AudioDeviceInfo.TYPE_USB_DEVICE
                    || t == AudioDeviceInfo.TYPE_USB_ACCESSORY) {
                wired = d;
            }
        }
        if (wired != null) {
            return new Result(OutputType.WIRED, wired);
        }
        return new Result(OutputType.SPEAKER, null);
    }
}
