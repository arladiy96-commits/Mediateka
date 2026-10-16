package com.serialtransfer.app;

import android.content.Context;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;

/** The receiver initiates all connections, so Android hotspot clients need no inbound server. */
final class RemoteControlClient {
    private static int watchCycle;
    private RemoteControlClient() { }

    private static String encode(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private static JSONObject get(String host, String code, String route) throws Exception {
        String address = DownloadEngine.validatedHost(host);
        if (code == null || !code.matches("[0-9]{8}")) throw new IllegalArgumentException("Неверный код");
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://" + address + ":" + HttpFileServer.PORT + route).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(4500);
        connection.setRequestProperty("X-Media-Key", code);
        connection.setRequestProperty("Accept", "application/json");
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (out.size() > 128 * 1024) throw new IllegalStateException("Ответ сервера слишком большой");
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }

    static JSONArray devices(String code) throws Exception {
        return get("127.0.0.1", code, "/remote/devices").getJSONArray("devices");
    }

    static void seek(String code, String device, int positionMs) throws Exception {
        String route = "/remote/send?device=" + encode(device) + "&action=seek_to&ms="
                + Math.max(0, positionMs);
        if (!get("127.0.0.1", code, route).optBoolean("ok"))
            throw new IllegalStateException("Команда не доставлена");
    }

    static void send(String code, String device, String action, String filmId) throws Exception {
        String route = "/remote/send?device=" + encode(device) + "&action=" + encode(action)
                + "&id=" + encode(filmId == null ? "" : filmId);
        if (!get("127.0.0.1", code, route).optBoolean("ok"))
            throw new IllegalStateException("Команда не доставлена");
    }

    static JSONObject poll(Context context, String host, String code) throws Exception {
        String id = MediaLibrary.prefs(context).getString("receiver_device_id", "");
        if (!id.matches("[A-Za-z0-9_-]{8,64}")) {
            id = UUID.randomUUID().toString().replace("-", "");
            MediaLibrary.prefs(context).edit().putString("receiver_device_id", id).commit();
        }
        String model = Build.MANUFACTURER + " " + Build.MODEL;
        String route = "/remote/poll?device=" + encode(id) + "&name=" + encode(model);
        route += "&player_id=" + encode(RemotePlayerActivity.playbackId)
                + "&player_position=" + Math.max(0, RemotePlayerActivity.playbackPosition)
                + "&player_duration=" + Math.max(0, RemotePlayerActivity.playbackDuration)
                + "&player_playing=" + (RemotePlayerActivity.playbackPlaying ? "1" : "0");
        JSONObject watch = WatchHistory.last(context);
        if ((++watchCycle % 3) == 0) {
            try {
                java.util.ArrayList<String> keys = new java.util.ArrayList<>();
                for (String key : MediaLibrary.prefs(context).getAll().keySet()) {
                    if (key.startsWith("watch_bookmark_v1_")) keys.add(key);
                }
                java.util.Collections.sort(keys);
                if (!keys.isEmpty()) {
                    String entry = MediaLibrary.prefs(context)
                            .getString(keys.get((watchCycle / 3) % keys.size()), "");
                    watch = new JSONObject(entry);
                }
            } catch (Exception ignored) { }
        }
        if (watch != null) {
            route += "&watch_id=" + encode(watch.optString("id", ""))
                    + "&watch_position=" + Math.max(0, watch.optInt("position"))
                    + "&watch_duration=" + Math.max(0, watch.optInt("duration"))
                    + "&watch_finished=" + (watch.optBoolean("finished") ? "1" : "0")
                    + "&watch_updated=" + Math.max(0, watch.optLong("updated"));
        }
        return get(host, code, route);
    }
}
