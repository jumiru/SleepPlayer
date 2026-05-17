package com.example.sleepplayer;

import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.Settings;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.util.List;
import java.util.Locale;

/**
 * Haupt-Activity für SleepPlayer.
 *
 * Zeigt:
 * - Aktuellen Track (Titel, Künstler)
 * - Play/Pause und Skip-Buttons
 * - Lautstärke-SeekBar (logarithmisch, feine Schritte im leisen Bereich)
 * - Sleep-Timer mit Auswahl und Restzeit-Anzeige
 * - Liste aller verfügbaren Audio-Tracks
 */
public class MainActivity extends AppCompatActivity implements PlaybackService.PlaybackCallback {

    // Timer-Optionen in Minuten
    private static final int[] TIMER_OPTIONS = {5, 10, 15, 20, 30, 45, 60, 90, 120};

    /** Schrittweite pro Hardware-Lautstärketaste (bezogen auf SEEK_BAR_MAX = 200). */
    private static final int VOLUME_KEY_STEP = 4;

    // System-Audio
    private AudioManager audioManager;
    private ContentObserver volumeObserver;
    private int lastSystemVolume = -1;

    // Views
    private TextView tvTrackName;
    private TextView tvTrackArtist;
    private ImageView ivAlbumArt;
    private ImageButton btnPlayPause;
    private ImageButton btnSkip;
    private ImageButton btnRestartTrack;
    private ImageButton btnSettings;
    private MaterialSwitch switchRandom;
    private SeekBar seekVolume;
    private TextView tvVolumePercent;
    private TextView tvVolumeLabel;
    private Spinner spinnerTimer;
    private TextView tvTimerRemaining;
    private ImageButton btnMeditation;
    private TextView tvTrackCount;
    private RecyclerView recyclerTracks;
    private EditText etSearch;

    // Vollbild-Overlay für Album Art
    private View overlayAlbumArt;
    private ImageView ivAlbumArtFull;

    // Fortschrittsbalken
    private SeekBar seekProgress;
    private TextView tvCurrentPosition;
    private TextView tvTrackDurationLabel;

