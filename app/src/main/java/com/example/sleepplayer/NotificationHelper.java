package com.example.sleepplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.media.session.MediaSessionCompat;

import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

/**
 * Erstellt und verwaltet die Foreground-Service-Notification.
 */
public class NotificationHelper {

    public static final String CHANNEL_ID = "sleep_player_playback";
    public static final int NOTIFICATION_ID = 1;

    public static final String ACTION_PLAY_PAUSE = "com.example.sleepplayer.PLAY_PAUSE";
    public static final String ACTION_STOP = "com.example.sleepplayer.STOP";
    /** Wird gefeuert, wenn der Nutzer die Sleep-Mode-Notification wegwischt → Service beenden. */
    public static final String ACTION_DISMISS = "com.example.sleepplayer.DISMISS";

    /**
     * Erstellt den NotificationChannel (einmalig, ab API 26).
     */
    public static void createChannel(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // Kein Sound für die Notification
        );
        channel.setDescription(context.getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    /**
     * Baut die Foreground-Notification.
     */
    public static Notification buildNotification(Context context,
                                                  MediaSessionCompat session,
                                                  String trackTitle,
                                                  boolean isPlaying) {

        // Intent um die App zu öffnen
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Play/Pause Action
        Intent playPauseIntent = new Intent(context, PlaybackService.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePending = PendingIntent.getService(
                context, 1, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Stop Action
        Intent stopIntent = new Intent(context, PlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                context, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int playPauseIcon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
        String playPauseLabel = isPlaying ? "Pause" : "Play";

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("SleepPlayer")
                .setContentText(trackTitle != null ? trackTitle : "Bereit")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(isPlaying)
                .addAction(playPauseIcon, playPauseLabel, playPausePending)
                .addAction(R.drawable.ic_notification, "Stopp", stopPending)
                .setStyle(new MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
    }

    /**
     * Baut die "Schlafmodus"-Notification, die erscheint wenn der Sleep-Timer abgelaufen ist
     * und die Wiedergabe pausiert wurde.
     *
     * WICHTIG: Diese Notification hält den Foreground-Service am Leben, damit
     * die MediaSession aktiv bleibt und Kopfhörer-Tasten weiterhin funktionieren –
     * auch nach langer Pause (Wiedereinschlafen ohne Bildschirm).
     *
     * Der Nutzer kann die Notification wegwischen → Service wird beendet (ACTION_DISMISS).
     */
    public static Notification buildSleepNotification(Context context,
                                                       MediaSessionCompat session,
                                                       String trackTitle) {
        // App öffnen
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Play-Action (Kopfhörertaste-Alternative in der Notification)
        Intent playIntent = new Intent(context, PlaybackService.class);
        playIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPending = PendingIntent.getService(
                context, 1, playIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Stop-Action
        Intent stopIntent = new Intent(context, PlaybackService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                context, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Dismiss-Intent: wird gefeuert wenn Nutzer die Notification wegwischt
        Intent dismissIntent = new Intent(context, PlaybackService.class);
        dismissIntent.setAction(ACTION_DISMISS);
        PendingIntent dismissPending = PendingIntent.getService(
                context, 3, dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("SleepPlayer – Schlafmodus")
                .setContentText("⏸ " + (trackTitle != null ? trackTitle : "Pausiert")
                        + " · Kopfhörertaste zum Fortsetzen")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // nicht ongoing → Nutzer kann wegwischen (= Dismiss-Intent wird ausgelöst)
                .setOngoing(false)
                .setDeleteIntent(dismissPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(R.drawable.ic_play, "Fortsetzen", playPending)
                .addAction(R.drawable.ic_notification, "Beenden", stopPending)
                .setStyle(new MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0))
                .build();
    }
}

