package com.example.sleepplayer;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.KeyEvent;

/**
 * Callback für MediaSession – verarbeitet Kopfhörer-Tasten-Events.
 *
 * 1× Klick:        Play/Pause
 * 2× Klick (oder mehr), während Track läuft:  Nächster Track (zufällig)
 * 2× Klick (oder mehr), während pausiert:     Meditation ein-/ausschalten
 *
 * Ab 2 Klicks wird also nicht mehr nach Klickzahl unterschieden, sondern
 * modusabhängig entschieden (isPlaying) – 3× Klick etc. verhält sich wie 2×.
 *
 * Langer Druck wird vom Android-System für Gemini/Voice-Assistent abgefangen
 * und erreicht diese Klasse nicht – daher kein Long-Press-Handling.
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {

    private static final String TAG = "MediaSessionCallback";

    /**
     * Wartefenster nach dem letzten Klick, bevor die Aktion ausgeführt wird.
     * Lang genug für Mehrfachklick-Erkennung, kurz genug für flüssige Bedienung.
     */
    private static final long CLICK_WINDOW_MS = 450;

    private final PlaybackService service;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int clickCount = 0;
    private final Runnable executeClickAction;

    public MediaSessionCallback(PlaybackService service) {
        this.service = service;
        this.executeClickAction = () -> {
            int count = clickCount;
            clickCount = 0;
            log("Klick-Aktion ausgelöst: count=" + count);
            if (count == 1) {
                log("1× Klick → togglePlayPause()");
                service.togglePlayPause();
            } else if (count >= 2) {
                if (service.isPlaying()) {
                    log(count + "× Klick, Track läuft → skipToNext()");
                    service.skipToNext();
                } else {
                    log(count + "× Klick, pausiert → toggleMeditation()");
                    service.toggleMeditation();
                }
            }
        };
    }

    @Override
    public void onPlay() {
        log("onPlay() → resumePlayback()");
        service.resumePlayback();
    }

    @Override
    public void onPause() {
        log("onPause() → pausePlayback()");
        service.pausePlayback();
    }

    @Override
    public void onStop() {
        log("onStop() → stopAllPlaybackAndMeditation()");
        service.stopAllPlaybackAndMeditation();
    }

    @Override
    public void onSkipToNext() {
        log("onSkipToNext() → skipToNext()");
        service.skipToNext();
    }

    @Override
    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
        if (mediaButtonEvent == null) {
            log("onMediaButtonEvent: Intent ist null");
            return super.onMediaButtonEvent(null);
        }

        KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (keyEvent == null) {
            log("onMediaButtonEvent: KeyEvent ist null");
            return super.onMediaButtonEvent(mediaButtonEvent);
        }

        logKeyEvent(keyEvent);

        int keyCode = keyEvent.getKeyCode();
        boolean isPlayPause = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                           || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;

        if (isPlayPause) {
            // Nur auf ACTION_UP reagieren (DOWN nur konsumieren)
            if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                return true;
            }
            if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                clickCount++;
                handler.removeCallbacks(executeClickAction);
                handler.postDelayed(executeClickAction, CLICK_WINDOW_MS);
                log("Klick #" + clickCount + " gezählt – warte " + CLICK_WINDOW_MS + "ms");
                return true;
            }

        } else if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                log("MEDIA_PLAY → resumePlayback()");
                service.resumePlayback();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                log("MEDIA_PAUSE → pausePlayback()");
                service.pausePlayback();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                log("MEDIA_NEXT → skipToNext()");
                service.skipToNext();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                log("MEDIA_STOP → stopAllPlaybackAndMeditation()");
                service.stopAllPlaybackAndMeditation();
                return true;
            }
        }

        return super.onMediaButtonEvent(mediaButtonEvent);
    }

    private void logKeyEvent(KeyEvent keyEvent) {
        String action;
        switch (keyEvent.getAction()) {
            case KeyEvent.ACTION_DOWN: action = "DOWN"; break;
            case KeyEvent.ACTION_UP:   action = "UP";   break;
            default:                   action = "ACTION=" + keyEvent.getAction();
        }
        log("KeyEvent: " + KeyEvent.keyCodeToString(keyEvent.getKeyCode()) + " " + action
                + " | repeat=" + keyEvent.getRepeatCount()
                + " | deviceId=" + keyEvent.getDeviceId()
                + " | source=0x" + Integer.toHexString(keyEvent.getSource())
                + " | clickCount=" + clickCount);
    }

    private void log(String message) {
        LogManager.getInstance(service).log(TAG, message);
    }
}
