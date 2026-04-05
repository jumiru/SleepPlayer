package com.example.sleepplayer;

import android.content.Intent;
import android.os.SystemClock;
import android.support.v4.media.session.MediaSessionCompat;
import android.view.KeyEvent;

/**
 * Callback für MediaSession – verarbeitet Kopfhörer-Tasten-Events.
 *
 * Einfacher Klick: Play/Pause umschalten + Timer neu starten
 * So kann man nachts ohne Bildschirm die Wiedergabe steuern.
 */
public class MediaSessionCallback extends MediaSessionCompat.Callback {

    private final PlaybackService service;

    public MediaSessionCallback(PlaybackService service) {
        this.service = service;
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
        if (mediaButtonEvent != null) {
            KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (keyEvent != null && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                int keyCode = keyEvent.getKeyCode();

                if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
                    // Toggle Play/Pause und Timer neu starten
                    service.togglePlayPause();
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
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
        }
        return super.onMediaButtonEvent(mediaButtonEvent);
    }
}

