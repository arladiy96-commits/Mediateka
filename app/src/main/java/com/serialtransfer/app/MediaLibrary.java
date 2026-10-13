package com.serialtransfer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SAF-only media index: never copies source movies into app storage. */
final class MediaLibrary {
    static final String PREFS = "mediateka_settings";
    static final String SOURCE_TREE = "source_tree";
    static final String DEST_TREE = "destination_tree";
    static final String SOURCE_FILES = "source_files";
    static final int LIMIT = 600;

    static final class Film {
        final String id;
        final String name;
        final long size;
        final Uri uri;

        Film(String id, String name, long size, Uri uri) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.uri = uri;
        }

        JSONObject asJson() throws Exception {
            JSONObject item = new JSONObject();
            item.put("id", id);
            item.put("name", name);
            item.put("size", size);
            return item;
        }
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void addFiles(Context context, List<Uri> uris) {
        SharedPreferences preferences = prefs(context);
        Set<String> filenames = new HashSet<>(preferences.getStringSet(SOURCE_FILES, Collections.<String>emptySet()));
        for (Uri uri : uris) filenames.add(uri.toString());
        preferences.edit().putStringSet(SOURCE_FILES, filenames).apply();
    }

    static void clearSources(Context context) {
        prefs(context).edit().remove(SOURCE_TREE).remove(SOURCE_FILES).apply();
    }

    static List<Film> scan(Context context) {
        List<Film> films = new ArrayList<>();
        Set<String> known = new HashSet<>();
        SharedPreferences preferences = prefs(context);
        String root = preferences.getString(SOURCE_TREE, "");
        if (!root.isEmpty()) {
            try { scanTree(context.getContentResolver(), Uri.parse(root), films, known); }
            catch (Exception ignored) { /* A revoked storage grant must not crash the app. */ }
        }
        for (String saved : preferences.getStringSet(SOURCE_FILES, Collections.<String>emptySet())) {
            if (films.size() >= LIMIT) break;
            try {
                Uri uri = Uri.parse(saved);
                String name = null;
                long size = -1;
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        name = cursor.getString(0);
                        if (!cursor.isNull(1)) size = cursor.getLong(1);
                    }
                }
                if (name != null && isVideo(name)) add(films, known, uri, name, size);
            } catch (Exception ignored) { }
        }
        Collections.sort(films, new Comparator<Film>() {
            @Override public int compare(Film a, Film b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return films;
    }

    private static void scanTree(ContentResolver resolver, Uri root, List<Film> out, Set<String> known) {
        String id = DocumentsContract.getTreeDocumentId(root);
        scanFolder(resolver, root, id, out, known, 0);
    }

    private static void scanFolder(ContentResolver resolver, Uri root, String parent,
                                   List<Film> out, Set<String> known, int depth) {
        if (depth > 5 || out.size() >= LIMIT) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parent);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext() && out.size() < LIMIT) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String type = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                if (childId == null || name == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(type)) {
                    scanFolder(resolver, root, childId, out, known, depth + 1);
                } else if (isVideo(name)) {
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(root, childId);
                    add(out, known, uri, name, size);
                }
            }
        } catch (Exception ignored) { }
    }

    private static boolean isVideo(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT);
        return s.endsWith(".mp4") || s.endsWith(".mkv") || s.endsWith(".avi")
                || s.endsWith(".mov") || s.endsWith(".webm") || s.endsWith(".m4v")
                || s.endsWith(".ts") || s.endsWith(".3gp") || s.endsWith(".mpg")
                || s.endsWith(".mpeg");
    }

    private static void add(List<Film> out, Set<String> known, Uri uri, String name, long size) {
        if (!known.add(uri.toString())) return;
        out.add(new Film(idOf(uri.toString()), name, size, uri));
    }

    private static String idOf(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; ++i) sb.append(String.format(java.util.Locale.US, "%02x", digest[i] & 255));
            return sb.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    static String mime(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }

    static String readableSize(long size) {
        if (size < 0) return "размер неизвестен";
        if (size < 1024 * 1024L) return String.format(java.util.Locale.US, "%.0f КБ", size / 1024.0);
        if (size < 1024 * 1024 * 1024L) return String.format(java.util.Locale.US, "%.1f МБ", size / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f ГБ", size / 1073741824.0);
    }

    private MediaLibrary() { }
}
