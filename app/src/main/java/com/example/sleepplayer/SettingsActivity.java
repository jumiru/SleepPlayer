package com.example.sleepplayer;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Haupt-Settings-Activity für SleepPlayer.
 * Verwaltet alle Einstellungen und Optionen:
 * - Ordner-Auswahl
 * - TTS Toggle
 * - Normalisierung
 * - Meditations-Effekte
 * - Kompressor
 * - Referenz-Track
 */
public class SettingsActivity extends AppCompatActivity {

    private ImageButton btnBack;
    private Button btnFolderSelect;
    private Button btnNormalization;
    private Button btnMeditationEffects;
    private Button btnCompressor;
    private Button btnBatteryOptimization;
    private Button btnLogs;

    private PrefsManager prefsManager;
    private NormalizationStore normalizationStore;
    private TrackAdapter trackAdapter;

    private PlaybackService playbackService;
    private boolean isBound = false;

    // Folder Picker Launcher
    private final ActivityResultLauncher<Uri> folderPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri != null) {
                    onFolderSelected(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefsManager = new PrefsManager(this);
        normalizationStore = new NormalizationStore(this);

        initViews();
        setupListeners();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        btnFolderSelect = findViewById(R.id.btnFolderSelect);
        btnNormalization = findViewById(R.id.btnNormalization);
        btnMeditationEffects = findViewById(R.id.btnMeditationEffects);
        btnCompressor = findViewById(R.id.btnCompressor);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnLogs = findViewById(R.id.btnLogs);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnFolderSelect.setOnClickListener(v -> showFolderDialog());
        btnNormalization.setOnClickListener(v -> showNormalizationDialog());
        btnMeditationEffects.setOnClickListener(v -> showMeditationEffectsDialog());
        btnCompressor.setOnClickListener(v -> showCompressorDialog());
        btnBatteryOptimization.setOnClickListener(v -> checkBatteryOptimization());
        btnLogs.setOnClickListener(v -> openLogActivity());
    }

    private void openLogActivity() {
        startActivity(new Intent(this, LogActivity.class));
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "Nicht erforderlich auf diesem Android", Toast.LENGTH_SHORT).show();
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, getString(R.string.battery_opt_ok), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.battery_opt_dialog_title))
                .setMessage(getString(R.string.battery_opt_dialog_msg))
                .setPositiveButton(getString(R.string.battery_opt_open_settings), (d, w) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Dialog zur Ordner-Auswahl
     */
    private void showFolderDialog() {
        String[] options = {
                getString(R.string.settings_folder_select),
                getString(R.string.settings_folder_clear),
                getString(R.string.tts_toggle)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_folder_title))
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            folderPickerLauncher.launch(null);
                            break;
                        case 1:
                            prefsManager.clearFolder();
                            Toast.makeText(this, R.string.folder_all, Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            boolean newTts = !prefsManager.isTtsEnabled();
                            prefsManager.saveTtsEnabled(newTts);
                            Toast.makeText(this,
                                    newTts ? R.string.tts_on : R.string.tts_off,
                                    Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Dialog zur Normalisierung
     */
    private void showNormalizationDialog() {
        String[] options = {
                getString(R.string.norm_menu_normalize),
                getString(R.string.norm_menu_reset)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.norm_menu_normalize))
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            startNormalization();
                            break;
                        case 1:
                            normalizationStore.clearGains();
                            Toast.makeText(this, R.string.norm_reset_done, Toast.LENGTH_SHORT).show();
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Startet die Normalisierungs-Analyse für alle Tracks
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

        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
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

        new Thread(() -> {
            TrackSelector selector = new TrackSelector(this);
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

            // Referenz-Track finden
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

            // Alle Tracks analysieren
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
                    android.util.Log.w("SettingsActivity", "Analyse fehlgeschlagen: " + track.title);
                    continue;
                }

                float gain = NormalizationStore.computeGainMultiplier(refDb, trackDb);
                normalizationStore.saveGain(trackUri, gain);
            }

            final int finalFailed = failed;
            runOnUiThread(() -> {
                progressDialog.dismiss();
                String msg = getString(R.string.norm_done, total - finalFailed, total);
                if (finalFailed > 0) {
                    msg += " (" + getString(R.string.norm_failed_count, finalFailed) + ")";
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            });

        }, "NormalizationThread").start();
    }

    /**
     * Dialog für Meditations-Effekte (Reverb + Delay)
     */
    private void showMeditationEffectsDialog() {
        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad);

        // Helper: Abschnitts-Überschrift
        java.util.function.Consumer<String> addHeader = text -> {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(text);
            tv.setTextColor(0xFF90CAF9);
            tv.setTextSize(13f);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setPadding(0, (int) (10 * dp), 0, 4);
            root.addView(tv);
        };

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
        android.widget.TextView tvDecayLabel = new android.widget.TextView(this);
        tvDecayLabel.setTextColor(0xFFB0BEC5);
        tvDecayLabel.setTextSize(12f);
        tvDecayLabel.setText("Nachhallzeit: " + decayCurrent + " ms");
        root.addView(tvDecayLabel);

        android.widget.SeekBar sbDecay = new android.widget.SeekBar(this);
        sbDecay.setMax(110);
        sbDecay.setProgress(Math.max(0, (decayCurrent - 500) / 50));
        sbDecay.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                tvDecayLabel.setText("Nachhallzeit: " + (500 + p * 50) + " ms");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
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
        android.widget.TextView tvDelayMsLabel = new android.widget.TextView(this);
        tvDelayMsLabel.setTextColor(0xFFB0BEC5);
        tvDelayMsLabel.setTextSize(12f);
        tvDelayMsLabel.setText("Delay-Zeit: " + delayCurrent + " ms");
        root.addView(tvDelayMsLabel);

        android.widget.SeekBar sbDelayMs = new android.widget.SeekBar(this);
        sbDelayMs.setMax(38);
        sbDelayMs.setProgress(Math.max(0, (delayCurrent - 100) / 50));
        sbDelayMs.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                tvDelayMsLabel.setText("Delay-Zeit: " + (100 + p * 50) + " ms");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        root.addView(sbDelayMs);

        int levelCurrent = prefsManager.getMeditationDelayLevel();
        android.widget.TextView tvDelayLvlLabel = new android.widget.TextView(this);
        tvDelayLvlLabel.setTextColor(0xFFB0BEC5);
        tvDelayLvlLabel.setTextSize(12f);
        tvDelayLvlLabel.setText("Echo-Lautstärke: " + levelCurrent + " %");
        root.addView(tvDelayLvlLabel);

        android.widget.SeekBar sbDelayLvl = new android.widget.SeekBar(this);
        sbDelayLvl.setMax(100);
        sbDelayLvl.setProgress(levelCurrent);
        sbDelayLvl.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                tvDelayLvlLabel.setText("Echo-Lautstärke: " + p + " %");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        root.addView(sbDelayLvl);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("🎛️ Meditations-Effekte")
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
     * Dialog für Dynamik-Kompressor (DynamicsProcessing, API 28+)
     */
    private void showCompressorDialog() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            new AlertDialog.Builder(this)
                    .setTitle("🔊 Kompressor")
                    .setMessage("Der Kompressor benötigt Android 9 (API 28). "
                            + "Dein Gerät hat API " + android.os.Build.VERSION.SDK_INT + ".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        float dp = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * dp);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad);

        // Ein/Aus
        com.google.android.material.materialswitch.MaterialSwitch swComp =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        swComp.setText("Kompressor aktiv");
        swComp.setChecked(prefsManager.isCompressorEnabled());
        swComp.setTextColor(0xFFFFFFFF);
        root.addView(swComp);

        // Info-Text
        android.widget.TextView tvInfo = new android.widget.TextView(this);
        tvInfo.setTextColor(0xFF90A4AE);
        tvInfo.setTextSize(11f);
        tvInfo.setPadding(0, (int) (4 * dp), 0, (int) (8 * dp));
        tvInfo.setText("Dämpft laute und hebt leise Stellen an.\n"
                + "Ergänzt die Track-Normalisierung.");
        root.addView(tvInfo);

        // Schwellenwert
        int threshCurrent = prefsManager.getCompressorThreshold();
        android.widget.TextView tvThreshLabel = new android.widget.TextView(this);
        tvThreshLabel.setTextColor(0xFFB0BEC5);
        tvThreshLabel.setTextSize(12f);
        tvThreshLabel.setText("Schwellenwert: " + threshCurrent + " dB");
        root.addView(tvThreshLabel);

        android.widget.SeekBar sbThresh = new android.widget.SeekBar(this);
        sbThresh.setMax(39);
        sbThresh.setProgress(-threshCurrent - 1);
        sbThresh.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                tvThreshLabel.setText("Schwellenwert: " + (-(p + 1)) + " dB");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        root.addView(sbThresh);

        // Ratio
        int ratioCurrent = prefsManager.getCompressorRatio();
        android.widget.TextView tvRatioLabel = new android.widget.TextView(this);
        tvRatioLabel.setTextColor(0xFFB0BEC5);
        tvRatioLabel.setTextSize(12f);
        tvRatioLabel.setText("Ratio: " + (ratioCurrent / 10f) + ":1");
        root.addView(tvRatioLabel);

        android.widget.SeekBar sbRatio = new android.widget.SeekBar(this);
        sbRatio.setMax(14);
        sbRatio.setProgress((ratioCurrent - 15) / 5);
        sbRatio.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                float r = (15 + p * 5) / 10f;
                tvRatioLabel.setText(String.format(java.util.Locale.getDefault(),
                        "Ratio: %.1f:1", r));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        root.addView(sbRatio);

        // Makeup-Gain
        int makeupCurrent = prefsManager.getCompressorMakeup();
        android.widget.TextView tvMakeupLabel = new android.widget.TextView(this);
        tvMakeupLabel.setTextColor(0xFFB0BEC5);
        tvMakeupLabel.setTextSize(12f);
        tvMakeupLabel.setText("Makeup-Gain: +" + makeupCurrent + " dB");
        root.addView(tvMakeupLabel);

        android.widget.SeekBar sbMakeup = new android.widget.SeekBar(this);
        sbMakeup.setMax(18);
        sbMakeup.setProgress(makeupCurrent);
        sbMakeup.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean u) {
                tvMakeupLabel.setText("Makeup-Gain: +" + p + " dB");
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {}

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        root.addView(sbMakeup);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("🔊 Dynamik-Kompressor")
                .setView(scrollView)
                .setPositiveButton("Speichern", (d, w) -> {
                    boolean on = swComp.isChecked();
                    int threshold = -(sbThresh.getProgress() + 1);
                    int ratio = 15 + sbRatio.getProgress() * 5;
                    int makeup = sbMakeup.getProgress();

                    prefsManager.saveCompressorEnabled(on);
                    prefsManager.saveCompressorThreshold(threshold);
                    prefsManager.saveCompressorRatio(ratio);
                    prefsManager.saveCompressorMakeup(makeup);

                    Toast.makeText(this,
                            "✅ Kompressor " + (on ? "aktiv" : "deaktiviert")
                            + (on ? " (" + threshold + "dB, " + (ratio / 10f) + ":1, +" + makeup + "dB)" : ""),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Wird aufgerufen wenn der Benutzer einen Ordner ausgewählt hat
     */
    private void onFolderSelected(Uri treeUri) {
        String displayName = extractFolderDisplayName(treeUri);
        prefsManager.saveFolderUri(treeUri.toString());
        prefsManager.saveFolderDisplayName(displayName);
        Toast.makeText(this, getString(R.string.settings_folder_title) + ": " + displayName,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Extrahiert einen lesbaren Ordnernamen aus einem SAF Tree-URI
     */
    private String extractFolderDisplayName(Uri treeUri) {
        String path = treeUri.getPath();
        if (path != null && path.startsWith("/tree/")) {
            String docId = path.substring(6); // nach "/tree/"
            int colonIndex = docId.indexOf(':');
            if (colonIndex >= 0 && colonIndex < docId.length() - 1) {
                return docId.substring(colonIndex + 1);
            }
        }
        return treeUri.getLastPathSegment();
    }
}




