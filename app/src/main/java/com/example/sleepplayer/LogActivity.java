package com.example.sleepplayer;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;

/**
 * Activity zur Anzeige und Verwaltung von Debug-Logs.
 */
public class LogActivity extends AppCompatActivity {

    private TextView tvLogContent;
    private Button btnCopy;
    private Button btnShare;
    private Button btnClear;
    private ImageButton btnBack;
    private ScrollView scrollView;

    private LogManager logManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logManager = LogManager.getInstance(this);

        initViews();
        setupListeners();
        loadLogs();
    }

    private void initViews() {
        tvLogContent = findViewById(R.id.tvLogContent);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnClear = findViewById(R.id.btnClear);
        btnBack = findViewById(R.id.btnBack);
        scrollView = findViewById(R.id.scrollView);
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnCopy.setOnClickListener(v -> copyLogsToClipboard());
        btnShare.setOnClickListener(v -> shareLogsFile());
        btnClear.setOnClickListener(v -> clearLogs());
    }

    private void loadLogs() {
        String logs = logManager.getAllLogs();
        if (logs.isEmpty()) {
            tvLogContent.setText("Noch keine Logs vorhanden...");
        } else {
            tvLogContent.setText(logs);
            // Zum Ende scrollen
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }

        logManager.log("LogActivity", "Log-Dialog geöffnet");
    }

    /**
     * Kopiert die Logs in die Zwischenablage
     */
    private void copyLogsToClipboard() {
        String logs = logManager.getAllLogs();
        if (logs.isEmpty()) {
            Toast.makeText(this, "Keine Logs zum Kopieren", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SleepPlayer Logs", logs);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, "Logs kopiert", Toast.LENGTH_SHORT).show();
        logManager.log("LogActivity", "Logs in Zwischenablage kopiert");
    }

    /**
     * Teilt die Logs als Datei
     */
    private void shareLogsFile() {
        File logFile = logManager.saveLogsToFile();
        if (logFile == null) {
            Toast.makeText(this, "Fehler beim Speichern der Logs", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri fileUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider",
                    logFile);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SleepPlayer Debug Log");
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Logs teilen…"));
            logManager.log("LogActivity", "Share-Intent gestartet für: " + logFile.getName());
        } catch (Exception e) {
            Toast.makeText(this, "Fehler beim Teilen", Toast.LENGTH_SHORT).show();
            logManager.logError("LogActivity", "Fehler beim Teilen der Logs", e);
        }
    }

    /**
     * Löscht alle Logs mit Bestätigung
     */
    private void clearLogs() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Logs löschen?")
                .setMessage("Alle Logs werden gelöscht.")
                .setPositiveButton("Löschen", (dialog, which) -> {
                    logManager.clearLogs();
                    tvLogContent.setText("Logs gelöscht.");
                    Toast.makeText(this, "Logs gelöscht", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}

