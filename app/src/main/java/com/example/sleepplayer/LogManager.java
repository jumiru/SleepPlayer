package com.example.sleepplayer;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

/**
 * Verwaltet Debug-Logs für SleepPlayer.
 * Speichert Logs sowohl im Memory (für schnelle Anzeige) als auch optional in Datei.
 */
public class LogManager {
    private static final String TAG = "LogManager";
    private static final int MAX_LOG_LINES = 500;
    private static final String LOG_FILE_NAME = "sleepplayer_debug.log";

    private final Queue<LogEntry> logBuffer = new LinkedList<>();
    private final Context context;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private static LogManager instance;

    public static synchronized LogManager getInstance(Context context) {
        if (instance == null) {
            instance = new LogManager(context.getApplicationContext());
        }
        return instance;
    }

    private LogManager(Context context) {
        this.context = context;
    }

    /**
     * Fügt einen Log-Eintrag hinzu
     */
    public void log(String tag, String message) {
        log(tag, message, Log.DEBUG);
    }

    /**
     * Fügt einen Log-Eintrag mit Level hinzu
     */
    public void log(String tag, String message, int level) {
        String levelString = getLevelString(level);
        String timestamp = dateFormat.format(new Date());
        String logMessage = String.format("[%s] %s/%s: %s", timestamp, levelString, tag, message);

        synchronized (logBuffer) {
            logBuffer.offer(new LogEntry(logMessage, level));
            // Puffer begrenzen
            while (logBuffer.size() > MAX_LOG_LINES) {
                logBuffer.poll();
            }
        }

        // Auch in Android Log schreiben
        Log.println(level, tag, message);
    }

    public void logError(String tag, String message, Throwable throwable) {
        String errorMessage = message;
        if (throwable != null) {
            errorMessage += "\n" + Log.getStackTraceString(throwable);
        }
        log(tag, errorMessage, Log.ERROR);
    }

    /**
     * Gibt alle gepufferten Logs als String zurück
     */
    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logBuffer) {
            for (LogEntry entry : logBuffer) {
                sb.append(entry.message).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Speichert Logs in eine Datei
     */
    public File saveLogsToFile() {
        try {
            File logsDir = new File(context.getFilesDir(), "logs");
            if (!logsDir.exists()) {
                logsDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(new Date());
            File logFile = new File(logsDir, "log_" + timestamp + ".txt");

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFile))) {
                writer.write("SleepPlayer Debug Log\n");
                writer.write("Generated: " + new Date() + "\n");
                writer.write("==========================================\n\n");
                writer.write(getAllLogs());
            }

            Log.i(TAG, "Logs saved to: " + logFile.getAbsolutePath());
            return logFile;
        } catch (IOException e) {
            Log.e(TAG, "Fehler beim Speichern der Logs", e);
            return null;
        }
    }

    /**
     * Löscht alle gepufferten Logs
     */
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        Log.i(TAG, "Logs cleared");
    }

    private String getLevelString(int level) {
        switch (level) {
            case Log.VERBOSE:
                return "V";
            case Log.DEBUG:
                return "D";
            case Log.INFO:
                return "I";
            case Log.WARN:
                return "W";
            case Log.ERROR:
                return "E";
            case Log.ASSERT:
                return "A";
            default:
                return "?";
        }
    }

    /**
     * Innere Klasse für Log-Einträge
     */
    private static class LogEntry {
        String message;
        int level;

        LogEntry(String message, int level) {
            this.message = message;
            this.level = level;
        }
    }
}

