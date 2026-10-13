package com.serialtransfer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight durable transfer queue. Stores only small JSON metadata in SharedPreferences;
 * the video bytes always go directly through Storage Access Framework to the SD card.
 * All methods updating the queue are synchronized and use commit() for crash resilience.
 */
final class DownloadQueue {
    static final String KEY_QUEUE = "queue_v3";
    static final String KEY_PAUSED = "queue_paused_v3";
    private static final String URI_PREFIX = "episode_uri_v3_";
    private static final String FOLDER_PREFIX = "episode_folder_v3_";
    private static final String STATE_PREFIX = "episode_state_v3_";
    static final String DONE = "done";
    static final String PARTIAL = "partial";
    static final String MISSING = "missing";

    private DownloadQueue() { }

    private static SharedPreferences p(Context c) { return MediaLibrary.prefs(c); }

    static synchronized JSONArray all(Context c) {
        try { return new JSONArray(p(c).getString(KEY_QUEUE, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static synchronized int waiting(Context c) { return all(c).length(); }

    static synchronized boolean paused(Context c) { return p(c).getBoolean(KEY_PAUSED, false); }

    static synchronized void setPaused(Context c, boolean value) {
        p(c).edit().putBoolean(KEY_PAUSED, value).commit();
    }

    static synchronized String status(Context c, String id) {
        return p(c).getString(STATE_PREFIX + id, MISSING);
    }

    /** Single linear scan avoids per-row repeated queue parsing on 1 GB tablets. */
    static synchronized Set<String> queuedIds(Context c) {
        Set<String> ids = new HashSet<>();
        JSONArray queue = all(c);
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item != null) ids.add(item.optString("id"));
        }
        return ids;
    }

    /** Adds only absent, unfinished files while preserving the order the user selected. */
    static synchronized int enqueue(Context c, JSONArray requested) {
        JSONArray queue = all(c);
        Set<String> known = new HashSet<>();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item != null) known.add(item.optString("id"));
        }
        int added = 0;
        for (int i = 0; i < requested.length(); i++) {
            JSONObject item = requested.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (!id.matches("[0-9a-f]{24}") || known.contains(id) || DONE.equals(status(c, id))) continue;
            JSONObject snapshot = new JSONObject();
            try {
                snapshot.put("id", id);
                snapshot.put("name", item.optString("name", "Серия.mp4"));
                snapshot.put("size", item.optLong("size", -1));
                queue.put(snapshot);
                known.add(id);
                added++;
            } catch (Exception ignored) { }
        }
        if (added > 0 && !p(c).edit().putString(KEY_QUEUE, queue.toString()).commit()) return 0;
        return added;
    }

