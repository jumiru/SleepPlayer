package com.example.sleepplayer;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;

/**
 * Persistentes Debug-Logging für SleepPlayer.
 *
 * Jeder log()-Aufruf wird sofort in eine tagesbezogene Datei geschrieben
 * (sleepplayer_YYYY-MM-DD.log). Es werden maximal MAX_LOG_FILES Tagesdateien
 * behalten; ältere werden beim Start und beim Tageswechsel automatisch gelöscht.
 */
public class LogManager {

    private static final String TAG = "LogManager";
    private static final int MAX_IN_MEMORY_LINES = 1000;
    private static final int MAX_LOG_FILES = 7;

    private final Queue<String> logBuffer = new LinkedList<>();
    private final Context context;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private static volatile LogManager instance;

    private File logsDir;
    private BufferedWriter fileWriter;
    private String currentLogDate = "";

    public static LogManager getInstance(Context context) {
        if (instance == null) {
            synchronized (LogManager.class) {
                if (instance == null) {
                    instance = new LogManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LogManager(Context context) {
        this.context = context;
        logsDir = new File(context.getFilesDir(), "logs");
        if (!logsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            logsDir.mkdirs();
        }
        cleanOldLogFiles();
        openCurrentLogFile();
    }

    public void log(String tag, String message) {
        log(tag, message, Log.DEBUG);
    }

    public void log(String tag, String message, int level) {
        String levelStr = getLevelString(level);
        String timestamp = timeFormat.format(new Date());
        String line = "[" + timestamp + "] " + levelStr + "/" + tag + ": " + message;

        synchronized (logBuffer) {
            logBuffer.offer(line);
            while (logBuffer.size() > MAX_IN_MEMORY_LINES) {
                logBuffer.poll();
            }
        }

        Log.println(level, tag, message);
        writeLineToFile(line);
    }

    public void logError(String tag, String message, Throwable throwable) {
        String full = throwable != null ? message + "\n" + Log.getStackTraceString(throwable) : message;
        log(tag, full, Log.ERROR);
    }

    /** Gibt den Inhalt der heutigen Log-Datei zurück (für die UI). */
    public String getAllLogs() {
        File today = buildLogFile(dateFormat.format(new Date()));
        if (today.exists()) {
            return readFile(today);
        }
        // Fallback: In-Memory-Puffer
        StringBuilder sb = new StringBuilder();
        synchronized (logBuffer) {
            for (String line : logBuffer) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** Liest eine bestimmte Log-Datei komplett als String. */
    public String getLogsFromFile(File file) {
        return readFile(file);
    }

    /** Alle vorhandenen Log-Dateien, neueste zuerst. */
    public List<File> getAvailableLogFiles() {
        File[] files = logsDir.listFiles(
                f -> f.getName().startsWith("sleepplayer_") && f.getName().endsWith(".log"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        return new ArrayList<>(Arrays.asList(files));
    }

    /** Löscht die heutige Log-Datei und leert den In-Memory-Puffer. */
    public void clearLogs() {
        synchronized (logBuffer) {
            logBuffer.clear();
        }
        closeFileWriter();
        File today = buildLogFile(dateFormat.format(new Date()));
        if (today.exists()) {
            //noinspection ResultOfMethodCallIgnored
            today.delete();
        }
        openCurrentLogFile();
        Log.i(TAG, "Logs gelöscht");
    }

    /** Gibt die heutige Log-Datei zurück (für Share/Export). */
    public File saveLogsToFile() {
        File today = buildLogFile(dateFormat.format(new Date()));
        return today.exists() ? today : null;
    }

    // ===== private helpers =====

    private File buildLogFile(String date) {
        return new File(logsDir, "sleepplayer_" + date + ".log");
    }

    private synchronized void openCurrentLogFile() {
        String today = dateFormat.format(new Date());
        currentLogDate = today;
        File logFile = buildLogFile(today);
        try {
            fileWriter = new BufferedWriter(new FileWriter(logFile, true));
            String header = "\n===== Session " + new Date() + " =====\n";
            fileWriter.write(header);
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Log-Datei konnte nicht geöffnet werden: " + logFile, e);
            fileWriter = null;
        }
    }

    private synchronized void writeLineToFile(String line) {
        // Tageswechsel erkennen
        String today = dateFormat.format(new Date());
        if (!today.equals(currentLogDate)) {
            closeFileWriter();
            cleanOldLogFiles();
            openCurrentLogFile();
        }
        if (fileWriter == null) return;
        try {
            fileWriter.write(line);
            fileWriter.newLine();
            fileWriter.flush();
        } catch (IOException e) {
            Log.e(TAG, "Fehler beim Schreiben in Log-Datei", e);
        }
    }

    private synchronized void closeFileWriter() {
        if (fileWriter != null) {
            try {
                fileWriter.close();
            } catch (IOException ignored) {}
            fileWriter = null;
        }
    }

    private void cleanOldLogFiles() {
        File[] files = logsDir.listFiles(
                f -> f.getName().startsWith("sleepplayer_") && f.getName().endsWith(".log"));
        if (files == null || files.length <= MAX_LOG_FILES) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        int toDelete = files.length - MAX_LOG_FILES;
        for (int i = 0; i < toDelete; i++) {
            boolean ok = files[i].delete();
            Log.d(TAG, "Altes Log gelöscht: " + files[i].getName() + " ok=" + ok);
        }
    }

    private String readFile(File file) {
        if (file == null || !file.exists()) return "";
        StringBuilder sb = new StringBuilder((int) file.length() + 64);
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.e(TAG, "Fehler beim Lesen von " + file, e);
        }
        return sb.toString();
    }

    private String getLevelString(int level) {
        switch (level) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG:   return "D";
            case Log.INFO:    return "I";
            case Log.WARN:    return "W";
            case Log.ERROR:   return "E";
            case Log.ASSERT:  return "A";
            default:          return "?";
        }
    }
}
