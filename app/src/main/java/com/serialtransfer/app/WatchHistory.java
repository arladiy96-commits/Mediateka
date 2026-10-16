package com.serialtransfer.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Tiny durable playback bookmarks. No thumbnails, video bytes or database are stored. */
final class WatchHistory {
    private static final String LAST_ID = "watch_last_id_v1";
    private static final String KEY = "watch_bookmark_v1_";

    private WatchHistory() { }

    static synchronized void save(Context context, String id, String name,
                                  int positionMs, int durationMs, boolean finished) {
        if (id == null || !id.matches("[0-9a-f]{24}")) return;
        SharedPreferences prefs = MediaLibrary.prefs(context);
        JSONObject previous = item(context, id);
        int position = Math.max(0, positionMs);
        int duration = Math.max(0, durationMs);
        long now = System.currentTimeMillis();
        boolean changed = previous == null
                || Math.abs((long)previous.optInt("position") - position) >= 8000L
                || previous.optBoolean("finished") != finished
                || (now - previous.optLong("updated", 0)) >= 20000;
        if (!changed && id.equals(prefs.getString(LAST_ID, ""))) return;
        JSONObject record = new JSONObject();
        try {
            record.put("id", id);
            record.put("name", name == null ? "Серия" : name);
            record.put("position", position);
            record.put("duration", duration);
            record.put("finished", finished);
            record.put("updated", now);
        } catch (Exception ignored) { return; }
        prefs.edit().putString(LAST_ID, id).putString(KEY + id, record.toString()).commit();
    }

    static synchronized JSONObject item(Context context, String id) {
        if (id == null || !id.matches("[0-9a-f]{24}")) return null;
        try {
            return new JSONObject(MediaLibrary.prefs(context).getString(KEY + id, ""));
        } catch (Exception ignored) { return null; }
    }

    static synchronized JSONObject last(Context context) {
        String id = MediaLibrary.prefs(context).getString(LAST_ID, "");
        return item(context, id);
    }

    static synchronized int resumeAt(Context context, String id) {
        JSONObject item = item(context, id);
        if (item == null || item.optBoolean("finished")) return 0;
        int position = item.optInt("position", 0);
        int duration = item.optInt("duration", 0);
        // Treat the last few seconds as watched, not as a useful resume point.
        if (duration > 0 && duration - position < 15000) return 0;
        return position < 5000 ? 0 : position;
    }
}
