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

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Sequential range-resumable streaming. Writes only to the user's SAF folder. */
final class DownloadEngine {
    interface Progress { void onProgress(String label, long done, long total); }

    private final Context context;
    private final ContentResolver resolver;
    private final AtomicBoolean cancel;
    private final Progress progress;
    private final Uri tree;
    private final String host;
    private final String code;
    private volatile HttpURLConnection currentConnection;

    DownloadEngine(Context context, String host, String code, Uri tree,
                   AtomicBoolean cancel, Progress callback) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.tree = tree;
        this.host = host;
        this.code = code;
        this.cancel = cancel;
        this.progress = callback;
    }

    static String list(String host, String code) throws Exception {
        HttpURLConnection connection = open(host, "/list", code, -1);
        try {
            int response = connection.getResponseCode();
            if (response == 403) throw new Exception("Неверный код подключения");
            if (response != 200) throw new Exception("Телефон вернул ошибку " + response);
            try (InputStream source = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    if (bytes.size() + n > 512 * 1024) throw new Exception("Слишком большой список файлов");
                    bytes.write(buffer, 0, n);
                }
                return bytes.toString("UTF-8");
            }
        } finally { connection.disconnect(); }
    }

    static String validatedHost(String raw) {
        String host = raw.trim().replace("http://", "").replace("https://", "");
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        if (host.endsWith(":" + HttpFileServer.PORT)) host = host.substring(0, host.lastIndexOf(':'));
        if (!host.matches("[0-9.]+")) throw new IllegalArgumentException("Укажи IP телефона, например 192.168.43.1");
        String[] parts = host.split("\\.");
        if (parts.length != 4) throw new IllegalArgumentException("Неверный IP-адрес");
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("Неверный IP-адрес");
            }
        }
        return host;
    }

    private static HttpURLConnection open(String host, String path, String key, long from) throws Exception {
        URL url = new URL("http://" + validatedHost(host) + ':' + HttpFileServer.PORT + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("X-Media-Key", key);
        connection.setRequestProperty("Connection", "close");
        if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
        return connection;
    }

    void download(JSONArray items) throws Exception {
        for (int i = 0; i < items.length(); ++i) {
            if (cancel.get()) return;
            JSONObject item = items.getJSONObject(i);
            downloadOne(item, i + 1, items.length());
        }
    }

    private void downloadOne(JSONObject item, int ordinal, int totalFiles) throws Exception {
        String id = item.getString("id");
        if (!id.matches("[a-f0-9]{24}")) throw new Exception("Неверный идентификатор серии");
        String name = safeName(item.optString("name", "Серия.mp4"));
        long total = item.optLong("size", -1);
        String label = ordinal + "/" + totalFiles + " · " + name;
        Uri outputUri = getOrCreate(id, name);
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(outputUri, "rw")) {
            if (descriptor == null) throw new Exception("Нет доступа к файлу на microSD");
            try (FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
                FileChannel channel = output.getChannel();
                long position = channel.size();
                if ((total >= 0 && position > total) || (total < 0 && position > 0)) {
                    // When the source size is unknown, restart rather than request
                    // an unverifiable Content-Range from a document provider.
                    channel.truncate(0);
                    position = 0;
                }
                if (total >= 0 && position == total) {
                    DownloadQueue.completed(context, item);
                    progress.onProgress("Уже скачано: " + label, total, total);
                    return;
                }
                HttpURLConnection connection = open(host, "/file?id=" + id, code, position);
                currentConnection = connection;
                try {
                    int status = connection.getResponseCode();
                    if (status == 403) throw new Exception("Неверный код подключения");
                    if (status != 200 && status != 206) throw new Exception("Ошибка загрузки HTTP " + status);
                    if (position > 0 && status == 200) {
                        channel.truncate(0);
                        position = 0;
                    }
                    if (status == 206) {
                        String range = connection.getHeaderField("Content-Range");
                        if (range == null || !range.startsWith("bytes " + position + "-"))
                            throw new Exception("Сервер не подтвердил точку продолжения");
                    }
                    channel.position(position);
                    byte[] buffer = new byte[64 * 1024];
                    long downloaded = position;
                    long lastProgress = 0;
                    progress.onProgress(label, downloaded, total);
                    try (InputStream input = connection.getInputStream()) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            if (cancel.get()) return;
                            if (count == 0) continue;
                            output.write(buffer, 0, count);
                            downloaded += count;
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (now - lastProgress >= 300) {
                                progress.onProgress(label, downloaded, total);
                                lastProgress = now;
                            }
                        }
                    }
                    output.flush();
                    if (total >= 0 && downloaded != total)
                        throw new Exception("Передача оборвалась: " + MediaLibrary.readableSize(downloaded)
                                + " из " + MediaLibrary.readableSize(total) + ". Повтори скачивание для продолжения.");
                    DownloadQueue.completed(context, item);
                    progress.onProgress("Готово: " + label, downloaded, total);
                } finally {
                    connection.disconnect();
                    currentConnection = null;
                }
            }
        }
    }

    void interrupt() {
        cancel.set(true);
        HttpURLConnection active = currentConnection;
        if (active != null) active.disconnect();
    }

    private Uri getOrCreate(String id, String name) throws Exception {
        Uri previous = DownloadQueue.knownFile(context, id, tree, host, host);
        if (previous != null) {
            try (ParcelFileDescriptor fd = resolver.openFileDescriptor(previous, "rw")) {
                if (fd != null) return previous;
            } catch (Exception ignored) { }
        }
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Uri created = DocumentsContract.createDocument(resolver, parent, MediaLibrary.mime(name), name);
        if (created == null) throw new Exception("Не удалось создать серию на microSD");
        DownloadQueue.rememberFile(context, id, created, tree);
        return created;
    }

    private static String safeName(String given) {
        String clean = given.replace('/', '_').replace('\\', '_').replace('\n', '_').replace('\r', '_').trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "Серия.mp4";
        if (clean.length() > 180) clean = clean.substring(clean.length() - 180);
        return clean;
    }
}
