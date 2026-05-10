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
 * Einfacher Klick:   Play/Pause umschalten
 * Doppelklick:       Meditation ein-/ausschalten
 * Langer Druck:      Meditation ein-/ausschalten (als Alternative)
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {

    /** Maximaler Abstand zwischen zwei Klicks für Doppelklick-Erkennung (ms). */
    private static final long DOUBLE_CLICK_WINDOW_MS = 400;

    private final PlaybackService service;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Langdruck-Zustand
    private boolean longPressFired = false;
    private final Runnable longPressRunnable;

    // Doppelklick-Zustand
    private boolean pendingSingleClick = false;
    private final Runnable singleClickRunnable;

    public MediaSessionCallback(PlaybackService service) {
        this.service = service;

        // Langdruck → Meditation (als Fallback behalten)
        this.longPressRunnable = () -> {
            longPressFired = true;
            service.toggleMeditation();
        };

        // Einfacher Klick (nach Ablauf des Doppelklick-Fensters) → Play/Pause
        this.singleClickRunnable = () -> {
            pendingSingleClick = false;
            service.togglePlayPause();
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
        service.stopAllPlaybackAndMeditation();
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
                    // Kurzer Klick: Doppelklick-Erkennung
                    if (pendingSingleClick) {
                        // Zweiter Klick innerhalb des Fensters → Doppelklick → Meditation
                        handler.removeCallbacks(singleClickRunnable);
                        pendingSingleClick = false;
                        service.toggleMeditation();
                    } else {
                        // Erster Klick: warten ob ein zweiter kommt
                        pendingSingleClick = true;
                        handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_WINDOW_MS);
                    }
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
                service.stopAllPlaybackAndMeditation();
                return true;
            }
        }

        return super.onMediaButtonEvent(mediaButtonEvent);
    }
}

