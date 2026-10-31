package com.example.sleepplayer;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialoge zur Verwaltung mehrerer Basisordner (z.B. "Sport", "Einschlafen", "Podcasts").
 *
 * Die Normalisierungswerte (dB) hängen an der Track-URI, nicht am Ordner –
 * Hinzufügen, Entfernen oder Wechseln von Ordnern lässt sie daher unverändert.
 */
public final class FolderDialogs {

    /** Callbacks an die aufrufende Activity. */
    public interface Host {
        /** Den System-Ordnerauswahldialog öffnen (OpenDocumentTree). */
        void requestAddFolder();
        /** Aktiver Ordner oder Ordnerliste hat sich geändert. */
        void onFoldersChanged();
    }

    private FolderDialogs() {}

    /**
     * Schnellauswahl: welcher Ordner soll für die Trackauswahl verwendet werden?
     */
    public static void showQuickSelect(Activity activity, PrefsManager prefs, Host host) {
        List<PrefsManager.FolderEntry> folders = prefs.getFolders();
        String active = prefs.getFolderUri();

        List<String> labels = new ArrayList<>();
        labels.add(activity.getString(R.string.folder_all));
        int checked = 0;
        for (int i = 0; i < folders.size(); i++) {
            labels.add(folders.get(i).name);
            if (folders.get(i).uri.equals(active)) checked = i + 1;
        }

        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_quick_title)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    prefs.setActiveFolder(which == 0 ? null : folders.get(which - 1).uri);
                    d.dismiss();
                    host.onFoldersChanged();
                })
                .setNeutralButton(R.string.folder_manage, (d, w) -> showManage(activity, prefs, host))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Verwaltung: Ordner hinzufügen, auswählen, umbenennen, entfernen.
     */
    public static void showManage(Activity activity, PrefsManager prefs, Host host) {
        List<PrefsManager.FolderEntry> folders = prefs.getFolders();
        String active = prefs.getFolderUri();

        List<String> labels = new ArrayList<>();
        for (PrefsManager.FolderEntry f : folders) {
            labels.add((f.uri.equals(active) ? "✓ " : "    ") + f.name);
        }
        labels.add((active == null ? "✓ " : "    ") + activity.getString(R.string.folder_all));
        labels.add(activity.getString(R.string.folder_add));

        final int idxAll = folders.size();
        final int idxAdd = folders.size() + 1;

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_folder_title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == idxAdd) {
                        host.requestAddFolder();
                    } else if (which == idxAll) {
                        prefs.setActiveFolder(null);
                        host.onFoldersChanged();
                        Toast.makeText(activity, R.string.folder_all, Toast.LENGTH_SHORT).show();
                    } else {
                        showFolderActions(activity, prefs, host, folders.get(which));
                    }
                })
                .setNegativeButton(R.string.folder_close, null)
                .show();
    }

    private static void showFolderActions(Activity activity, PrefsManager prefs, Host host,
                                          PrefsManager.FolderEntry folder) {
        String[] actions = {
                activity.getString(R.string.folder_action_select),
                activity.getString(R.string.folder_action_rename),
                activity.getString(R.string.folder_action_remove)
        };
        new AlertDialog.Builder(activity)
                .setTitle(folder.name)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            prefs.setActiveFolder(folder.uri);
                            host.onFoldersChanged();
                            Toast.makeText(activity,
                                    activity.getString(R.string.folder_selected, folder.name),
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            showRename(activity, prefs, host, folder);
                            break;
                        case 2:
                            new AlertDialog.Builder(activity)
                                    .setTitle(R.string.folder_action_remove)
                                    .setMessage(activity.getString(R.string.folder_remove_confirm, folder.name))
                                    .setPositiveButton(R.string.folder_action_remove, (d2, w2) -> {
                                        prefs.removeFolder(folder.uri);
                                        try {
                                            activity.getContentResolver().releasePersistableUriPermission(
                                                    Uri.parse(folder.uri),
                                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        } catch (SecurityException ignored) { }
                                        host.onFoldersChanged();
                                        showManage(activity, prefs, host);
                                    })
                                    .setNegativeButton(android.R.string.cancel,
                                            (d2, w2) -> showManage(activity, prefs, host))
                                    .show();
                            break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> showManage(activity, prefs, host))
                .show();
    }

    private static void showRename(Activity activity, PrefsManager prefs, Host host,
                                   PrefsManager.FolderEntry folder) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        input.setText(folder.name);
        input.setSelectAllOnFocus(true);
        FrameLayout container = new FrameLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.folder_action_rename)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        prefs.renameFolder(folder.uri, name);
                        host.onFoldersChanged();
                    }
                    showManage(activity, prefs, host);
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> showManage(activity, prefs, host))
                .show();
    }

    /**
     * Nach Auswahl im System-Ordnerdialog: Ordner speichern, aktivieren und
     * direkt einen Namen vorschlagen (z.B. "Sport").
     */
    public static void onFolderPicked(Activity activity, PrefsManager prefs, Host host, Uri treeUri) {
        // Dauerhafte Leseberechtigung -> Ordner kann direkt durchsucht werden
        // (auch Dateien, die nicht im MediaStore stehen, z.B. wegen .nomedia).
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    treeUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            android.util.Log.w("FolderDialogs", "Keine dauerhafte Berechtigung fuer " + treeUri, e);
        }
        String uri = treeUri.toString();
        prefs.addFolder(uri, PrefsManager.defaultFolderName(treeUri));
        prefs.setActiveFolder(uri);
        host.onFoldersChanged();
        for (PrefsManager.FolderEntry f : prefs.getFolders()) {
            if (f.uri.equals(uri)) {
                showRename(activity, prefs, host, f);
                return;
            }
        }
    }
}