    /** Handler + Runnable für die sekündliche Fortschritts-Aktualisierung. */
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            progressHandler.postDelayed(this, 500);
        }
    };

    // Service
    private PlaybackService playbackService;
    private boolean isBound = false;

    /**
     * Aktion die ausgeführt wird sobald der Service verbunden ist.
     * Wird gesetzt wenn der Nutzer Play/Skip drückt bevor der Service bereit ist.
     */
    private Runnable pendingServiceAction = null;

    // Adapter
    private TrackAdapter trackAdapter;

    // Prefs
    private PrefsManager prefsManager;

    // Normalisierung
    private NormalizationStore normalizationStore;

    // Permission Launcher
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadTracks();
                } else {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    // Notification Permission Launcher
    private final ActivityResultLauncher<String> requestNotificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // Optional, Notification funktioniert auch ohne auf manchen Geräten
            });

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            PlaybackService.LocalBinder localBinder = (PlaybackService.LocalBinder) binder;
            playbackService = localBinder.getService();
            isBound = true;

            playbackService.setCallback(MainActivity.this);

            // UI mit aktuellem Zustand synchronisieren
            syncUI();

            // Ausstehende Aktion ausführen (z.B. Play-Druck während Service noch startete)
            if (pendingServiceAction != null) {
                pendingServiceAction.run();
                pendingServiceAction = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            playbackService = null;
            isBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // StrictMode im Debug-Build: zeigt Main-Thread I/O sofort im Logcat
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build());
        }

        setContentView(R.layout.activity_main);

        // Logging initialisieren
        LogManager logManager = LogManager.getInstance(this);
        logManager.log("MainActivity", "App gestartet – initialisiere...");

        prefsManager = new PrefsManager(this);
        normalizationStore = new NormalizationStore(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Lautstärketasten sollen den Medien-Stream steuern
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        initViews();
        setupVolumeControl();
        setupTimerSpinner();
        setupRandomSwitch();
        setupSettingsButton();
        setupTrackList();
        checkPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // An den Service binden (falls er läuft)
        Intent intent = new Intent(this, PlaybackService.class);
        bindService(intent, serviceConnection, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerVolumeObserver();
        // Fortschrittsbalken starten falls gerade abgespielt wird
        if (isBound && playbackService != null && playbackService.isPlaying()) {
            progressHandler.post(progressRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterVolumeObserver();
        progressHandler.removeCallbacks(progressRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        pendingServiceAction = null; // ausstehende Aktionen verwerfen
        if (isBound) {
            if (playbackService != null) {
                playbackService.setCallback(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    /**
     * ContentObserver für Systemlautstärke-Änderungen.
     * Funktioniert auch wenn die MediaSession die Tasten abfängt.
     * Erkennt die Richtung der Änderung und verschiebt den in-app Slider
     * um VOLUME_KEY_STEP – so bleibt die logarithmische Feinregelung erhalten.
     */
    private void registerVolumeObserver() {
        lastSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                int newSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (newSystemVolume == lastSystemVolume) return;

                boolean volumeUp = newSystemVolume > lastSystemVolume;
                lastSystemVolume = newSystemVolume;

                int current = seekVolume.getProgress();
                int next = volumeUp
                        ? Math.min(VolumeHelper.SEEK_BAR_MAX, current + VOLUME_KEY_STEP)
                        : Math.max(0, current - VOLUME_KEY_STEP);

                if (next != current) {
                    seekVolume.setProgress(next);
                    updateVolumeLabel(next);
                    float volume = VolumeHelper.progressToVolume(next);
                    if (isBound && playbackService != null) {
                        playbackService.setVolume(volume);
                    }
                    prefsManager.saveVolume(next);
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.CONTENT_URI, true, volumeObserver);
    }

    private void unregisterVolumeObserver() {
        if (volumeObserver != null) {
            getContentResolver().unregisterContentObserver(volumeObserver);
            volumeObserver = null;
        }
    }

    // ===== Initialisierung =====

    private void initViews() {
        tvTrackName = findViewById(R.id.tvTrackName);
        tvTrackArtist = findViewById(R.id.tvTrackArtist);
        ivAlbumArt = findViewById(R.id.ivAlbumArt);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnSkip = findViewById(R.id.btnSkip);
        btnRestartTrack = findViewById(R.id.btnRestartTrack);
        btnSettings = findViewById(R.id.btnSettings);
        switchRandom = findViewById(R.id.switchRandom);
        seekVolume = findViewById(R.id.seekVolume);
        tvVolumePercent = findViewById(R.id.tvVolumePercent);
        tvVolumeLabel = findViewById(R.id.tvVolumeLabel);
        spinnerTimer = findViewById(R.id.spinnerTimer);
        tvTimerRemaining = findViewById(R.id.tvTimerRemaining);
        btnMeditation = findViewById(R.id.btnMeditation);
        tvTrackCount = findViewById(R.id.tvTrackCount);
        recyclerTracks = findViewById(R.id.recyclerTracks);
        etSearch = findViewById(R.id.etSearch);
        overlayAlbumArt = findViewById(R.id.overlayAlbumArt);
        ivAlbumArtFull = findViewById(R.id.ivAlbumArtFull);
        seekProgress = findViewById(R.id.seekProgress);
        tvCurrentPosition = findViewById(R.id.tvCurrentPosition);
        tvTrackDurationLabel = findViewById(R.id.tvTrackDurationLabel);

        btnPlayPause.setOnClickListener(v -> onPlayPauseClicked());
        btnSkip.setOnClickListener(v -> onSkipClicked());
        btnRestartTrack.setOnClickListener(v -> onRestartTrackClicked());

        // Meditations-Button: togglet die 4-7-8 Atemübung (wie Doppelklick am Kopfhörer)
        btnMeditation.setOnClickListener(v -> {
            ensureServiceStarted();
            if (isBound && playbackService != null) {
                playbackService.toggleMeditation();
            } else {
                pendingServiceAction = () -> playbackService.toggleMeditation();
            }
        });

        // Klick auf kleines Album-Art → Vollbild-Overlay öffnen
        ivAlbumArt.setOnClickListener(v -> showAlbumArtOverlay());

        // Klick irgendwo im Overlay → schließen
        overlayAlbumArt.setOnClickListener(v -> hideAlbumArtOverlay());

        // Fortschrittsbalken: Nutzer kann Position manuell setzen
        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}
            @Override public void onStartTrackingTouch(SeekBar s) {
                // Aktualisierung pausieren während der Nutzer zieht
                progressHandler.removeCallbacks(progressRunnable);
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (isBound && playbackService != null) {
                    int duration = playbackService.getDuration();
                    if (duration > 0) {
                        int seekMs = (int) ((long) s.getProgress() * duration / 1000);
                        playbackService.seekTo(seekMs);
                    }
                }
                // Aktualisierung wieder starten
                progressHandler.post(progressRunnable);
            }
        });

        // Suchfeld: Liste live einschränken
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                trackAdapter.filter(s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    private void setupVolumeControl() {
        seekVolume.setMax(VolumeHelper.SEEK_BAR_MAX);

        // Gespeicherten Wert laden
        int savedProgress = prefsManager.getVolume();
        seekVolume.setProgress(savedProgress);
        updateVolumeLabel(savedProgress);

        seekVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float volume = VolumeHelper.progressToVolume(progress);
                    if (isBound && playbackService != null) {
                        playbackService.setVolume(volume);
                    }
                    updateVolumeLabel(progress);
                    prefsManager.saveVolume(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateVolumeLabel(int progress) {
        int percent = VolumeHelper.progressToPercent(progress);
        tvVolumePercent.setText(String.format(Locale.getDefault(), "%d%%", percent));
        tvVolumeLabel.setText(String.format(Locale.getDefault(),
                getString(R.string.volume_label), percent));
    }

    private void setupTimerSpinner() {
        String[] timerLabels = new String[TIMER_OPTIONS.length];
        for (int i = 0; i < TIMER_OPTIONS.length; i++) {
            timerLabels[i] = TIMER_OPTIONS[i] + " " + getString(R.string.minutes_suffix);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, timerLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTimer.setAdapter(adapter);

        // Gespeicherten Wert auswählen
        int savedMinutes = prefsManager.getTimerMinutes();
        for (int i = 0; i < TIMER_OPTIONS.length; i++) {
            if (TIMER_OPTIONS[i] == savedMinutes) {
                spinnerTimer.setSelection(i);
                break;
            }
        }

        spinnerTimer.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int minutes = TIMER_OPTIONS[pos];
                prefsManager.saveTimerMinutes(minutes);
                if (isBound && playbackService != null && playbackService.isTimerRunning()) {
                    // Timer mit neuer Dauer neu starten
                    playbackService.startTimer(minutes);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupTrackList() {
        trackAdapter = new TrackAdapter();
        trackAdapter.setNormalizationStore(normalizationStore);
        recyclerTracks.setLayoutManager(new LinearLayoutManager(this));
        recyclerTracks.setAdapter(trackAdapter);

        trackAdapter.setOnTrackClickListener(track -> {
            ensureServiceStarted();
            if (isBound && playbackService != null) {
                playbackService.playTrack(track);
            } else {
                pendingServiceAction = () -> playbackService.playTrack(track);
            }
        });

        // Longpress → Referenz-Track setzen
        trackAdapter.setOnTrackLongClickListener(track -> showReferenceTrackMenu(track));
    }

    private void setupRandomSwitch() {
        // Gespeicherten Modus laden
        boolean savedRandom = prefsManager.isRandomMode();
        switchRandom.setChecked(savedRandom);
        switchRandom.setText(savedRandom ? R.string.mode_random : R.string.mode_sequential);

        switchRandom.setOnCheckedChangeListener((button, isChecked) -> {
            prefsManager.saveRandomMode(isChecked);
            button.setText(isChecked ? R.string.mode_random : R.string.mode_sequential);
            if (isBound && playbackService != null) {
                playbackService.setRandomMode(isChecked);
            }
        });
    }

    // ===== Settings-Button / Ordner-Auswahl =====

    private void setupSettingsButton() {
        btnSettings.setOnClickListener(v -> openSettingsActivity());
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, SettingsActivity.class);
        startActivity(intent);
    }

    // ===== Permissions =====

    private void checkPermissions() {
        // Audio Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            } else {
                loadTracks();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            } else {
                loadTracks();
            }
        }

        // Notification Permission (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    private void loadTracks() {
        // MediaStore-Query im Hintergrund laden (kein Main-Thread I/O)
        new Thread(() -> {
            TrackSelector selector = new TrackSelector(this);
            selector.clearCache();
            List<TrackSelector.TrackInfo> tracks = selector.getAllTracks();
            runOnUiThread(() -> {
                trackAdapter.setTracks(tracks);
                tvTrackCount.setText(String.format(Locale.getDefault(),
                        getString(R.string.tracks_found), tracks.size()));
                if (tracks.isEmpty()) {
                    tvTrackName.setText(R.string.no_audio_files);
                } else {
                    // Zufälligen Track vorauswählen und im UI anzeigen,
                    // falls gerade nichts spielt – so sieht der Nutzer sofort
                    // welcher Track beim ersten Play-Druck gestartet wird.
                    boolean nothingPlaying = !isBound
                            || playbackService == null
                            || playbackService.getCurrentTrack() == null;
                    if (nothingPlaying) {
                        int idx = (int) (Math.random() * tracks.size());
                        TrackSelector.TrackInfo preselected = tracks.get(idx);
                        tvTrackName.setText(preselected.title);
                        tvTrackArtist.setText(preselected.artist);
                        updateAlbumArt(preselected);
                    }
                }
            });
        }, "LoadTracks").start();
    }

    // ===== Playback Controls =====

    private void onPlayPauseClicked() {
        ensureServiceStarted();
        if (isBound && playbackService != null) {
            playbackService.togglePlayPause();
        } else {
            // Service startet gerade async – Aktion merken und in onServiceConnected ausführen
            pendingServiceAction = () -> playbackService.togglePlayPause();
        }
    }

    private void onSkipClicked() {
        ensureServiceStarted();
        if (isBound && playbackService != null) {
            playbackService.skipToNext();
        } else {
            pendingServiceAction = () -> playbackService.skipToNext();
        }
    }

    /** Setzt den aktuellen Titel auf Position 0 zurück (Anfang des Tracks). */
    private void onRestartTrackClicked() {
        ensureServiceStarted();
        if (isBound && playbackService != null) {
            playbackService.seekTo(0);
        } else {
            pendingServiceAction = () -> playbackService.seekTo(0);
        }
    }

    /**
     * Stellt sicher, dass der Service gestartet und gebunden ist.
     */
    private void ensureServiceStarted() {
        Intent intent = new Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        if (!isBound) {
            bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        }
    }

    // ===== PlaybackCallback Implementierung =====

    @Override
    public void onTrackChanged(TrackSelector.TrackInfo track) {
        runOnUiThread(() -> {
            if (track != null) {
                tvTrackName.setText(track.title);
                tvTrackArtist.setText(track.artist);
                updateAlbumArt(track);
            }
            // Fortschritt zurücksetzen bis die neue Position eintrifft
            seekProgress.setProgress(0);
            tvCurrentPosition.setText("0:00");
            tvTrackDurationLabel.setText(formatMs(track != null ? track.duration : 0));
        });
    }

    /**
     * Zeigt ein Kontextmenü zum Setzen des Referenz-Tracks und Normalisierungsoptionen (nach Longpress).
     */
    private void showReferenceTrackMenu(TrackSelector.TrackInfo track) {
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(getString(R.string.norm_set_ref_confirm));
        items.add(getString(R.string.norm_single_track));

        new AlertDialog.Builder(this)
                .setTitle(track.title)
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String chosen = items.get(which);
                    if (chosen.equals(getString(R.string.norm_set_ref_confirm))) {
                        normalizationStore.saveReferenceTrack(track.uri.toString(), track.title);
                        trackAdapter.notifyDataSetChanged();
                        Toast.makeText(this, getString(R.string.norm_ref_set, track.title), Toast.LENGTH_SHORT).show();
                    } else if (chosen.equals(getString(R.string.norm_single_track))) {
                        startSingleTrackNormalization(track);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Normalisiert einen einzelnen Track gegen den Referenz-Track.
     */
    private void startSingleTrackNormalization(TrackSelector.TrackInfo track) {
        String refUri = normalizationStore.getReferenceTrackUri();
        if (refUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.norm_menu_normalize))
                    .setMessage(getString(R.string.norm_no_ref))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }

        // Fortschritts-Dialog
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.norm_progress_title)
                .setMessage(track.title)
                .setView(pb)
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            // Referenz-RMS
            float refDb = Float.NaN;
            if (track.uri.toString().equals(refUri)) {
                refDb = AudioAnalyzer.analyzeRmsDb(this, track.uri, p -> runOnUiThread(() -> pb.setProgress(p)));
            } else {
                // Analyse der Referenz (schnell, nur 60s)
                TrackSelector selector = isBound && playbackService != null
                        ? playbackService.getTrackSelector() : new TrackSelector(this);
                for (TrackSelector.TrackInfo t : selector.getAllTracks()) {
                    if (t.uri.toString().equals(refUri)) {
                        refDb = AudioAnalyzer.analyzeRmsDb(this, t.uri);
                        break;
                    }
                }
                if (Float.isNaN(refDb)) {
                    runOnUiThread(() -> { dlg.dismiss();
                        Toast.makeText(this, R.string.norm_ref_analysis_failed, Toast.LENGTH_LONG).show(); });
                    return;
                }
                float trackDb = AudioAnalyzer.analyzeRmsDb(this, track.uri,
                        p -> runOnUiThread(() -> pb.setProgress(p)));
                if (Float.isNaN(trackDb)) {
                    runOnUiThread(() -> { dlg.dismiss();
                        Toast.makeText(this, R.string.norm_track_analysis_failed, Toast.LENGTH_LONG).show(); });
                    return;
                }
                float gain = NormalizationStore.computeGainMultiplier(refDb, trackDb);
                normalizationStore.saveGain(track.uri.toString(), gain);
                String gainStr = String.format(java.util.Locale.getDefault(), "%.2f", gain);
                runOnUiThread(() -> { dlg.dismiss(); trackAdapter.notifyDataSetChanged();
                    Toast.makeText(this, getString(R.string.norm_single_done, gainStr), Toast.LENGTH_LONG).show(); });
                return;
            }
            // Track ist selbst der Referenz-Track → Gain 1.0
            normalizationStore.saveGain(track.uri.toString(), 1.0f);
            runOnUiThread(() -> { dlg.dismiss(); trackAdapter.notifyDataSetChanged();
                Toast.makeText(this, getString(R.string.norm_single_done, "1.00"), Toast.LENGTH_SHORT).show(); });
        }, "SingleNormThread").start();
    }

    /**
     * Startet die Normalisierungs-Analyse für alle Tracks im Hintergrund.
     * Zeigt einen Fortschritts-Dialog und speichert die Gains danach.
     */
    private void startNormalization() {
        String refUri = normalizationStore.getReferenceTrackUri();
        if (refUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.norm_menu_normalize))
                    .setMessage(getString(R.string.norm_no_ref))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        // Fortschritts-Dialog aufbauen (Tracks werden im Hintergrund geladen)
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        progressBar.setPadding(padding, padding, padding, padding);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.norm_progress_title)
                .setMessage(getString(R.string.norm_progress_msg, 0, 0))
                .setView(progressBar)
                .setCancelable(false)
                .create();
        progressDialog.show();

        // Alles im Hintergrund – kein Main-Thread I/O!
        new Thread(() -> {
            // Track-Liste laden (MediaStore-Query → muss im Hintergrund laufen!)
            TrackSelector selector = isBound && playbackService != null
                    ? playbackService.getTrackSelector()
                    : new TrackSelector(this);
            List<TrackSelector.TrackInfo> allTracks = selector.getAllTracks();

            if (allTracks.isEmpty()) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, R.string.norm_no_tracks, Toast.LENGTH_SHORT).show();
                });
                return;
            }

            final int total = allTracks.size();
            runOnUiThread(() -> {
                progressBar.setMax(total);
                progressDialog.setMessage(getString(R.string.norm_progress_msg, 0, total));
            });

            // 1) Referenz-RMS berechnen
            TrackSelector.TrackInfo refTrack = null;
            for (TrackSelector.TrackInfo t : allTracks) {
                if (t.uri.toString().equals(refUri)) {
                    refTrack = t;
                    break;
                }
            }
            if (refTrack == null) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, R.string.norm_ref_not_found, Toast.LENGTH_LONG).show();
                });
                return;
            }

            float refDb = AudioAnalyzer.analyzeRmsDb(this, refTrack.uri);
            if (Float.isNaN(refDb)) {
                runOnUiThread(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(this, R.string.norm_ref_analysis_failed, Toast.LENGTH_LONG).show();
                });
                return;
            }

            // 2) Alle anderen Tracks analysieren
            int analyzed = 0;
            int failed = 0;

            for (TrackSelector.TrackInfo track : allTracks) {
                analyzed++;
                final int progress = analyzed;

                runOnUiThread(() -> {
                    progressBar.setProgress(progress);
                    progressDialog.setMessage(
                            getString(R.string.norm_progress_msg, progress, total));
                });

                String trackUri = track.uri.toString();
                if (trackUri.equals(refUri)) {
                    normalizationStore.saveGain(trackUri, 1.0f);
                    continue;
                }

                float trackDb = AudioAnalyzer.analyzeRmsDb(this, track.uri);
                if (Float.isNaN(trackDb)) {
                    failed++;
                    android.util.Log.w("MainActivity", "Analyse fehlgeschlagen: " + track.title);
                    continue;
                }

                float gain = NormalizationStore.computeGainMultiplier(refDb, trackDb);
                normalizationStore.saveGain(trackUri, gain);
            }

            final int finalFailed = failed;
            runOnUiThread(() -> {
                progressDialog.dismiss();
                trackAdapter.notifyDataSetChanged();
                String msg = getString(R.string.norm_done, total - finalFailed, total);
                if (finalFailed > 0) {
                    msg += " (" + getString(R.string.norm_failed_count, finalFailed) + ")";
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });

        }, "NormalizationThread").start();
    }

    /**
     * Wird aufgerufen wenn der Benutzer einen Ordner ausgewählt hat.
     */
    private void onFolderSelected(Uri treeUri) {
        String displayName = extractFolderDisplayName(treeUri);
        prefsManager.saveFolderUri(treeUri.toString());
        prefsManager.saveFolderDisplayName(displayName);
        loadTracks();
        Toast.makeText(this, getString(R.string.settings_folder_title) + ": " + displayName,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Extrahiert einen lesbaren Ordnernamen aus einem SAF Tree-URI.
     * z.B. "content://...externalstorage.../tree/primary%3AMusic%2FSleep"
     *  → "Music/Sleep"
     */
    private String extractFolderDisplayName(Uri treeUri) {
        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/")) {
            String docId = path.substring(6); // nach "/tree/"
            // docId: "primary:Music/Sleep" oder "XXXX-XXXX:Folder"
            int colonIndex = docId.indexOf(':');
            if (colonIndex >= 0 && colonIndex < docId.length() - 1) {
                return docId.substring(colonIndex + 1);
            }
        }
        // Fallback: letztes Segment des URI
        return treeUri.getLastPathSegment();
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            btnPlayPause.setContentDescription(getString(isPlaying ? R.string.pause : R.string.play));
            if (isPlaying) {
                progressHandler.post(progressRunnable);
            } else {
                progressHandler.removeCallbacks(progressRunnable);
            }
        });
    }

    @Override
    public void onTimerTick(long millisRemaining) {
        runOnUiThread(() -> {
            tvTimerRemaining.setText(formatTime(millisRemaining));
        });
    }

    @Override
    public void onTimerFinished() {
        runOnUiThread(() -> {
            tvTimerRemaining.setText(R.string.timer_off);
            btnPlayPause.setImageResource(R.drawable.ic_play);
            btnPlayPause.setContentDescription(getString(R.string.play));
        });
    }

    @Override
    public void onMeditationStateChanged(boolean active,
            MeditationController.Phase phase, int cycle) {
        runOnUiThread(() -> {
            updateMeditationButtonState(active);
            if (active && phase == MeditationController.Phase.PREPARE) {
                openMeditationActivity();
            }
        });
    }

    // ===== UI Sync =====

    private void syncUI() {
        if (!isBound || playbackService == null) return;

        // Aktuellen Track anzeigen
        TrackSelector.TrackInfo track = playbackService.getCurrentTrack();
        if (track != null) {
            tvTrackName.setText(track.title);
            tvTrackArtist.setText(track.artist);
            updateAlbumArt(track);
        }

        // Play/Pause Button
        boolean playing = playbackService.isPlaying();
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        // Random Switch mit Service-Zustand synchronisieren
        boolean random = playbackService.isRandomMode();
        switchRandom.setOnCheckedChangeListener(null); // Listener kurz deaktivieren
        switchRandom.setChecked(random);
        switchRandom.setText(random ? R.string.mode_random : R.string.mode_sequential);
        switchRandom.setOnCheckedChangeListener((button, isChecked) -> {
            prefsManager.saveRandomMode(isChecked);
            button.setText(isChecked ? R.string.mode_random : R.string.mode_sequential);
            if (isBound && playbackService != null) {
                playbackService.setRandomMode(isChecked);
            }
        });

        // Timer
        if (playbackService.isTimerRunning()) {
            tvTimerRemaining.setText(formatTime(playbackService.getTimerMillisRemaining()));
        } else {
            tvTimerRemaining.setText(R.string.timer_off);
        }

        updateMeditationButtonState(playbackService.isMeditating());
    }

    // ===== Hilfsmethoden =====

    /** Aktualisiert Fortschrittsbalken und Zeitanzeigen. */
    private void updateProgress() {
        if (!isBound || playbackService == null) return;
        int position = playbackService.getCurrentPosition(); // ms
        int duration = playbackService.getDuration();        // ms
        if (duration > 0) {
            int progress = (int) ((long) position * 1000 / duration);
            seekProgress.setProgress(progress);
        } else {
            seekProgress.setProgress(0);
        }
        tvCurrentPosition.setText(formatMs(position));
        tvTrackDurationLabel.setText(formatMs(duration));
    }

    /** Formatiert Millisekunden als M:SS oder H:MM:SS. */
    private String formatMs(long ms) {
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    private void updateMeditationButtonState(boolean active) {
        btnMeditation.setAlpha(active ? 1.0f : 0.6f);
        if (active) {
            btnMeditation.setColorFilter(
                    ContextCompat.getColor(this, R.color.teal_200),
                    android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            btnMeditation.clearColorFilter();
        }
        btnMeditation.setContentDescription(
                getString(active ? R.string.meditation_stop : R.string.meditation_start));
    }

    private void openMeditationActivity() {
        Intent intent = new Intent(this, MeditationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    /**
     * Lädt das Album-Cover mit Glide.
     * Zeigt den Platzhalter wenn kein Cover vorhanden oder das Laden fehlschlägt.
     */
    private void updateAlbumArt(TrackSelector.TrackInfo track) {
        Uri artUri = track != null ? track.getAlbumArtUri() : null;
        Glide.with(this)
                .load(artUri)
                .placeholder(R.drawable.ic_album_placeholder)
                .error(R.drawable.ic_album_placeholder)
                .transition(DrawableTransitionOptions.withCrossFade(300))
                .centerCrop()
                .into(ivAlbumArt);

        // Overlay-Bild ebenfalls aktualisieren falls es gerade sichtbar ist
        if (overlayAlbumArt.getVisibility() == View.VISIBLE) {
            Glide.with(this)
                    .load(artUri)
                    .placeholder(R.drawable.ic_album_placeholder)
                    .error(R.drawable.ic_album_placeholder)
                    .into(ivAlbumArtFull);
        }
    }

    // ===== Album-Art Vollbild-Overlay =====

    private void showAlbumArtOverlay() {
        // Aktuelles Bild in das Vollbild-View laden
        TrackSelector.TrackInfo track = (isBound && playbackService != null)
                ? playbackService.getCurrentTrack() : null;
        Uri artUri = track != null ? track.getAlbumArtUri() : null;

        Glide.with(this)
                .load(artUri)
                .placeholder(R.drawable.ic_album_placeholder)
                .error(R.drawable.ic_album_placeholder)
                .into(ivAlbumArtFull);

        // Einblenden mit Fade-Animation
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(200);
        overlayAlbumArt.setVisibility(View.VISIBLE);
        overlayAlbumArt.startAnimation(fadeIn);
    }

    private void hideAlbumArtOverlay() {
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                overlayAlbumArt.setVisibility(View.GONE);
            }
        });
        overlayAlbumArt.startAnimation(fadeOut);
    }

    /** Zurück-Taste schließt zuerst das Overlay (falls offen). */
    @Override
    public void onBackPressed() {
        if (overlayAlbumArt != null && overlayAlbumArt.getVisibility() == View.VISIBLE) {
            hideAlbumArtOverlay();
        } else {
            super.onBackPressed();
        }
    }
}


