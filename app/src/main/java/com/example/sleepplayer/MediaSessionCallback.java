package com.example.sleepplayer;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/**
 * Callback für MediaSession – verarbeitet Kopfhörer-Tasten-Events.
 *
 * Kurzer Klick (< LongPressTimeout):  Play/Pause umschalten + Timer neu starten
 * Langer Druck (≥ LongPressTimeout):  Meditation ein-/ausschalten
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {

    private final PlaybackService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean longPressFired = false;

    /**
     * Runnable das nach LongPressTimeout feuert → Meditation umschalten.
     * Wird bei ACTION_UP abgebrochen, falls der Finger früh wieder losgelassen wird.
     * Initialisiert im Konstruktor damit 'service' sicher gesetzt ist.
     */
    private final Runnable longPressRunnable;

    public MediaSessionCallback(PlaybackService service) {
        this.service = service;
        this.longPressRunnable = () -> {
            longPressFired = true;
            service.toggleMeditation();
        };
    }

    @Override
    public void onPlay() {
        service.resumePlayback();
    }

    @Override
    public void onPause() {
        service.pausePlayback();
    }

    @Override
    public void onStop() {
        service.stopPlayback();
    }

    @Override
    public void onSkipToNext() {
        service.skipToNext();
    }

    @Override
    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
        if (mediaButtonEvent == null) return super.onMediaButtonEvent(null);

        KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (keyEvent == null) return super.onMediaButtonEvent(mediaButtonEvent);

        int keyCode = keyEvent.getKeyCode();
        boolean isPlayPause = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                           || keyCode == KeyEvent.KEYCODE_HEADSETHOOK;

        if (isPlayPause) {
            if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                if (keyEvent.getRepeatCount() == 0) {
                    // Erster DOWN: Timer für Langdruck starten
                    longPressFired = false;
                    handler.postDelayed(longPressRunnable,
                            ViewConfiguration.getLongPressTimeout());
                }
                return true;

            } else if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                handler.removeCallbacks(longPressRunnable);
                if (!longPressFired) {
                    // Kurzer Klick → Play/Pause
                    service.togglePlayPause();
                }
                return true;
            }
        } else if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                service.resumePlayback();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                service.pausePlayback();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                service.skipToNext();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                service.stopPlayback();
                return true;
            }
        }

        return super.onMediaButtonEvent(mediaButtonEvent);
    }
}
