package com.example.sleepplayer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Wählt zufällig Audio-Tracks aus dem MediaStore aus.
 * Vermeidet die letzten 5 gespielten Titel.
 * Kann optional auf einen bestimmten Ordner gefiltert werden.
 */
public class TrackSelector {

    private final Context context;
    private final PrefsManager prefsManager;
    private final Random random;
    private List<TrackInfo> cachedTracks;
    private String cachedFolderPath; // Um Cache bei Ordnerwechsel zu invalidieren

    public TrackSelector(Context context) {
        this.context = context.getApplicationContext();
        this.prefsManager = new PrefsManager(context);
        this.random = new Random();
    }

    /**
     * Datenklasse für Track-Informationen.
     */
    public static class TrackInfo {
        public final long id;
        public final String title;
        public final String artist;
        public final String album;
        public final long albumId;   // für Album-Cover-URI
        public final long duration;
        public final Uri uri;

        /** URI zum Album-Cover (aus dem MediaStore). Kann null ergeben wenn kein Cover vorhanden. */
        public Uri getAlbumArtUri() {
            if (albumId <= 0) return null;
            return ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId);
        }

        public TrackInfo(long id, String title, String artist, String album,
                         long albumId, long duration, Uri uri) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumId = albumId;
            this.duration = duration;
            this.uri = uri;
        }

        /**
         * Formatiert die Dauer als MM:SS oder HH:MM:SS.
         */
        public String getFormattedDuration() {
            long totalSeconds = duration / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            if (hours > 0) {
                return String.format("%d:%02d:%02d", hours, minutes, seconds);
            }
            return String.format("%d:%02d", minutes, seconds);
        }
    }

    /**
     * Lädt alle Audio-Tracks vom Gerät.
     * Wenn ein Ordner in den Einstellungen gesetzt ist, werden nur
     * Tracks aus diesem Ordner (und Unterordnern) geladen.
     */
    public List<TrackInfo> getAllTracks() {
        // Aktuellen Ordner-Filter prüfen
        String folderPath = getFolderPathFromPrefs();

        // Cache nur gültig wenn gleicher Ordner-Filter
        if (cachedTracks != null && objectsEqual(cachedFolderPath, folderPath)) {
            return cachedTracks;
        }

        cachedTracks = new ArrayList<>();
        cachedFolderPath = folderPath;

        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA // Dateipfad für Ordner-Filterung
        };

        // Basis-Filter: mindestens 30 Sekunden lang
        String selection;
        String[] selectionArgs;

        if (folderPath != null) {
            // Ordner-Filter aktiv: nur Dateien deren Pfad mit dem Ordner beginnt
            selection = MediaStore.Audio.Media.DURATION + " >= ? AND "
                    + MediaStore.Audio.Media.DATA + " LIKE ?";
            selectionArgs = new String[]{"30000", folderPath + "%"};
        } else {
            // Kein Ordner-Filter: alle Audio-Dateien
            selection = MediaStore.Audio.Media.DURATION + " >= ?";
            selectionArgs = new String[]{"30000"};
        }

        String sortOrder = MediaStore.Audio.Media.TITLE + " ASC";

        try (Cursor cursor = resolver.query(collection, projection, selection,
                selectionArgs, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String title = cursor.getString(titleCol);
                    String artist = cursor.getString(artistCol);
                    String album = cursor.getString(albumCol);
                    long albumId = cursor.getLong(albumIdCol);
                    long duration = cursor.getLong(durationCol);
                    Uri uri = Uri.withAppendedPath(collection, String.valueOf(id));

                    if (title == null || title.isEmpty()) {
                        title = "Unbekannt";
                    }
                    if (artist == null || "<unknown>".equals(artist)) {
                        artist = "Unbekannt";
                    }

                    cachedTracks.add(new TrackInfo(id, title, artist, album, albumId, duration, uri));
                }
            }
        }

        return cachedTracks;
    }

    /**
     * Cache leeren (z.B. nach Permission-Änderung oder Ordnerwechsel).
     */
    public void clearCache() {
        cachedTracks = null;
        cachedFolderPath = null;
    }

    /**
     * Extrahiert den Dateisystem-Pfad aus dem gespeicherten SAF-Tree-URI.
     *
     * Ein Tree-URI sieht z.B. so aus:
     * content://com.android.externalstorage.documents/tree/primary%3AMusic%2FSleep
     * Daraus wird: /storage/emulated/0/Music/Sleep/
     */
    private String getFolderPathFromPrefs() {
        String uriString = prefsManager.getFolderUri();
        if (uriString == null) return null;

        try {
            Uri treeUri = Uri.parse(uriString);
            String docId = getTreeDocumentId(treeUri);
            if (docId == null) return null;

            // docId Format: "primary:Music/Sleep" oder "XXXX-XXXX:Folder"
            String[] parts = docId.split(":", 2);
            if (parts.length < 2) return null;

            String volume = parts[0];
            String relativePath = parts[1];

            String basePath;
            if ("primary".equalsIgnoreCase(volume)) {
                basePath = "/storage/emulated/0/";
            } else {
                // SD-Karte oder anderer externer Speicher
                basePath = "/storage/" + volume + "/";
            }

            String fullPath = basePath + relativePath;
            if (!fullPath.endsWith("/")) {
                fullPath += "/";
            }
            return fullPath;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extrahiert die Document ID aus einem Tree-URI.
     */
    private String getTreeDocumentId(Uri treeUri) {
        String path = treeUri.getPath();
        if (path == null) return null;
        // Pfad: /tree/primary:Music/Sleep
        if (path.startsWith("/tree/")) {
            return path.substring(6); // nach "/tree/"
        }
        return null;
    }

    private static boolean objectsEqual(Object a, Object b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    /**
     * Wählt zufällig den nächsten Track aus.
     * Vermeidet die letzten 5 gespielten Titel.
     *
     * @return TrackInfo oder null wenn keine Tracks vorhanden
     */
    public TrackInfo getNextTrack() {
        List<TrackInfo> allTracks = getAllTracks();
        if (allTracks.isEmpty()) {
            return null;
        }

        List<String> recentUris = prefsManager.getRecentTracks();

        // Verfügbare Tracks (nicht in der Zuletzt-Gespielt-Liste)
        List<TrackInfo> available = new ArrayList<>();
        for (TrackInfo track : allTracks) {
            if (!recentUris.contains(track.uri.toString())) {
                available.add(track);
            }
        }

        // Falls alle Tracks kürzlich gespielt wurden, alle zulassen
        if (available.isEmpty()) {
            available = new ArrayList<>(allTracks);
        }

        // Zufällig auswählen
        TrackInfo selected = available.get(random.nextInt(available.size()));

        // In die Zuletzt-Gespielt-Liste aufnehmen
        prefsManager.addRecentTrack(selected.uri.toString());

        return selected;
    }
}




