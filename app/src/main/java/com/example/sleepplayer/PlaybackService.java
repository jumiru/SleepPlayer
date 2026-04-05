package com.example.sleepplayer;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media.session.MediaButtonReceiver;

import java.io.IOException;
import java.util.List;

/**
 * Foreground-Service für die Audio-Wiedergabe.
 *
 * Verantwortlich für:
 * - MediaPlayer Verwaltung
 * - MediaSession (Kopfhörer-Tasten)
 * - Sleep-Timer (CountDownTimer)
 * - Audio Focus
 * - WakeLock
 * - Foreground Notification
 */
public class PlaybackService extends Service {

    private static final String TAG = "PlaybackService";

    /** Dauer des Fade-outs in Millisekunden (letzte 60 Sekunden vor Timer-Ende). */
    private static final long FADE_OUT_DURATION_MS = 60_000L;

    // Binder für Activity-Kommunikation
    private final IBinder binder = new LocalBinder();

    private MediaPlayer mediaPlayer;
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private TrackSelector trackSelector;
    private PrefsManager prefsManager;

    private CountDownTimer sleepTimer;
    private long timerMillisRemaining = 0;
    private int timerTotalMinutes = 30;
    private boolean isTimerRunning = false;

    private float currentVolume = 0.15f;
    private boolean isPlaying = false;
    private boolean isRandomMode = true;

    // Fade-out Zustand
    private boolean isFadingOut = false;
    private float volumeBeforeFade = 0f;

    private TrackSelector.TrackInfo currentTrack;

    // Callback-Interface für UI-Updates
    private PlaybackCallback callback;

    public interface PlaybackCallback {
        void onTrackChanged(TrackSelector.TrackInfo track);
        void onPlaybackStateChanged(boolean isPlaying);
        void onTimerTick(long millisRemaining);
        void onTimerFinished();
    }

    public class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created");

        prefsManager = new PrefsManager(this);
        trackSelector = new TrackSelector(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Notification Channel erstellen
        NotificationHelper.createChannel(this);

        // MediaSession erstellen
        mediaSession = new MediaSessionCompat(this, "SleepPlayer");
        mediaSession.setCallback(new MediaSessionCallback(this));
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);

        // Audio Focus Request
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(this::onAudioFocusChange)
                .build();

        // Gespeicherte Lautstärke laden
        int savedProgress = prefsManager.getVolume();
        currentVolume = VolumeHelper.progressToVolume(savedProgress);

        // Gespeicherten Wiedergabe-Modus laden
        isRandomMode = prefsManager.isRandomMode();

