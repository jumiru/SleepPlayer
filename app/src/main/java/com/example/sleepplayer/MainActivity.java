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
        btnSettings.setOnClickListener(v -> showSettingsMenu());
    }

    /**
     * Zeigt ein PopupMenu mit Ordner-Auswahl-Optionen, TTS-Toggle und Normalisierung.
     */
    private void showSettingsMenu() {
        PopupMenu popup = new PopupMenu(this, btnSettings);

        // Aktuellen Ordner als erstes (deaktiviertes) Item anzeigen
        String folderName = prefsManager.getFolderDisplayName();
        String folderLabel = (folderName != null)
                ? getString(R.string.settings_folder_title) + ": " + folderName
                : getString(R.string.settings_folder_title) + ": " + getString(R.string.folder_all);
        popup.getMenu().add(0, 0, 0, folderLabel).setEnabled(false);
        popup.getMenu().add(0, 1, 1, getString(R.string.settings_folder_select));
        popup.getMenu().add(0, 2, 2, getString(R.string.settings_folder_clear))
                .setEnabled(folderName != null);

        // TTS-Toggle
        boolean ttsEnabled = prefsManager.isTtsEnabled();
        popup.getMenu().add(0, 3, 3,
                ttsEnabled ? getString(R.string.tts_on) : getString(R.string.tts_off));

        // Normalisierung – "Normalisieren" immer aktiv (zeigt eigene Hinweise wenn nötig),
        // "Zurücksetzen" nur aktiv wenn überhaupt Gain-Daten vorhanden
        popup.getMenu().add(0, 4, 4, getString(R.string.norm_menu_normalize))
                .setEnabled(true);
        popup.getMenu().add(0, 5, 5, getString(R.string.norm_menu_reset))
                .setEnabled(!normalizationStore.getAllGains().isEmpty());

        // Meditations-Effekte
        popup.getMenu().add(0, 7, 7, "🎛️ Meditations-Effekte…");

        // Referenz-Info (deaktiviert, nur zur Info)
        String refTitle = normalizationStore.getReferenceTrackTitle();
        if (refTitle != null) {
            popup.getMenu().add(0, 6, 6, "⭐ " + getString(R.string.norm_ref_label) + ": " + refTitle)
                    .setEnabled(false);
        } else {
            popup.getMenu().add(0, 6, 6, getString(R.string.norm_ref_hint))
                    .setEnabled(false);
        }

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    folderPickerLauncher.launch(null);
                    return true;
                case 2:
                    prefsManager.clearFolder();
                    loadTracks();
                    Toast.makeText(this, R.string.folder_all, Toast.LENGTH_SHORT).show();
                    return true;
                case 3:
                    boolean newTts = !prefsManager.isTtsEnabled();
                    prefsManager.saveTtsEnabled(newTts);
                    Toast.makeText(this,
                            newTts ? R.string.tts_on : R.string.tts_off,
                            Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    startNormalization();
                    return true;
                case 5:
                    normalizationStore.clearGains();
                    trackAdapter.notifyDataSetChanged();
                    Toast.makeText(this, R.string.norm_reset_done, Toast.LENGTH_SHORT).show();
                    return true;
                case 7:
                    showMeditationEffectsDialog();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    /**
     * Einstellungs-Dialog für Meditations-Audioeffekte (Reverb + Delay).
     * Verwendet MaterialSwitch für zuverlässiges Toggle-Verhalten im dunklen Theme.
     */
    private void showMeditationEffectsDialog() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int)(16 * dp);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad);

        // Helper: Abschnitts-Überschrift
        java.util.function.Consumer<String> addHeader = text -> {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextColor(0xFF90CAF9);
            tv.setTextSize(13f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setPadding(0, (int)(10*dp), 0, 4);
            root.addView(tv);
        };

        // Helper: Switch-Zeile (Label links, Switch rechts)
        android.widget.LinearLayout[] switchRowHolder = new android.widget.LinearLayout[2];
        // Wir bauen die Rows inline

        // ===== REVERB =====
        addHeader.accept("🏛️ Reverb (Raumhall)");

        boolean reverbOn = prefsManager.isMeditationReverbEnabled();
        com.google.android.material.materialswitch.MaterialSwitch swReverb =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        swReverb.setText("Reverb aktiv");
        swReverb.setChecked(reverbOn);
        swReverb.setTextColor(0xFFFFFFFF);
        root.addView(swReverb);

        int decayCurrent = Math.max(500, prefsManager.getMeditationReverbDecay());
        TextView tvDecayLabel = new TextView(this);
        tvDecayLabel.setTextColor(0xFFB0BEC5);
        tvDecayLabel.setTextSize(12f);
        tvDecayLabel.setText("Nachhallzeit: " + decayCurrent + " ms");
        root.addView(tvDecayLabel);

        SeekBar sbDecay = new SeekBar(this);
        sbDecay.setMax(110); // 0 → 500 ms … 110 → 6050 ms (50 ms Schritte)
        sbDecay.setProgress(Math.max(0, (decayCurrent - 500) / 50));
        sbDecay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvDecayLabel.setText("Nachhallzeit: " + (500 + p * 50) + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sbDecay);

        // ===== DELAY =====
        addHeader.accept("🔁 Delay (Echo × 4)");

        boolean delayOn = prefsManager.isMeditationDelayEnabled();
        com.google.android.material.materialswitch.MaterialSwitch swDelay =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        swDelay.setText("Delay aktiv");
        swDelay.setChecked(delayOn);
        swDelay.setTextColor(0xFFFFFFFF);
        root.addView(swDelay);

        int delayCurrent = Math.max(100, prefsManager.getMeditationDelayMs());
        TextView tvDelayMsLabel = new TextView(this);
        tvDelayMsLabel.setTextColor(0xFFB0BEC5);
        tvDelayMsLabel.setTextSize(12f);
        tvDelayMsLabel.setText("Delay-Zeit: " + delayCurrent + " ms");
        root.addView(tvDelayMsLabel);

        SeekBar sbDelayMs = new SeekBar(this);
        sbDelayMs.setMax(38); // 0 → 100 ms … 38 → 1990 ms (50 ms Schritte)
        sbDelayMs.setProgress(Math.max(0, (delayCurrent - 100) / 50));
        sbDelayMs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvDelayMsLabel.setText("Delay-Zeit: " + (100 + p * 50) + " ms");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sbDelayMs);

        int levelCurrent = prefsManager.getMeditationDelayLevel();
        TextView tvDelayLvlLabel = new TextView(this);
        tvDelayLvlLabel.setTextColor(0xFFB0BEC5);
        tvDelayLvlLabel.setTextSize(12f);
        tvDelayLvlLabel.setText("Echo-Lautstärke: " + levelCurrent + " %");
        root.addView(tvDelayLvlLabel);

        SeekBar sbDelayLvl = new SeekBar(this);
        sbDelayLvl.setMax(100);
        sbDelayLvl.setProgress(levelCurrent);
        sbDelayLvl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                tvDelayLvlLabel.setText("Echo-Lautstärke: " + p + " %");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(sbDelayLvl);

        // ScrollView damit alles auf kleinen Bildschirmen erreichbar ist
        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("🎛�� Meditations-Effekte")
                .setView(scrollView)
                .setPositiveButton("Speichern", (d, w) -> {
                    prefsManager.saveMeditationReverbEnabled(swReverb.isChecked());
                    prefsManager.saveMeditationReverbDecay(500 + sbDecay.getProgress() * 50);
                    prefsManager.saveMeditationDelayEnabled(swDelay.isChecked());
                    prefsManager.saveMeditationDelayMs(100 + sbDelayMs.getProgress() * 50);
                    prefsManager.saveMeditationDelayLevel(sbDelayLvl.getProgress());
                    Toast.makeText(this,
                            "✅ Reverb " + (swReverb.isChecked() ? "an" : "aus")
                            + "  •  Delay " + (swDelay.isChecked() ? "an" : "aus"),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Zeigt ein Kontextmenü zum Setzen des Referenz-Tracks und Normalisierungsoptionen (nach Longpress).
     */
    private void showReferenceTrackMenu(TrackSelector.TrackInfo track) {
        boolean hasSectional = normalizationStore.hasSectionalGains(track.uri.toString());
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add(getString(R.string.norm_set_ref_confirm));
        items.add(getString(R.string.norm_single_track));
        items.add(getString(R.string.norm_sectional));
        if (hasSectional) {
            items.add(getString(R.string.norm_sectional_visualize));
            items.add(getString(R.string.norm_sectional_clear));
        }

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
                    } else if (chosen.equals(getString(R.string.norm_sectional))) {
                        startSectionalNormalization(track);
                    } else if (chosen.equals(getString(R.string.norm_sectional_visualize))) {
                        showSectionalGainChart(track);
                    } else if (chosen.equals(getString(R.string.norm_sectional_clear))) {
                        normalizationStore.clearSectionalGains(track.uri.toString());
                        trackAdapter.notifyDataSetChanged();
                        Toast.makeText(this, getString(R.string.norm_reset_done), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Zeigt ein Diagramm der sektionsweisen Gain-Anpassungen für einen Track.
     * Grüne Balken = leise Stellen werden angehoben, orange = laute werden gedämpft.
     * Eine pinke Linie zeigt die aktuelle Wiedergabeposition (falls dieser Track spielt).
     */
    private void showSectionalGainChart(TrackSelector.TrackInfo track) {
        java.util.List<float[]> sections = normalizationStore.getSectionalGains(track.uri.toString());
        if (sections == null || sections.isEmpty()) {
            Toast.makeText(this, R.string.norm_no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }

        SectionalGainChartView chartView = new SectionalGainChartView(this);
        chartView.setSections(sections);

        // Aktuelle Wiedergabeposition eintragen, falls dieser Track gerade spielt
        if (isBound && playbackService != null && playbackService.getCurrentTrack() != null
                && playbackService.getCurrentTrack().uri.toString().equals(track.uri.toString())) {
            chartView.setCurrentPosition(playbackService.getCurrentPosition());
        }

        // Höhe: 260dp
        int heightPx = (int)(260 * getResources().getDisplayMetrics().density);
        chartView.setMinimumHeight(heightPx);

        // Zusammenfassung unter dem Diagramm
        int boostCount = 0, cutCount = 0, neutralCount = 0;
        float maxBoostDb = 0f, maxCutDb = 0f;
        for (float[] s : sections) {
            float db = (float)(20.0 * Math.log10(Math.max(1e-6, s[1])));
            if (db > 0.5f) { boostCount++; maxBoostDb = Math.max(maxBoostDb, db); }
            else if (db < -0.5f) { cutCount++; maxCutDb = Math.max(maxCutDb, -db); }
            else neutralCount++;
        }
        String summary = String.format(Locale.getDefault(),
                "%d Sektionen  •  ▲ %d angehoben (max +%.1fdB)  •  ▼ %d gedämpft (max −%.1fdB)  •  %d neutral",
                sections.size(), boostCount, maxBoostDb, cutCount, maxCutDb, neutralCount);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.addView(chartView);

        TextView summaryView = new TextView(this);
        summaryView.setText(summary);
        summaryView.setTextColor(0xFF90A4AE);
        summaryView.setTextSize(11f);
        int pad = (int)(12 * getResources().getDisplayMetrics().density);
        summaryView.setPadding(pad, pad/2, pad, pad);
        layout.addView(summaryView);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.norm_sectional_chart_title) + "\n" + track.title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, null)
                // "Neu analysieren" Shortcut
                .setNeutralButton(getString(R.string.norm_sectional), (d, w) -> startSectionalNormalization(track))
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
     * Sektionsweise Normalisierung für einen einzelnen Track.
     * Fragt zuerst nach der gewünschten Sektionslänge (1–30 Sek.), dann Analyse.
     */
    private void startSectionalNormalization(TrackSelector.TrackInfo track) {
        String refUri = normalizationStore.getReferenceTrackUri();
        if (refUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.norm_menu_normalize))
                    .setMessage(getString(R.string.norm_no_ref))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }

        // Sektionslänge-Dialog mit SeekBar (1–30 Sek.)
        int currentSec = prefsManager.getSectionLengthSec();

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        TextView labelView = new TextView(this);
        labelView.setTextColor(0xFFFFFFFF);
        labelView.setTextSize(14f);
        labelView.setText(getString(R.string.norm_section_length_label, currentSec));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(29);                        // 0–29 → Sekunden 1–30
        seekBar.setProgress(currentSec - 1);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                labelView.setText(getString(R.string.norm_section_length_label, p + 1));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        layout.addView(labelView);
        layout.addView(seekBar);

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.norm_sectional))
                .setMessage(track.title)
                .setView(layout)
                .setPositiveButton(getString(R.string.norm_start_analysis), (d, w) -> {
                    int chosenSec = seekBar.getProgress() + 1;
                    prefsManager.saveSectionLengthSec(chosenSec);
                    runSectionalNormalization(track, chosenSec * 1000);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Führt die eigentliche sektionsweise Analyse durch (nach Bestätigung der Sektionslänge). */
    private void runSectionalNormalization(TrackSelector.TrackInfo track, int sectionLengthMs) {
        String refUri = normalizationStore.getReferenceTrackUri();
        if (refUri == null) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.norm_menu_normalize))
                    .setMessage(getString(R.string.norm_no_ref))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.norm_sectional_title)
                .setMessage(track.title)
                .setView(pb)
                .setCancelable(false)
                .create();
        dlg.show();

        new Thread(() -> {
            // Referenz-RMS (Gesamtlevel als Ziel)
            float refDb = Float.NaN;
            TrackSelector selector = isBound && playbackService != null
                    ? playbackService.getTrackSelector() : new TrackSelector(this);
            for (TrackSelector.TrackInfo t : selector.getAllTracks()) {
                if (t.uri.toString().equals(refUri)) {
                    refDb = AudioAnalyzer.analyzeRmsDb(this, t.uri);
                    break;
                }
            }
            if (Float.isNaN(refDb)) {
                final float finalRefDb = refDb;
                runOnUiThread(() -> { dlg.dismiss();
                    Toast.makeText(this, R.string.norm_ref_analysis_failed, Toast.LENGTH_LONG).show(); });
                return;
            }

            final float targetDb = refDb;
            // Sektionsweise Analyse des Tracks
            java.util.List<float[]> sections = AudioAnalyzer.analyzeSectionsDb(
                    this, track.uri, sectionLengthMs,
                    p -> runOnUiThread(() -> pb.setProgress(p)));

            if (sections.isEmpty()) {
                runOnUiThread(() -> { dlg.dismiss();
                    Toast.makeText(this, R.string.norm_track_analysis_failed, Toast.LENGTH_LONG).show(); });
                return;
            }

            // Für jede Sektion Gain berechnen: [startMs, gainMul]
            java.util.List<float[]> sectionalGains = new java.util.ArrayList<>();
            for (float[] s : sections) {
                float sectionDb = s[1];
                float gain = Float.isNaN(sectionDb) || sectionDb <= -99f
                        ? 1.0f
                        : NormalizationStore.computeGainMultiplier(targetDb, sectionDb);
                // Auf ±18 dB begrenzen
                float maxMul = (float) Math.pow(10, NormalizationStore.MAX_GAIN_DB / 20f);
                gain = Math.max(1f / maxMul, Math.min(maxMul, gain));
                sectionalGains.add(new float[]{s[0], gain});
            }

            normalizationStore.saveSectionalGains(track.uri.toString(), sectionalGains);
            final int count = sectionalGains.size();
            runOnUiThread(() -> { dlg.dismiss(); trackAdapter.notifyDataSetChanged();
                Toast.makeText(this, getString(R.string.norm_sectional_done, count), Toast.LENGTH_LONG).show(); });
        }, "SectionalNormThread").start();
    }

    /**
     * Startet die Normalisierungs-Analyse für alle Tracks im Hintergrund.
     * Zeigt einen Fortschritts-Dialog und speichert die Gains danach.
     */
    private void startNormalization() {
        // Track-Liste holen (bereits geladen im Adapter)
        List<TrackSelector.TrackInfo> allTracks;
        try {
            // Tracks via TrackSelector neu laden (sicher, eigener Thread folgt)
            TrackSelector selector = isBound && playbackService != null
                    ? playbackService.getTrackSelector()
                    : new TrackSelector(this);
            allTracks = selector.getAllTracks();
        } catch (Exception e) {
            Toast.makeText(this, R.string.norm_no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }
        if (allTracks.isEmpty()) {
            Toast.makeText(this, R.string.norm_no_tracks, Toast.LENGTH_SHORT).show();
            return;
        }

        String refUri = normalizationStore.getReferenceTrackUri();
        if (refUri == null) {
            // Kein Referenz-Track → erklärenden Dialog zeigen
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.norm_menu_normalize))
                    .setMessage(getString(R.string.norm_no_ref))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        // Fortschritts-Dialog aufbauen
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(allTracks.size());
        progressBar.setProgress(0);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        progressBar.setPadding(padding, padding, padding, padding);

        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.norm_progress_title)
                .setMessage(getString(R.string.norm_progress_msg, 0, allTracks.size()))
                .setView(progressBar)
                .setCancelable(false)
                .create();
        progressDialog.show();

        // Analyse im Hintergrund
        new Thread(() -> {
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
            final int total = allTracks.size();

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
                    // Referenz selbst bekommt Gain 1.0
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
            if (active) {
                // Meditation aktiv: voll sichtbar + türkis eingefärbt
                btnMeditation.setAlpha(1.0f);
                btnMeditation.setColorFilter(
                        ContextCompat.getColor(this, R.color.teal_200),
                        android.graphics.PorterDuff.Mode.SRC_IN);
            } else {
                // Meditation inaktiv: gedimmt + keine Färbung
                btnMeditation.setAlpha(0.6f);
                btnMeditation.clearColorFilter();
            }
            btnMeditation.setContentDescription(
                    getString(active ? R.string.meditation_stop : R.string.meditation_start));
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

