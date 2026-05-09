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
import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;

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
    private MeditationLog meditationLog;

    private CountDownTimer sleepTimer;
    private long timerMillisRemaining = 0;
    private int timerTotalMinutes = 30;
    private boolean isTimerRunning = false;

    private float currentVolume = 0.15f;
    private boolean isPlaying = false;
    private boolean isRandomMode = true;

    /**
     * Generations-Zähler zur Vermeidung von Race-Conditions beim asynchronen Track-Laden.
     *
     * Problem ohne diesen Zähler:
     *   1. Track A läuft zu Ende → onCompletion → startRandomPlayback() startet IO-Thread
     *   2. Nutzer wählt währenddessen Track B aus → playTrackInternal(B) läuft sofort
     *   3. IO-Thread fertig → mainHandler.post(playTrackInternal(random)) → überschreibt B!
     *
     * Lösung: playTrackInternal() inkrementiert den Zähler. Der async-Callback prüft
     * ob der Zähler noch passt; falls nicht, wird der veraltete Aufruf verworfen.
     */
    private int playbackGeneration = 0;

    // Fade-out Zustand
    private boolean isFadingOut = false;
    private float volumeBeforeFade = 0f;

    /**
     * Normalisierungs-Gain des aktuell spielenden Tracks (1.0 = keine Änderung).
     * Wird in playTrackInternal() gesetzt.
     */
    private float currentTrackGain = 1.0f;

    /** Normalisierungs-Daten (Referenz-Track + Gain-Offsets). */
    private NormalizationStore normalizationStore;

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

    /** Sprachsynthese für Zeitansage beim Timer-Start. */
    private TtsHelper ttsHelper;

    /** Steuerung der 4-7-8 Atemübung. */
    private MeditationController meditationController;

    // Callback-Interface für UI-Updates
    private PlaybackCallback callback;

    public interface PlaybackCallback {
        void onTrackChanged(TrackSelector.TrackInfo track);
        void onPlaybackStateChanged(boolean isPlaying);
        void onTimerTick(long millisRemaining);
        void onTimerFinished();
        /** Wird aufgerufen wenn der Meditations-Modus ein-/ausgeschaltet wird. */
        default void onMeditationStateChanged(boolean active, MeditationController.Phase phase, int cycle) {}
    }

    /**
     * Callback-Interface für die MeditationActivity (Animations-UI).
     * Wird von der Activity registriert solange sie sichtbar ist.
     */
    public interface MeditationUICallback {
        void onMeditationPhaseChanged(MeditationController.Phase phase, int cycle);
        void onMeditationDone();
    }

    private volatile MeditationUICallback meditationUICallback;

    public void setMeditationUICallback(MeditationUICallback cb) {
        meditationUICallback = cb;
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
        meditationLog = new MeditationLog(this);
        trackSelector = new TrackSelector(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        normalizationStore = new NormalizationStore(this);

        // Meditations-Controller initialisieren
        meditationController = new MeditationController(this, new MeditationController.MeditationCallback() {
            @Override
            public void onPhaseChanged(MeditationController.Phase phase, int cycle) {
                Log.d(TAG, "Meditation Phase: " + phase + " Zyklus " + cycle);
                if (callback != null) {
                    callback.onMeditationStateChanged(true, phase, cycle);
                }
                if (meditationUICallback != null) {
                    meditationUICallback.onMeditationPhaseChanged(phase, cycle);
                }
            }
            @Override
            public void onMeditationFinished() {
                Log.d(TAG, "Meditation beendet");
                if (callback != null) {
                    callback.onMeditationStateChanged(false,
                            MeditationController.Phase.FINISHED, 0);
                }
                if (meditationUICallback != null) {
                    meditationUICallback.onMeditationDone();
                }
                // Automatisch zurück in den normalen Schlafmodus wechseln
                showSleepModeNotification();
            }
        });

        // Notification Channel erstellen
        NotificationHelper.createChannel(this);

        // Service-WakeLock initialisieren (noch nicht acquiren)
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        serviceWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "SleepPlayer:ServiceWakeLock");
        serviceWakeLock.setReferenceCounted(false);

        // MediaSession erstellen (MUSS vor startForeground() stehen!)
        mediaSession = new MediaSessionCompat(this, "SleepPlayer");
        mediaSession.setCallback(new MediaSessionCallback(this));
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setActive(true);

        // Initialen PlaybackState setzen – ohne diesen ignoriert Android
        // die MediaSession beim allerersten Kopfhörer-Tastendruck
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED);

        // Sofort als Foreground-Service starten – stellt sicher dass die MediaSession
        // von Anfang an Kopfhörer-Tasten empfängt, auch beim allerersten App-Start
        // ohne vorherigen Track. Der Service bleibt so immer erreichbar.
        // WICHTIG: erst NACH mediaSession-Initialisierung aufrufen!
        Notification initialNotification = NotificationHelper.buildNotification(
                this, mediaSession, getString(R.string.app_name), false);
        startForeground(NotificationHelper.NOTIFICATION_ID, initialNotification);

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

        // TTS initialisieren (asynchron – bereit bevor der erste Timer gestartet wird)
        ttsHelper = new TtsHelper(this);

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
        if (ttsHelper != null) {
            ttsHelper.release();
            ttsHelper = null;
        }
        if (meditationController != null) {
            meditationController.release();
            meditationController = null;
        }
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
            // Generation zum Zeitpunkt dieses Aufrufs merken
            final int generation = playbackGeneration;
            ioExecutor.execute(() -> {
                TrackSelector.TrackInfo track = trackSelector.getNextTrack();
                Log.d(TAG, "Nächster Track ausgewählt: "
                        + (track != null ? track.title : "keiner!"));
                mainHandler.post(() -> {
                    // Nur abspielen wenn kein direkter Track-Wechsel dazwischengekommen ist
                    if (generation != playbackGeneration) {
                        Log.d(TAG, "startRandomPlayback: Generation veraltet – abgebrochen "
                                + "(war=" + generation + ", aktuell=" + playbackGeneration + ")");
                        return;
                    }
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
            final int generation = playbackGeneration;
            ioExecutor.execute(() -> {
                TrackSelector.TrackInfo track = trackSelector.getNextTrack();
                mainHandler.post(() -> {
                    if (generation != playbackGeneration) {
                        Log.d(TAG, "repeatCurrentTrack: Generation veraltet – abgebrochen");
                        return;
                    }
                    if (track != null) {
                        playTrackInternal(track);
                    }
                });
            });
        }
    }

    /**
     * Spielt einen bestimmten Track ab (z.B. direkte Selektion aus der Liste).
     * Wenn der Player gerade nicht läuft (neuer Start), wird zuerst die Uhrzeit angesagt.
     * Wenn bereits ein Track läuft (Wechsel), startet der neue Track sofort ohne Ansage.
     */
    public void playTrack(TrackSelector.TrackInfo track) {
        if (!isPlaying) {
            speakTimeAndThen(() -> playTrackInternal(track));
        } else {
            playTrackInternal(track);
        }
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
        // Generation inkrementieren → macht alle laufenden async-Anfragen ungültig
        playbackGeneration++;
        Log.d(TAG, "playTrackInternal: " + track.title + " / " + track.artist
                + " (generation=" + playbackGeneration + ")");

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

        // Normalisierungs-Gain für diesen Track laden
        currentTrackGain = normalizationStore.getGain(track.uri.toString());
        Log.d(TAG, "Track-Gain: " + currentTrackGain + "× für " + track.title);

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
            player.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));

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
     * Spricht zuerst die aktuelle Uhrzeit an (passend zur Track-Lautstärke),
     * startet danach den Track bzw. setzt die Wiedergabe fort.
     */
    public void togglePlayPause() {
        Log.d(TAG, "togglePlayPause() – isPlaying=" + isPlaying
                + ", hasPlayer=" + (mediaPlayer != null)
                + ", currentTrack=" + (currentTrack != null ? currentTrack.title : "null")
                + ", isMeditating=" + isMeditating()
                + ", isTimerRunning=" + isTimerRunning
                + ", wakeLockHeld=" + (serviceWakeLock != null && serviceWakeLock.isHeld()));

        // Wenn Meditation läuft → Pause = Meditation beenden, zurück in Schlafmodus
        if (isMeditating()) {
            Log.d(TAG, "togglePlayPause: Meditation läuft → stoppe Meditation");
            stopMeditation();
            return;
        }

        if (isPlaying) {
            pausePlayback();
        } else {
            if (mediaPlayer == null || currentTrack == null) {
                // Neuer Start: TTS → dann Track laden
                speakTimeAndThen(() -> {
                    startRandomPlayback();
                    // Timer startet in onPrepared falls noch nicht aktiv;
                    // hier sicherheitshalber auch direkt (doppelt-sicher):
                    if (!isTimerRunning) restartTimer();
                });
            } else {
                // Fortsetzen nach Pause: TTS → dann resume
                speakTimeAndThen(() -> {
                    resumePlayback();
                    restartTimer();
                });
            }
        }
    }

    /**
     * Spricht die aktuelle Uhrzeit (mit Track-Lautstärke) und ruft danach {@code action} aus.
     * Falls TTS deaktiviert oder nicht verfügbar, wird {@code action} sofort ausgeführt.
     */
    private void speakTimeAndThen(Runnable action) {
        if (ttsHelper != null && prefsManager.isTtsEnabled()) {
            ttsHelper.speakCurrentTime(currentVolume, action);
        } else {
            action.run();
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
     * Falls der MediaPlayer nach langer Pause in einem ungültigen Zustand ist,
     * wird er freigegeben und der Track neu geladen (Recovery).
     */
    public void resumePlayback() {
        Log.d(TAG, "resumePlayback() – isPlaying=" + isPlaying
                + ", hasPlayer=" + (mediaPlayer != null)
                + ", currentTrack=" + (currentTrack != null ? currentTrack.title : "null")
                + ", isFadingOut=" + isFadingOut
                + ", wakeLockHeld=" + (serviceWakeLock != null && serviceWakeLock.isHeld()));
        if (mediaPlayer != null && !isPlaying) {
            // Audio Focus erneut anfordern
            int result = audioManager.requestAudioFocus(audioFocusRequest);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio Focus nicht erhalten in resumePlayback() – result=" + result);
                return;
            }
            // Lautstärke wiederherstellen – nach Fade-out ist sie nahe 0
            mediaPlayer.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));
            try {
                mediaPlayer.start();
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException in resumePlayback() – Player ungültig nach langer Pause", e);
                // Recovery: Player freigeben und Track neu laden
                Log.d(TAG, "resumePlayback Recovery: starte Track neu – " + (currentTrack != null ? currentTrack.title : "null"));
                releaseMediaPlayer();
                if (currentTrack != null) {
                    playTrackInternal(currentTrack);
                } else {
                    startRandomPlayback();
                }
                return;
            }
            isPlaying = true;
            // WakeLock wieder acquirieren da wir aktiv spielen
            acquireServiceWakeLock();
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
            showNotification();

            if (callback != null) {
                callback.onPlaybackStateChanged(true);
            }
            Log.d(TAG, "resumePlayback() erfolgreich – " + (currentTrack != null ? currentTrack.title : "?"));
        } else {
            Log.w(TAG, "resumePlayback() ignoriert: isPlaying=" + isPlaying
                    + ", mediaPlayer=" + (mediaPlayer != null ? "vorhanden" : "null"));
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

    // ===== Meditation =====

    /**
     * Schaltet die 4-7-8 Atemübung ein oder aus.
     * Beim Einschalten wird die Musikwiedergabe pausiert.
     * Beim Ausschalten kehrt der Service in den normalen Schlafmodus zurück.
     */
    public void toggleMeditation() {
        if (meditationController != null && meditationController.isRunning()) {
            stopMeditation();
        } else {
            startMeditation();
        }
    }

    /** Startet die Atemübung (pausiert laufende Wiedergabe). */
    public void startMeditation() {
        Log.d(TAG, "startMeditation()");

        // Sleep-Timer stoppen – darf während Meditation nicht die Notification überschreiben
        stopTimerInternal();

        // Musik anhalten und Audio-Focus freigeben, damit Meditations-Töne ungestört spielen
        if (isPlaying) {
            pausePlayback();
        }
        abandonAudioFocus();

        // Lautstärke: mindestens 30%, damit die Töne deutlich hörbar sind
        float vol = Math.max(0.30f, currentVolume);
        meditationController.start(vol);

        // Sitzung für heute loggen
        meditationLog.logToday();

        // Notification aktualisieren
        showMeditationNotification();
        // Die MeditationActivity wird von MainActivity.onMeditationStateChanged() gestartet,
        // da Activity-Starts aus dem Service auf Android 10+ eingeschränkt sind.
    }

    /** Stoppt die Atemübung. */
    public void stopMeditation() {
        Log.d(TAG, "stopMeditation()");
        meditationController.stop();
        if (callback != null) {
            callback.onMeditationStateChanged(false,
                    MeditationController.Phase.FINISHED, 0);
        }
        // Schlafmodus-Notification wiederherstellen
        showSleepModeNotification();
    }

    /** Gibt zurück ob die Atemübung gerade läuft. */
    public boolean isMeditating() {
        return meditationController != null && meditationController.isRunning();
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
            mediaPlayer.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));
        }
    }

    /**
     * Setzt die Timer-Dauer und startet den Timer.
     * Gibt beim Start die aktuelle Uhrzeit per Sprachsynthese aus.
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
                    float fadedVolume = effectiveVolume(volumeBeforeFade * fadeFactor);
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

                // Lautstärke wiederherstellen BEVOR pausiert wird,
                // damit beim nächsten Resume nicht der Fade-out-Wert (~0) im Player steckt
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));
                }

                // Wiedergabe pausieren (nicht komplett stoppen)
                pausePlayback();

                // WakeLock freigeben: als Foreground-Service bleiben wir am Leben,
                // der Lock ist im Schlafmodus unnötig und würde den Akku belasten.
                releaseServiceWakeLock();

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

                Log.d(TAG, "Sleep-Mode aktiv: WakeLock freigegeben, Foreground-Notification aktiv. "
                        + "MediaSession empfängt weiter Kopfhörer-Events."
                        + " | currentTrack=" + (currentTrack != null ? currentTrack.title : "null")
                        + " | mediaPlayer=" + (mediaPlayer != null ? "vorhanden" : "null"));
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
    }    private void stopTimerInternal() {
        if (sleepTimer != null) {
            sleepTimer.cancel();
            sleepTimer = null;
        }
        // Falls ein Fade-out lief, Lautstärke wiederherstellen
        if (isFadingOut && mediaPlayer != null) {
            mediaPlayer.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));
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
                    mediaPlayer.setVolume(effectiveVolume(currentVolume) * 0.3f,
                            effectiveVolume(currentVolume) * 0.3f);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                // Focus zurück → Lautstärke wiederherstellen
                if (mediaPlayer != null) {
                    mediaPlayer.setVolume(effectiveVolume(currentVolume), effectiveVolume(currentVolume));
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
     * Zeigt die "Schlafmodus"-Notification und hält den Service im Foreground.     * Wird aufgerufen wenn der Sleep-Timer abläuft und Wiedergabe pausiert wurde.
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

    /**
     * Zeigt die Meditations-Notification und hält den Service im Foreground.
     */
    private void showMeditationNotification() {
        Notification notification = NotificationHelper.buildMeditationNotification(
                this, mediaSession,
                "Einatmen 4s · Halten 7s · Ausatmen 8s · Doppelklick = Ende");
        startForeground(NotificationHelper.NOTIFICATION_ID, notification);
        Log.d(TAG, "Meditations-Notification aktiv");
    }

    /** Acquiriert den Service-WakeLock mit 10-Minuten-Timeout (idempotent).
     *  Ein Timeout ist wichtig: ohne ihn kann es auf Android 10+ zu einem
     *  "WakeLock finalized while still held"-Crash kommen falls der Service
     *  unerwartet zerstört wird. */
    private void acquireServiceWakeLock() {
        if (serviceWakeLock != null && !serviceWakeLock.isHeld()) {
            // Timeout: 10 Minuten. Der Lock wird in stopPlayback() / releaseServiceWakeLock()
            // vorher freigegeben; der Timeout ist nur die letzte Absicherung.
            serviceWakeLock.acquire(10 * 60 * 1000L);
            Log.d(TAG, "Service-WakeLock acquired (10-min timeout)"
                    + " | isPlaying=" + isPlaying
                    + " | timerRunning=" + isTimerRunning);
        }
    }

    /** Gibt den Service-WakeLock frei (idempotent). */
    private void releaseServiceWakeLock() {
        if (serviceWakeLock != null && serviceWakeLock.isHeld()) {
            serviceWakeLock.release();
            Log.d(TAG, "Service-WakeLock released");
        }
    }

    /**
     * Berechnet die effektive Lautstärke unter Berücksichtigung des Track-Gains.
     * Ergebnis ist immer im Bereich [0.0, 1.0].
     */
    private float effectiveVolume(float base) {
        return Math.max(0.0f, Math.min(1.0f, base * currentTrackGain));
    }


    /** Gibt den NormalizationStore zurück (für die Activity). */
    public NormalizationStore getNormalizationStore() {
        return normalizationStore;
    }

    /**
     * Aktualisiert den Kompressor – Stub für Kompatibilität mit Settings-Dialog.
     * DynamicsProcessing wurde entfernt (verursachte Knackser + Audio-Mute auf diesem Gerät).
     */
    public void updateCompressor() {
        Log.d(TAG, "updateCompressor: DynamicsProcessing nicht aktiv");
    }
}