    /** Avoid requesting episodes removed from the phone; they stay in the queue for later. */
    static synchronized JSONArray availablePending(Context c, JSONArray remote) {
        Map<String, JSONObject> online = new HashMap<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item != null) online.put(item.optString("id"), item);
        }
        JSONArray result = new JSONArray();
        JSONArray queue = all(c);
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (!DONE.equals(status(c, id)) && online.containsKey(id)) result.put(online.get(id));
        }
        return result;
    }

    static synchronized void completed(Context c, JSONObject item) {
        String id = item.optString("id");
        JSONArray old = all(c);
        JSONArray rest = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject queued = old.optJSONObject(i);
            if (queued != null && !id.equals(queued.optString("id"))) rest.put(queued);
        }
        p(c).edit().putString(STATE_PREFIX + id, DONE)
                .putString(KEY_QUEUE, rest.toString()).commit();
        TransferService.queueRevision++;
    }

    /** Save destination URI before any video bytes are streamed. */
    static synchronized void rememberFile(Context c, String id, Uri file, Uri tree) throws Exception {
        if (!p(c).edit().putString(URI_PREFIX + id, file.toString())
                .putString(FOLDER_PREFIX + id, tree.toString())
                .putString(STATE_PREFIX + id, PARTIAL).commit())
            throw new Exception("Не удалось сохранить путь серии");
    }

    /** Resolves the v3 URI; also imports downloads from the older IP-based implementation. */
    static synchronized Uri knownFile(Context c, String id, Uri destination,
                                      String previousHost, String currentHost) {
        SharedPreferences preferences = p(c);
        String savedFolder = preferences.getString(FOLDER_PREFIX + id, "");
        String saved = preferences.getString(URI_PREFIX + id, "");
        if (!saved.isEmpty() && destination.toString().equals(savedFolder)) return Uri.parse(saved);
        for (String host : new String[]{previousHost, currentHost}) {
            if (host == null || host.isEmpty()) continue;
            String old = preferences.getString("download_" + host.replace('.', '_') + "_" + id, "");
            if (old.isEmpty()) continue;
            Uri oldUri = Uri.parse(old);
            // Only reuse an old document when it actually belongs to this destination tree.
            try {
                String doc = DocumentsContract.getDocumentId(oldUri);
                String tree = DocumentsContract.getTreeDocumentId(destination);
                boolean belongs = doc.equals(tree) || doc.startsWith(tree + "/")
                        || (tree.endsWith(":") && doc.startsWith(tree));
                if (!belongs) continue;
                preferences.edit().putString(URI_PREFIX + id, old)
                        .putString(FOLDER_PREFIX + id, destination.toString()).commit();
                return oldUri;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static long fileSize(ContentResolver resolver, Uri uri) {
        boolean readable = false;
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            if (fd != null) {
                readable = true;
                if (fd.getStatSize() >= 0) return fd.getStatSize();
            }
        } catch (Exception ignored) { }
        try (Cursor cursor = resolver.query(uri,
                new String[] {DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (Exception ignored) { }
        // -2 means file exists but this SD provider does not report size.
        return readable ? -2 : -1;
    }

    /** Runs off the UI thread; detects downloaded episodes even after a process restart. */
    static synchronized void reconcile(Context c, JSONArray remote,
                                       String previousHost, String currentHost) {
        String folder = p(c).getString(MediaLibrary.DEST_TREE, "");
        if (folder.isEmpty()) return;
        Uri tree = Uri.parse(folder);
        SharedPreferences.Editor editor = p(c).edit();
        Set<String> finished = new HashSet<>();
        for (int i = 0; i < remote.length(); i++) {
            JSONObject item = remote.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (!id.matches("[0-9a-f]{24}")) continue;
            long expected = item.optLong("size", -1);
            Uri file = knownFile(c, id, tree, previousHost, currentHost);
            long actual = file == null ? -1 : fileSize(c.getContentResolver(), file);
            String earlier = status(c, id);
            String state = expected >= 0 && actual == expected ? DONE
                    : (actual == -2 || expected < 0 && actual >= 0) && DONE.equals(earlier) ? DONE
                    : actual > 0 ? PARTIAL : MISSING;
            editor.putString(STATE_PREFIX + id, state);
            if (DONE.equals(state)) finished.add(id);
        }
        JSONArray old = all(c);
        JSONArray rest = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && !finished.contains(item.optString("id"))) rest.put(item);
        }
        editor.putString(KEY_QUEUE, rest.toString()).commit();
        TransferService.queueRevision++;
    }

    /** New SD folder means completed flags and queued data must not apply to it. */
    static synchronized void destinationChanged(Context c) {
        // Retain old file URIs (the files are never deleted), but their folder
        // values prevent mistakenly counting them as downloads in the new folder.
        SharedPreferences.Editor edit = p(c).edit();
        for (String key : p(c).getAll().keySet()) {
            if (key.startsWith(STATE_PREFIX)) edit.remove(key);
        }
        edit.putString(KEY_QUEUE, "[]").putBoolean(KEY_PAUSED, false).commit();
        TransferService.queueRevision++;
    }
}
