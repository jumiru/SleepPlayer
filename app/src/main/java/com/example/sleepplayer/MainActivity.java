package com.example.sleepplayer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

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

    // Views
    private TextView tvTrackName;
    private TextView tvTrackArtist;
    private ImageButton btnPlayPause;
    private ImageButton btnSkip;
    private SeekBar seekVolume;
    private TextView tvVolumePercent;
    private TextView tvVolumeLabel;
    private Spinner spinnerTimer;
    private TextView tvTimerRemaining;
    private TextView tvTrackCount;
    private RecyclerView recyclerTracks;
    private TextView tvFolderName;
    private MaterialButton btnSelectFolder;
    private MaterialButton btnClearFolder;

    // Service
    private PlaybackService playbackService;
    private boolean isBound = false;

    // Adapter
    private TrackAdapter trackAdapter;

    // Prefs
    private PrefsManager prefsManager;

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

    // Ordner-Picker Launcher (SAF = Storage Access Framework)
    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    onFolderSelected(uri);
                }
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
        setContentView(R.layout.activity_main);

        prefsManager = new PrefsManager(this);
        initViews();
        setupVolumeControl();
        setupTimerSpinner();
        setupFolderSelection();
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
    protected void onStop() {
        super.onStop();
        if (isBound) {
            if (playbackService != null) {
                playbackService.setCallback(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
    }

    /**
     * Fängt Hardware-Lautstärketasten ab und leitet sie an den App-eigenen
     * logarithmischen Volume-Slider weiter.
     * So bleibt die GUI immer aktuell und die feinen Schritte bleiben erhalten.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            // Nur beim Drücken reagieren (nicht beim Loslassen)
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int current = seekVolume.getProgress();
                int next = (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                        ? Math.min(VolumeHelper.SEEK_BAR_MAX, current + VOLUME_KEY_STEP)
                        : Math.max(0, current - VOLUME_KEY_STEP);

                if (next != current) {
                    seekVolume.setProgress(next);
                    float volume = VolumeHelper.progressToVolume(next);
                    if (isBound && playbackService != null) {
                        playbackService.setVolume(volume);
                    }
                    updateVolumeLabel(next);
                    prefsManager.saveVolume(next);
                }
            }
            // Event konsumieren → Systemlautstärke bleibt unverändert
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ===== Initialisierung =====

    private void initViews() {
        tvTrackName = findViewById(R.id.tvTrackName);
        tvTrackArtist = findViewById(R.id.tvTrackArtist);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnSkip = findViewById(R.id.btnSkip);
        seekVolume = findViewById(R.id.seekVolume);
        tvVolumePercent = findViewById(R.id.tvVolumePercent);
        tvVolumeLabel = findViewById(R.id.tvVolumeLabel);
        spinnerTimer = findViewById(R.id.spinnerTimer);
        tvTimerRemaining = findViewById(R.id.tvTimerRemaining);
        tvTrackCount = findViewById(R.id.tvTrackCount);
        recyclerTracks = findViewById(R.id.recyclerTracks);
        tvFolderName = findViewById(R.id.tvFolderName);
        btnSelectFolder = findViewById(R.id.btnSelectFolder);
        btnClearFolder = findViewById(R.id.btnClearFolder);

        btnPlayPause.setOnClickListener(v -> onPlayPauseClicked());
        btnSkip.setOnClickListener(v -> onSkipClicked());
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
        recyclerTracks.setLayoutManager(new LinearLayoutManager(this));
        recyclerTracks.setAdapter(trackAdapter);

        trackAdapter.setOnTrackClickListener(track -> {
            ensureServiceStarted();
            if (isBound && playbackService != null) {
                playbackService.playTrack(track);
            }
        });
    }

    // ===== Ordner-Auswahl =====

    private void setupFolderSelection() {
        // Gespeicherten Ordner anzeigen
        updateFolderDisplay();

        btnSelectFolder.setOnClickListener(v -> {
            // SAF Ordner-Picker öffnen
            folderPickerLauncher.launch(null);
        });

        btnClearFolder.setOnClickListener(v -> {
            // Ordner-Filter entfernen
            prefsManager.clearFolder();
            updateFolderDisplay();
            loadTracks();
        });
    }

    /**
     * Wird aufgerufen wenn der Benutzer einen Ordner ausgewählt hat.
     */
    private void onFolderSelected(Uri treeUri) {
        // Anzeigenamen aus dem URI extrahieren
        String displayName = extractFolderDisplayName(treeUri);

        // Speichern
        prefsManager.saveFolderUri(treeUri.toString());
        prefsManager.saveFolderDisplayName(displayName);

        // UI aktualisieren
        updateFolderDisplay();

        // Track-Liste mit neuem Filter neu laden
        loadTracks();
    }

    /**
     * Aktualisiert die Ordner-Anzeige im UI.
     */
    private void updateFolderDisplay() {
        String displayName = prefsManager.getFolderDisplayName();
        if (displayName != null) {
            tvFolderName.setText(displayName);
            btnClearFolder.setVisibility(View.VISIBLE);
        } else {
            tvFolderName.setText(R.string.folder_all);
            btnClearFolder.setVisibility(View.GONE);
        }
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
        TrackSelector selector = new TrackSelector(this);
        selector.clearCache(); // Cache leeren damit Ordner-Filter greift
        List<TrackSelector.TrackInfo> tracks = selector.getAllTracks();
        trackAdapter.setTracks(tracks);
        tvTrackCount.setText(String.format(Locale.getDefault(),
                getString(R.string.tracks_found), tracks.size()));

        if (tracks.isEmpty()) {
            tvTrackName.setText(R.string.no_audio_files);
        }
    }

    // ===== Playback Controls =====

    private void onPlayPauseClicked() {
        ensureServiceStarted();
        if (isBound && playbackService != null) {
            playbackService.togglePlayPause();
        }
    }

    private void onSkipClicked() {
        ensureServiceStarted();
        if (isBound && playbackService != null) {
            playbackService.skipToNext();
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
            }
        });
    }

    @Override
    public void onPlaybackStateChanged(boolean isPlaying) {
        runOnUiThread(() -> {
            btnPlayPause.setImageResource(isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
            btnPlayPause.setContentDescription(getString(isPlaying ? R.string.pause : R.string.play));
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

    // ===== UI Sync =====

    private void syncUI() {
        if (!isBound || playbackService == null) return;

        // Aktuellen Track anzeigen
        TrackSelector.TrackInfo track = playbackService.getCurrentTrack();
        if (track != null) {
            tvTrackName.setText(track.title);
            tvTrackArtist.setText(track.artist);
        }

        // Play/Pause Button
        boolean playing = playbackService.isPlaying();
        btnPlayPause.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);

        // Timer
        if (playbackService.isTimerRunning()) {
            tvTimerRemaining.setText(formatTime(playbackService.getTimerMillisRemaining()));
        } else {
            tvTimerRemaining.setText(R.string.timer_off);
        }
    }

    // ===== Hilfsmethoden =====

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }
}

