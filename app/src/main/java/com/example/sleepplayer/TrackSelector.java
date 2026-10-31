package com.example.sleepplayer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Wählt zufällig Audio-Tracks aus dem MediaStore aus.
 * Vermeidet die letzten 5 gespielten Titel.
 * Kann optional auf einen bestimmten Ordner gefiltert werden.
 */
public class TrackSelector {

    private static final String TAG = "TrackSelector";
    private static final long MIN_DURATION_MS = 30000;
    /** Cache fuer Metadaten direkt gescannter Dateien (Dauer/Titel/Interpret). */
    private static final String META_PREFS = "sleep_player_saf_meta";

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

        List<TrackInfo> tracks = new ArrayList<>();
        Set<String> indexedPaths = new HashSet<>();

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
                int dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

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

                    tracks.add(new TrackInfo(id, title, artist, album, albumId, duration, uri));
                    String data = cursor.getString(dataCol);
                    if (data != null) indexedPaths.add(data);
                }
            }
        }

        // Ordner direkt durchsuchen: findet auch Dateien, die Android nicht
        // in den MediaStore aufnimmt (z.B. Podcast-Ordner mit .nomedia-Datei).
        int before = tracks.size();
        for (String treeUri : getScannableTrees()) {
            try {
                scanTree(Uri.parse(treeUri), indexedPaths, tracks);
            } catch (Exception e) {
                Log.w(TAG, "Ordner-Scan fehlgeschlagen: " + treeUri, e);
            }
        }
        if (tracks.size() > before) {
            Collections.sort(tracks, (a, b) -> a.title.compareToIgnoreCase(b.title));
            Log.d(TAG, (tracks.size() - before) + " Titel per Ordner-Scan ergaenzt");
        }

        cachedTracks = tracks;
        cachedFolderPath = folderPath;
        return tracks;
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
     * Welche Ordner werden direkt durchsucht? Nur solche mit dauerhafter
     * Leseberechtigung: der aktive Ordner, oder bei "Alle Audio-Dateien"
     * alle gespeicherten Ordner.
     */
    private List<String> getScannableTrees() {
        Set<String> granted = new HashSet<>();
        for (UriPermission perm : context.getContentResolver().getPersistedUriPermissions()) {
            if (perm.isReadPermission()) granted.add(perm.getUri().toString());
        }
        List<String> result = new ArrayList<>();
        String active = prefsManager.getFolderUri();
        if (active != null) {
            if (granted.contains(active)) result.add(active);
        } else {
            for (PrefsManager.FolderEntry f : prefsManager.getFolders()) {
                if (granted.contains(f.uri)) result.add(f.uri);
            }
        }
        return result;
    }

    /** Durchsucht einen SAF-Ordnerbaum rekursiv (inkl. aller Unterordner). */
    private void scanTree(Uri treeUri, Set<String> knownPaths, List<TrackInfo> out) {
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences meta = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor metaEdit = null;

        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };

        List<String> pending = new ArrayList<>();
        pending.add(DocumentsContract.getTreeDocumentId(treeUri));

        while (!pending.isEmpty()) {
            String parentId = pending.remove(pending.size() - 1);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
            try (Cursor c = resolver.query(childrenUri, projection, null, null, null)) {
                if (c == null) continue;
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long modified = c.isNull(3) ? 0 : c.getLong(3);

                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        pending.add(docId);
                        continue;
                    }
                    if (!isAudio(name, mime)) continue;

                    // Schon ueber den MediaStore gefunden? -> nicht doppelt aufnehmen
                    // (MediaStore-URI behaelt ihre bisherigen dB-Werte).
                    String path = docIdToPath(docId);
                    if (path != null && !knownPaths.add(path)) continue;

                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                    String key = docUri.toString();

                    String title = null, artist = null, album = null;
                    long duration = 0;
                    boolean cached = false;
                    String json = meta.getString(key, null);
                    if (json != null) {
                        try {
                            JSONObject o = new JSONObject(json);
                            if (o.optLong("m") == modified) {
                                duration = o.optLong("d");
                                title = o.has("t") ? o.getString("t") : null;
                                artist = o.has("a") ? o.getString("a") : null;
                                album = o.has("al") ? o.getString("al") : null;
                                cached = true;
                            }
                        } catch (Exception ignored) { }
                    }
                    if (!cached) {
                        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                        try {
                            mmr.setDataSource(context, docUri);
                            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                            duration = d != null ? Long.parseLong(d) : 0;
                            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
                        } catch (Exception e) {
                            Log.w(TAG, "Metadaten nicht lesbar: " + name);
                        } finally {
                            try { mmr.release(); } catch (Exception ignored) { }
                        }
                        try {
                            JSONObject o = new JSONObject();
                            o.put("m", modified);
                            o.put("d", duration);
                            if (title != null) o.put("t", title);
                            if (artist != null) o.put("a", artist);
                            if (album != null) o.put("al", album);
                            if (metaEdit == null) metaEdit = meta.edit();
                            metaEdit.putString(key, o.toString());
                        } catch (Exception ignored) { }
                    }

                    // Gleiche Mindestlaenge wie beim MediaStore (0 = unbekannt -> aufnehmen)
                    if (duration > 0 && duration < MIN_DURATION_MS) continue;

                    if (title == null || title.isEmpty()) {
                        int dot = name.lastIndexOf('.');
                        title = dot > 0 ? name.substring(0, dot) : name;
                    }
                    if (artist == null || artist.isEmpty() || "<unknown>".equals(artist)) {
                        artist = album != null && !album.isEmpty() ? album : "Unbekannt";
                    }

                    long id = -(Math.abs((long) key.hashCode()) + 1); // negativ = kein MediaStore-Eintrag
                    out.add(new TrackInfo(id, title, artist, album, 0, duration, docUri));
                }
            } catch (Exception e) {
                Log.w(TAG, "Ordner nicht lesbar: " + parentId, e);
            }
        }
        if (metaEdit != null) metaEdit.apply();
    }

    private static boolean isAudio(String name, String mime) {
        if (mime != null && mime.startsWith("audio/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".flac")
                || n.endsWith(".wav") || n.endsWith(".m4b");
    }

    /** "primary:Music/x.mp3" -> "/storage/emulated/0/Music/x.mp3" */
    private static String docIdToPath(String docId) {
        String[] parts = docId.split(":", 2);
        if (parts.length < 2) return null;
        String base = "primary".equalsIgnoreCase(parts[0])
                ? "/storage/emulated/0/" : "/storage/" + parts[0] + "/";
        return base + parts[1];
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




