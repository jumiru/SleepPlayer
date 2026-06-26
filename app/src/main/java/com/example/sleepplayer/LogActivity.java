package com.example.sleepplayer;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogActivity extends AppCompatActivity {

    private TextView tvLogContent;
    private Button btnCopy;
    private Button btnShare;
    private Button btnClear;
    private ImageButton btnBack;
    private ScrollView scrollView;
    private Spinner spinnerLogFiles;

    private LogManager logManager;
    private List<File> logFiles;
    private File selectedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        logManager = LogManager.getInstance(this);

        tvLogContent = findViewById(R.id.tvLogContent);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnClear = findViewById(R.id.btnClear);
        btnBack = findViewById(R.id.btnBack);
        scrollView = findViewById(R.id.scrollView);
        spinnerLogFiles = findViewById(R.id.spinnerLogFiles);

        btnBack.setOnClickListener(v -> finish());
        btnCopy.setOnClickListener(v -> copyLogsToClipboard());
        btnShare.setOnClickListener(v -> shareLogsFile());
        btnClear.setOnClickListener(v -> clearLogs());

        setupFileSpinner();
        logManager.log("LogActivity", "Log-Dialog geöffnet");
    }

    private void setupFileSpinner() {
        logFiles = logManager.getAvailableLogFiles();

        if (logFiles.isEmpty()) {
            tvLogContent.setText("Noch keine Logs vorhanden.");
            spinnerLogFiles.setVisibility(View.GONE);
            return;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String yesterday = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                .format(new Date(System.currentTimeMillis() - 86_400_000L));

        String[] labels = new String[logFiles.size()];
        for (int i = 0; i < logFiles.size(); i++) {
            String name = logFiles.get(i).getName(); // sleepplayer_YYYY-MM-DD.log
            String date = name.replace("sleepplayer_", "").replace(".log", "");
            if (date.equals(today)) {
                labels[i] = "Heute (" + date + ")";
            } else if (date.equals(yesterday)) {
                labels[i] = "Gestern (" + date + ")";
            } else {
                labels[i] = date;
            }
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLogFiles.setAdapter(adapter);

        spinnerLogFiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedFile = logFiles.get(position);
                loadFileContent(selectedFile);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Heute als Standard auswählen (ist bereits Index 0, da neueste zuerst)
        spinnerLogFiles.setSelection(0);
    }

    private void loadFileContent(File file) {
        if (file == null) return;
        String content = logManager.getLogsFromFile(file);
        if (content.isEmpty()) {
            tvLogContent.setText("Keine Einträge in dieser Datei.");
        } else {
            tvLogContent.setText(content);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void copyLogsToClipboard() {
        String logs = tvLogContent.getText().toString();
        if (logs.isEmpty()) {
            Toast.makeText(this, "Keine Logs zum Kopieren", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("SleepPlayer Logs", logs);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Logs kopiert", Toast.LENGTH_SHORT).show();
    }

    private void shareLogsFile() {
        File fileToShare = selectedFile != null ? selectedFile : logManager.saveLogsToFile();
        if (fileToShare == null || !fileToShare.exists()) {
            Toast.makeText(this, "Keine Log-Datei vorhanden", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri fileUri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider",
                    fileToShare);

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "SleepPlayer Log " + fileToShare.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(shareIntent, "Log teilen…"));
            logManager.log("LogActivity", "Share-Intent für: " + fileToShare.getName());
        } catch (Exception e) {
            Toast.makeText(this, "Fehler beim Teilen", Toast.LENGTH_SHORT).show();
            logManager.logError("LogActivity", "Fehler beim Teilen", e);
        }
    }

    private void clearLogs() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Heutige Logs löschen?")
                .setMessage("Nur die heutige Log-Datei wird gelöscht. Ältere Dateien bleiben erhalten.")
                .setPositiveButton("Löschen", (dialog, which) -> {
                    logManager.clearLogs();
                    setupFileSpinner();
                    Toast.makeText(this, "Heutige Logs gelöscht", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}
