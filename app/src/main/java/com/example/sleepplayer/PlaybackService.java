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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media.session.MediaButtonReceiver;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Hintergrund-Thread für MediaStore-Abfragen (verhindert Main-Thread I/O / ANR). */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    /** Handler zum Zurückwechseln auf den Main-Thread nach I/O. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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

    /**
     * WakeLock auf Service-Ebene – hält die CPU wach, damit der Service im
     * Hintergrund nicht vom System in einen Zustand versetzt wird, in dem er
     * keine Intents mehr empfangen kann.
     *
     * PARTIAL_WAKE_LOCK: CPU läuft, Bildschirm darf aus → batterieschonend.
     * Wird bei echter Wiedergabe und im Schlafmodus (Timer abgelaufen, aber Service
     * noch aktiv) gehalten; wird nur in stopPlayback() / onDestroy() freigegeben.
     */
    private PowerManager.WakeLock serviceWakeLock;

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
        Log.d(TAG, "Service created (instance=" + System.identityHashCode(this) + ")");

        prefsManager = new PrefsManager(this);
        trackSelector = new TrackSelector(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Notification Channel erstellen
        NotificationHelper.createChannel(this);

        // Service-WakeLock initialisieren (noch nicht acquiren)
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        serviceWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SleepPlayer:ServiceWakeLock");
        serviceWakeLock.setReferenceCounted(false);

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

        // Track-Liste im Hintergrund vorladen, damit beim ersten Abspielen
        // KEIN MediaStore-Query auf dem Main-Thread nötig ist (ANR-Prävention).
        ioExecutor.execute(() -> {
            List<TrackSelector.TrackInfo> tracks = trackSelector.getAllTracks();
            Log.d(TAG, "Track-Cache vorgeladen: " + tracks.size() + " Titel");
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();

            if (NotificationHelper.ACTION_PLAY_PAUSE.equals(action)) {
                togglePlayPause();
            } else if (NotificationHelper.ACTION_STOP.equals(action)) {
                stopPlayback();
            } else if (NotificationHelper.ACTION_DISMISS.equals(action)) {
                // Nutzer hat die Sleep-Mode-Notification weggewischt → Service vollständig beenden
                Log.d(TAG, "ACTION_DISMISS empfangen – Service wird beendet");
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
        Log.d(TAG, "Service destroyed (instance=" + System.identityHashCode(this) + ")");
        ioExecutor.shutdownNow();
        stopTimerInternal();
        releaseMediaPlayer();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        abandonAudioFocus();
        releaseServiceWakeLock();
        super.onDestroy();
    }

    // ===== Öffentliche Methoden für Activity und MediaSessionCallback =====

    public void setCallback(PlaybackCallback callback) {
        this.callback = callback;
    }

    /**
     * Spielt den nächsten Titel ab – zufällig oder aktuellen wiederholen,
     * je nach aktuellem Modus.
     *
     * WICHTIG: Die MediaStore-Abfrage läuft auf einem Hintergrund-Thread,
     * um ANR auf dem Main-Thread zu verhindern.
     */
    public void startRandomPlayback() {
        Log.d(TAG, "startRandomPlayback() – isRandomMode=" + isRandomMode
                + ", currentTrack=" + (currentTrack != null ? currentTrack.title : "null"));
        if (isRandomMode) {
            // Nächsten zufälligen Track im Hintergrund auswählen
            ioExecutor.execute(() -> {
                TrackSelector.TrackInfo track = trackSelector.getNextTrack();
                Log.d(TAG, "Nächster Track ausgewählt: "
                        + (track != null ? track.title : "keiner!"));
                mainHandler.post(() -> {
                    if (track != null) {
                        playTrackInternal(track);
                    } else {
                        Log.w(TAG, "Keine Tracks verfügbar – Wiedergabe gestoppt");
                    }
                });
            });
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
            Log.d(TAG, "repeatCurrentTrack: " + currentTrack.title);
            playTrackInternal(currentTrack);
        } else {
            Log.d(TAG, "repeatCurrentTrack: kein aktueller Track – lade zufällig");
            // Erster Start: wie zufällig, im Hintergrund laden
            ioExecutor.execute(() -> {
                TrackSelector.TrackInfo track = trackSelector.getNextTrack();
                mainHandler.post(() -> {
                    if (track != null) {
                        playTrackInternal(track);
                    }
                });
            });
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
     *
     * Verwendet eine lokale 'player'-Variable anstelle von 'mediaPlayer',
     * um veraltete Callbacks (von einem bereits ersetzten MediaPlayer) sicher
     * zu erkennen und zu ignorieren.
     */
    private void playTrackInternal(TrackSelector.TrackInfo track) {
        if (track == null) {
            Log.w(TAG, "playTrackInternal: track ist null, abgebrochen");
            return;
        }
        Log.d(TAG, "playTrackInternal: " + track.title + " / " + track.artist);

        // Audio Focus anfordern
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio Focus nicht erhalten – Wiedergabe abgebrochen");
            return;
        }

        // Service-WakeLock halten – verhindert, dass der Service vom System
        // "eingeschläfert" wird und keine Intents mehr empfangen kann
        acquireServiceWakeLock();

        // Alten Player stoppen und freigeben
        releaseMediaPlayer();

        try {
            MediaPlayer player = new MediaPlayer();
            mediaPlayer = player; // sofort setzen, damit Stale-Check funktioniert

            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build());
            player.setDataSource(this, track.uri);
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            player.setVolume(currentVolume, currentVolume);

            player.setOnPreparedListener(mp -> {
                // Sicherheitscheck: Wurde dieser Player inzwischen ersetzt?
                if (mediaPlayer != player) {
                    Log.w(TAG, "onPrepared: Player wurde bereits ersetzt, ignoriere");
                    return;
                }
                Log.d(TAG, "onPrepared: starte '" + track.title + "'");
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
                    Log.d(TAG, "Timer noch nicht aktiv – starte Timer");
                    startTimer(timerTotalMinutes);
                }
            });

            player.setOnCompletionListener(mp -> {
                // Sicherheitscheck: Wurde dieser Player inzwischen ersetzt?
                if (mediaPlayer != player) {
                    Log.w(TAG, "onCompletion: Player wurde bereits ersetzt, ignoriere");
                    return;
                }
                Log.d(TAG, "onCompletion: '" + track.title + "' fertig – nächster Track");
                startRandomPlayback();
            });

            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer Fehler: what=" + what + " extra=" + extra
                        + " track='" + track.title + "'");
                if (mediaPlayer != player) {
                    Log.w(TAG, "onError: Player wurde bereits ersetzt, ignoriere");
                    return true;
                }
                // Bei Fehler: nächsten Track versuchen
                startRandomPlayback();
                return true;
            });

            Log.d(TAG, "prepareAsync() gestartet für: " + track.title);
            player.prepareAsync();

        } catch (IOException e) {
            Log.e(TAG, "IOException beim Laden: " + track.title, e);
            // Nächsten Track versuchen
            startRandomPlayback();
        } catch (Exception e) {
            Log.e(TAG, "Unerwarteter Fehler in playTrackInternal", e);
        }
    }

    /**
     * Toggle Play/Pause – wird von der Kopfhörer-Taste aufgerufen.
     * Startet auch den Timer neu.
     */
    public void togglePlayPause() {
        Log.d(TAG, "togglePlayPause() – isPlaying=" + isPlaying
                + ", hasPlayer=" + (mediaPlayer != null)
                + ", currentTrack=" + (currentTrack != null ? currentTrack.title : "null"));
        if (isPlaying) {
            pausePlayback();
        } else {
            // Wenn noch kein Track geladen war, neuen starten
            if (mediaPlayer == null || currentTrack == null) {
                startRandomPlayback();
                // Timer wird in playTrackInternal/onPrepared gestartet,
                // aber auch direkt hier sicherheitshalber:
                restartTimer();
            } else {
                resumePlayback();
                restartTimer();
            }
        }
    }

    /**
     * Pausiert die Wiedergabe.
     */
    public void pausePlayback() {
        Log.d(TAG, "pausePlayback() – isPlaying=" + isPlaying);
        if (mediaPlayer != null && isPlaying) {
            try {
                mediaPlayer.pause();
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException in pausePlayback()", e);
                // Player in unerwartetem Zustand – freigeben
                releaseMediaPlayer();
            }
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
        Log.d(TAG, "resumePlayback() – isPlaying=" + isPlaying
                + ", hasPlayer=" + (mediaPlayer != null));
        if (mediaPlayer != null && !isPlaying) {
            // Audio Focus erneut anfordern
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio Focus nicht erhalten in resumePlayback()");
                return;
            }
            try {
                mediaPlayer.start();
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException in resumePlayback()", e);
                return;
            }
            isPlaying = true;
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            showNotification();

            if (callback != null) {
                callback.onPlaybackStateChanged(true);
            }
        }
    }

    /**
     * Stoppt die Wiedergabe vollständig und beendet den Service.
     */
    public void stopPlayback() {
        Log.d(TAG, "stopPlayback()");
        stopTimerInternal();
        releaseMediaPlayer();
        isPlaying = false;
        currentTrack = null;
        abandonAudioFocus();
        releaseServiceWakeLock();

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
        Log.d(TAG, "skipToNext()");
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

        Log.d(TAG, "Timer gestartet: " + minutes + " Minuten");

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
                Log.d(TAG, "Sleep-Timer abgelaufen – pausiere Wiedergabe");

                // Wiedergabe pausieren (nicht komplett stoppen)
                pausePlayback();

                if (callback != null) {
                    callback.onTimerFinished();
                }

                // *** KRITISCH: Foreground-Status BEIBEHALTEN ***
                // Statt stopForeground() zeigen wir eine spezielle "Schlafmodus"-Notification.
                // Nur so bleibt der Service als Foreground-Service am Leben und die
                // MediaSession empfängt weiterhin Kopfhörer-Tasten-Events – auch nach
                // langer Pause (z.B. nachts beim Wiedereinschlafen).
                // Der Nutzer kann die Notification wegwischen um den Service zu beenden.
                showSleepModeNotification();

                Log.d(TAG, "Service bleibt im Foreground (Sleep-Mode-Notification). "
                        + "Kopfhörertaste kann Wiedergabe jederzeit neu starten.");
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

    /** Aktuelle Wiedergabeposition in Millisekunden (0 wenn kein Track). */
    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Gesamtdauer des aktuellen Tracks in Millisekunden (0 wenn kein Track). */
    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                int d = mediaPlayer.getDuration();
                return d > 0 ? d : 0;
            } catch (IllegalStateException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Springt zur angegebenen Position in Millisekunden. */
    public void seekTo(int positionMs) {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.seekTo(positionMs);
                Log.d(TAG, "seekTo: " + positionMs + "ms");
            } catch (IllegalStateException e) {
                Log.w(TAG, "seekTo: IllegalStateException", e);
            }
        }
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
            Log.d(TAG, "releaseMediaPlayer()");
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "IllegalStateException beim Stoppen des MediaPlayers", e);
            }
            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "Fehler beim Release des MediaPlayers", e);
            }
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
        Log.d(TAG, "onAudioFocusChange: " + focusChange);
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

    /**
     * Zeigt die "Schlafmodus"-Notification und hält den Service im Foreground.
     * Wird aufgerufen wenn der Sleep-Timer abläuft und Wiedergabe pausiert wurde.
     * Hält die MediaSession aktiv → Kopfhörer-Tasten weiterhin nutzbar.
     */
    private void showSleepModeNotification() {
        String title = currentTrack != null ? currentTrack.title : "SleepPlayer";
        Notification notification = NotificationHelper.buildSleepNotification(
                this, mediaSession, title);
        // startForeground() hält den Service als Foreground-Service am Leben
        startForeground(NotificationHelper.NOTIFICATION_ID, notification);
        Log.d(TAG, "Sleep-Mode-Notification aktiv – Service bleibt im Foreground");
    }

    /** Acquiriert den Service-WakeLock (idempotent). */
    private void acquireServiceWakeLock() {
        if (serviceWakeLock != null && !serviceWakeLock.isHeld()) {
            serviceWakeLock.acquire();
            Log.d(TAG, "Service-WakeLock acquired");
        }
    }

    /** Gibt den Service-WakeLock frei (idempotent). */
    private void releaseServiceWakeLock() {
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
            serviceWakeLock.release();
            Log.d(TAG, "Service-WakeLock released");
        }
    }
}