        // Gespeicherte Timer-Dauer laden
        timerTotalMinutes = prefsManager.getTimerMinutes();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (NotificationHelper.ACTION_PLAY_PAUSE.equals(action)) {
                togglePlayPause();
            } else if (NotificationHelper.ACTION_STOP.equals(action)) {
                stopPlayback();
            } else {
                // Könnte ein MediaButton-Intent sein
                MediaButtonReceiver.handleIntent(mediaSession, intent);
            }
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service destroyed");
        stopTimerInternal();
        releaseMediaPlayer();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        abandonAudioFocus();
        super.onDestroy();
    }

    // ===== Öffentliche Methoden für Activity und MediaSessionCallback =====

    public void setCallback(PlaybackCallback callback) {
        this.callback = callback;
    }

    /**
     * Spielt den nächsten Titel ab – zufällig oder aktuellen wiederholen,
     * je nach aktuellem Modus.
     */
    public void startRandomPlayback() {
        if (isRandomMode) {
            TrackSelector.TrackInfo track = trackSelector.getNextTrack();
            if (track != null) {
                playTrack(track);
            }
        } else {
            repeatCurrentTrack();
        }
    }

    /**
     * Wiederholt den aktuellen Track (Einzeltitel-Wiederholung).
     * Falls noch kein Track gespielt wurde, startet einen zufälligen.
     */
    private void repeatCurrentTrack() {
        if (currentTrack != null) {
            playTrackInternal(currentTrack);
        } else {
            // Noch kein Track → zufällig starten
            TrackSelector.TrackInfo track = trackSelector.getNextTrack();
            if (track != null) {
                playTrackInternal(track);
            }
        }
    }

    /**
     * Spielt einen bestimmten Track ab (z.B. direkte Selektion aus der Liste).
     */
    public void playTrack(TrackSelector.TrackInfo track) {
        playTrackInternal(track);
    }

    /**
     * Interne Wiedergabe-Implementierung.
     */
    private void playTrackInternal(TrackSelector.TrackInfo track) {
        if (track == null) return;

        // Audio Focus anfordern
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus not granted");
            return;
        }

        // Alten Player stoppen
        releaseMediaPlayer();

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
            mediaPlayer.setDataSource(this, track.uri);
            mediaPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mediaPlayer.setVolume(currentVolume, currentVolume);

            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                currentTrack = track;

                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
                updateMediaMetadata(track);
                showNotification();

                if (callback != null) {
                    callback.onTrackChanged(track);
                    callback.onPlaybackStateChanged(true);
                }

                // Timer starten falls noch nicht läuft
                if (!isTimerRunning) {
                    startTimer(timerTotalMinutes);
                }
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                // Track fertig → nächsten zufälligen abspielen
                Log.d(TAG, "Track completed, playing next");
                startRandomPlayback();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: " + what + ", " + extra);
                // Bei Fehler: nächsten Track versuchen
                startRandomPlayback();
                return true;
            });

            mediaPlayer.prepareAsync();

        } catch (IOException e) {
            Log.e(TAG, "Error playing track", e);
            // Nächsten Track versuchen
            startRandomPlayback();
        }
    }

    /**
     * Toggle Play/Pause – wird von der Kopfhörer-Taste aufgerufen.
     * Startet auch den Timer neu.
     */
    public void togglePlayPause() {
        if (isPlaying) {
            pausePlayback();
        } else {
            // Wenn noch kein Track geladen war, neuen starten
            if (mediaPlayer == null || currentTrack == null) {
                startRandomPlayback();
                // Timer wird in playTrack gestartet
            } else {
                resumePlayback();
            }
            // Timer neu starten bei jedem Play
            restartTimer();
        }
    }

    /**
     * Pausiert die Wiedergabe.
     */
    public void pausePlayback() {
        if (mediaPlayer != null && isPlaying) {
            mediaPlayer.pause();
            isPlaying = false;
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
            showNotification();

            if (callback != null) {
                callback.onPlaybackStateChanged(false);
            }
        }
    }

    /**
     * Setzt die Wiedergabe fort.
     */
    public void resumePlayback() {
        if (mediaPlayer != null && !isPlaying) {
            // Audio Focus erneut anfordern
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;

            mediaPlayer.start();
            isPlaying = true;
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            showNotification();

            if (callback != null) {
                callback.onPlaybackStateChanged(true);
            }
        }
    }

    /**
     * Stoppt die Wiedergabe vollständig.
     */
    public void stopPlayback() {
        stopTimerInternal();
        releaseMediaPlayer();
        isPlaying = false;
        currentTrack = null;
        abandonAudioFocus();

        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);

        if (callback != null) {
            callback.onPlaybackStateChanged(false);
            callback.onTimerFinished();
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /**
     * Springt zum nächsten zufälligen Track.
     */
    public void skipToNext() {
        startRandomPlayback();
    }

    /**
     * Setzt die Lautstärke (0.0 bis 1.0).
     * Wird der Wert manuell geändert, wird ein laufender Fade-out abgebrochen.
     */
    public void setVolume(float volume) {
        currentVolume = Math.max(0.0f, Math.min(1.0f, volume));
        // Manueller Eingriff bricht den Fade-out ab
        if (isFadingOut) {
            isFadingOut = false;
            Log.d(TAG, "Fade-out abgebrochen durch manuellen Lautstärke-Eingriff");
        }
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(currentVolume, currentVolume);
        }
    }

    /**
     * Setzt die Timer-Dauer und startet den Timer.
     */
    public void startTimer(int minutes) {
        timerTotalMinutes = minutes;
        prefsManager.saveTimerMinutes(minutes);

        stopTimerInternal();

        long millis = minutes * 60L * 1000L;
        timerMillisRemaining = millis;
        isTimerRunning = true;
        isFadingOut = false; // Fade-out-Zustand zurücksetzen

        sleepTimer = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                timerMillisRemaining = millisUntilFinished;

                // === Fade-out Logik ===
                if (!isFadingOut && millisUntilFinished <= FADE_OUT_DURATION_MS) {
                    // Fade-out beginnt: Lautstärke für nächsten Start speichern
                    isFadingOut = true;
                    volumeBeforeFade = currentVolume;
                    prefsManager.saveVolume(VolumeHelper.volumeToProgress(currentVolume));
                    Log.d(TAG, "Fade-out gestartet. Lautstärke gespeichert: " + currentVolume);
                }
                if (isFadingOut && volumeBeforeFade > 0f) {
                    float fadeFactor = (float) millisUntilFinished / FADE_OUT_DURATION_MS;
                    float fadedVolume = volumeBeforeFade * fadeFactor;
                    if (mediaPlayer != null) {
                        mediaPlayer.setVolume(fadedVolume, fadedVolume);
                    }
                }

                if (callback != null) {
                    callback.onTimerTick(millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                isTimerRunning = false;
                timerMillisRemaining = 0;
                isFadingOut = false;
                Log.d(TAG, "Sleep timer beendet – Fade-out abgeschlossen");

                // Wiedergabe pausieren (nicht komplett stoppen)
                pausePlayback();

                if (callback != null) {
                    callback.onTimerFinished();
                }

                // Service stoppen
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        };
        sleepTimer.start();
    }

    /**
     * Startet den Timer mit der aktuellen Dauer neu.
     */
    public void restartTimer() {
        startTimer(timerTotalMinutes);
    }

    // ===== Getter =====

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isRandomMode() {
        return isRandomMode;
    }

    /**
     * Setzt den Wiedergabe-Modus.
     * @param random true = zufällig, false = in Listenreihenfolge
     */
    public void setRandomMode(boolean random) {
        this.isRandomMode = random;
        prefsManager.saveRandomMode(random);
        Log.d(TAG, "Wiedergabe-Modus: " + (random ? "Zufall" : "Reihenfolge"));
    }

    public TrackSelector.TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    public long getTimerMillisRemaining() {
        return timerMillisRemaining;
    }

    public boolean isTimerRunning() {
        return isTimerRunning;
    }

    public int getTimerTotalMinutes() {
        return timerTotalMinutes;
    }

    public TrackSelector getTrackSelector() {
        return trackSelector;
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    // ===== Private Hilfsmethoden =====

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                // Ignore
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void stopTimerInternal() {
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        // Falls ein Fade-out lief, Lautstärke wiederherstellen
        if (isFadingOut && mediaPlayer != null) {
            mediaPlayer.setVolume(currentVolume, currentVolume);
        }
        isFadingOut = false;
        isTimerRunning = false;
    }

    private void abandonAudioFocus() {
        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
    }

    private void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Dauerhafter Verlust → pausieren
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Kurzer Verlust → pausieren
                pausePlayback();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Leiser machen
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(currentVolume * 0.3f, currentVolume * 0.3f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Focus zurück → Lautstärke wiederherstellen
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(currentVolume, currentVolume);
                }
                // Nicht automatisch fortsetzen – Nutzer schläft vielleicht
                break;
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;

        long position = 0;
        if (mediaPlayer != null) {
            try {
                position = mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                // Ignore
            }
        }

        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY
                                | PlaybackStateCompat.ACTION_PAUSE
                                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                                | PlaybackStateCompat.ACTION_STOP
                                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
                .setState(state, position, 1.0f);

        mediaSession.setPlaybackState(builder.build());
    }

    private void updateMediaMetadata(TrackSelector.TrackInfo track) {
        if (mediaSession == null || track == null) return;

        MediaMetadataCompat metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.duration)
                .build();

        mediaSession.setMetadata(metadata);
    }

    private void showNotification() {
        String title = currentTrack != null ? currentTrack.title : "SleepPlayer";
        Notification notification = NotificationHelper.buildNotification(
                this, mediaSession, title, isPlaying);
        startForeground(NotificationHelper.NOTIFICATION_ID, notification);
    }
}

