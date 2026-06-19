package com.example.sleepplayer;

import android.app.Application;

/**
 * Installiert einen Crash-Handler, der den Absturz noch vor dem Prozess-Ende
 * ins persistente Log schreibt (LogManager.saveLogsToFile()). Ohne das wäre
 * der In-Memory-Logpuffer nach einem nächtlichen Absturz verloren und man
 * sähe morgens nur "Service tot", aber nicht warum.
 */
public class SleepPlayerApplication extends Application {

    private static final String TAG = "SleepPlayerApp";

    @Override
    public void onCreate() {
        super.onCreate();

        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                LogManager logManager = LogManager.getInstance(this);
                logManager.logError(TAG, "Unbehandelte Exception – Prozess stirbt", throwable);
                logManager.saveLogsToFile();
            } catch (Exception ignored) {
                // Im Crash-Handler darf nichts weiter schiefgehen
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });
    }
}
