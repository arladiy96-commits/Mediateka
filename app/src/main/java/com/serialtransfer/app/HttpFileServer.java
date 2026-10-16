package com.serialtransfer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lightweight local file server for downloads and streaming to an external player. */
final class HttpFileServer {
    static final int PORT = 8765;

    private final ContentResolver resolver;
    private final Context appContext;
    private final Map<String, MediaLibrary.Film> byId = new LinkedHashMap<>();
    private final ExecutorService clients = Executors.newFixedThreadPool(3);
    private final String code;
    // Streaming tickets change on every server start and do not grant list/download access.
    private final String streamTicket;
    private volatile boolean running;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();

    private static final class Peer {
        final String id;
        volatile String name;
        volatile long lastSeen;
        final ArrayDeque<JSONObject> commands = new ArrayDeque<>();
        Peer(String id, String name) { this.id = id; this.name = name; lastSeen = System.currentTimeMillis(); }
    }

    private ServerSocket serverSocket;

    HttpFileServer(Context context, List<MediaLibrary.Film> files, String code) {
        appContext = context.getApplicationContext();
        resolver = appContext.getContentResolver();
        this.code = code;
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder(48);
        for (byte b : random) token.append(String.format(Locale.US, "%02x", b & 255));
        streamTicket = token.toString();
        for (MediaLibrary.Film file : files) byId.put(file.id, file);
    }

    void start() throws Exception {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(PORT), 8);
        serverSocket = socket;
        running = true;
        Thread listener = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket accepted = serverSocket.accept();
                        clients.execute(new Runnable() {
                            @Override public void run() { handle(accepted); }
                        });
                    } catch (Exception error) {
                        if (!running) return;
                    }
                }
            }
        }, "MediaServerAccept");
        listener.setDaemon(true);
        listener.start();
    }

    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) { }
        clients.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            if (!s.getInetAddress().isSiteLocalAddress() && !s.getInetAddress().isLoopbackAddress()) return;
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String first = reader.readLine();
            if (first == null || first.length() > 8192) return;
            String[] words = first.split(" ");
            if (words.length < 2) return;
            boolean head = "HEAD".equals(words[0]);
            if (!"GET".equals(words[0]) && !head) {
                respond(out, 405, "Only GET and HEAD supported", false);
                return;
            }
            String rawPath = words[1];
            String providedCode = "";
            String range = null;
            for (int i = 0; i < 60; i++) {
                String line = reader.readLine();
                if (line == null || line.isEmpty()) break;
                if (line.length() > 8192) return;
                String low = line.toLowerCase(Locale.ROOT);
                if (low.startsWith("x-media-key:")) providedCode = line.substring(line.indexOf(':') + 1).trim();
                else if (low.startsWith("range:")) range = line.substring(line.indexOf(':') + 1).trim();
            }
            int q = rawPath.indexOf('?');
            String path = q < 0 ? rawPath : rawPath.substring(0, q);
            String query = q < 0 ? "" : rawPath.substring(q + 1);

            if ("/stream".equals(path)) {
                // Third-party video players cannot provide custom HTTP pairing headers.
                // Permit ONLY streaming with the random ticket issued by authenticated /list.
                String ticket = queryParam(query, "ticket");
                if (!ticketMatches(ticket)) {
                    respond(out, 403, "Streaming ticket invalid", head);
                    return;
                }
                MediaLibrary.Film film = byId.get(queryParam(query, "id"));
                if (film == null) { respond(out, 404, "File missing", head); return; }
                sendMedia(out, film, range, head, mimeFor(film.name));
                return;
            }

            if (!code.equals(providedCode)) {
                respond(out, 403, "Wrong pairing code", head);
                return;
            }
            if ("/remote/poll".equals(path)) {
                if (head) { respond(out, 405, "GET only", head); return; }
                String id = queryParam(query, "device");
                String name = queryParam(query, "name");
                if (!id.matches("[A-Za-z0-9_-]{8,64}")) {
                    respond(out, 400, "Invalid device", head); return;
                }
                if (name.length() > 40) name = name.substring(0, 40);
                Peer peer = peers.get(id);
                if (peer == null) {
                    if (peers.size() >= 20) { respond(out, 429, "Too many devices", head); return; }
                    Peer created = new Peer(id, name);
                    Peer previous = peers.putIfAbsent(id, created);
                    peer = previous == null ? created : previous;
                }
                peer.name = name.isEmpty() ? "Android" : name;
                peer.lastSeen = System.currentTimeMillis();
                JSONObject command;
                synchronized (peer.commands) { command = peer.commands.pollFirst(); }
                if (command == null) command = new JSONObject().put("action", "none");
                respondJson(out, command.toString());
            } else if ("/remote/devices".equals(path)) {
                if (head) { respond(out, 405, "GET only", head); return; }
                JSONArray devices = new JSONArray();
                long now = System.currentTimeMillis();
                for (Peer peer : peers.values()) {
                    if (now - peer.lastSeen > 30000) continue;
                    devices.put(new JSONObject().put("id", peer.id).put("name", peer.name));
                }
                respondJson(out, new JSONObject().put("devices", devices).toString());
            } else if ("/remote/send".equals(path)) {
                if (head) { respond(out, 405, "GET only", head); return; }
                String device = queryParam(query, "device");
                String action = queryParam(query, "action");
                String filmId = queryParam(query, "id");
                Peer peer = peers.get(device);
                if (peer == null || System.currentTimeMillis() - peer.lastSeen > 30000) {
                    respond(out, 404, "Device offline", head); return;
                }
                if (!("play".equals(action) || "pause".equals(action) || "resume".equals(action)
                        || "seek_forward".equals(action) || "seek_back".equals(action)
                        || "next".equals(action) || "previous".equals(action) || "stop".equals(action)
                        || "volume_up".equals(action) || "volume_down".equals(action))) {
                    respond(out, 400, "Invalid action", head); return;
                }
                if ("play".equals(action) && !byId.containsKey(filmId)) {
                    respond(out, 404, "File missing", head); return;
                }
                JSONObject command = new JSONObject().put("action", action);
                if ("play".equals(action)) {
                    command.put("id", filmId).put("ticket", streamTicket);
                }
                synchronized (peer.commands) {
                    if (peer.commands.size() >= 15) peer.commands.pollFirst();
                    peer.commands.addLast(command);
                }
                respondJson(out, new JSONObject().put("ok", true).toString());
            } else if ("/list".equals(path)) {
                if (head) { respond(out, 405, "GET only", true); return; }
                JSONObject data = new JSONObject();
                data.put("protocol", 2);
                data.put("stream_ticket", streamTicket);
                JSONArray entries = new JSONArray();
                for (MediaLibrary.Film item : byId.values()) entries.put(item.asJson());
                data.put("files", entries);
                respondJson(out, data.toString());
            } else if ("/file".equals(path)) {
                MediaLibrary.Film film = byId.get(queryParam(query, "id"));
                if (film == null) { respond(out, 404, "File missing", head); return; }
                sendMedia(out, film, range, head, "application/octet-stream");
            } else {
                respond(out, 404, "Not found", head);
            }
        } catch (Exception ignored) { /* Disconnected client, canceled seek or invalid request. */ }
    }

    private boolean ticketMatches(String submitted) {
        if (submitted == null || submitted.length() != streamTicket.length()) return false;
        return MessageDigest.isEqual(streamTicket.getBytes(StandardCharsets.US_ASCII),
                submitted.getBytes(StandardCharsets.US_ASCII));
    }

    private static String queryParam(String query, String target) throws Exception {
        for (String segment : query.split("&")) {
            int eq = segment.indexOf('=');
            if (eq < 0) continue;
            if (target.equals(segment.substring(0, eq)))
                return URLDecoder.decode(segment.substring(eq + 1), "UTF-8");
        }
        return "";
    }

    private static String mimeFor(String filename) {
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mp4") || name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".mov")) return "video/quicktime";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".3gp")) return "video/3gpp";
        return "video/*";
    }

    /** HTTP byte ranges let VLC seek without saving a copy on the receiver. */
    private void sendMedia(OutputStream out, MediaLibrary.Film film,
                           String range, boolean head, String type) throws Exception {
        long size = film.size;
        long start = 0;
        long end = size > 0 ? size - 1 : -1;
        boolean partial = range != null && !range.isEmpty();
        if (partial) {
            if (size <= 0 || !range.startsWith("bytes=") || range.indexOf(',') >= 0) {
                rangeNotSatisfiable(out, size); return;
            }
            String request = range.substring("bytes=".length());
            int dash = request.indexOf('-');
            if (dash < 0 || dash != request.lastIndexOf('-')) {
                rangeNotSatisfiable(out, size); return;
            }
            String left = request.substring(0, dash);
            String right = request.substring(dash + 1);
            try {
                if (left.isEmpty()) {
                    long suffix = Long.parseLong(right);
                    if (suffix <= 0) throw new NumberFormatException();
                    start = Math.max(0, size - suffix);
                } else {
                    start = Long.parseLong(left);
                    if (!right.isEmpty()) end = Math.min(end, Long.parseLong(right));
                }
            } catch (NumberFormatException e) {
                rangeNotSatisfiable(out, size); return;
            }
            if (start < 0 || start >= size || end < start) {
                rangeNotSatisfiable(out, size); return;
            }
        }
        StringBuilder h = new StringBuilder();
        h.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Accept-Ranges: ").append(size > 0 ? "bytes" : "none").append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        if (size >= 0) h.append("Content-Length: ").append(partial ? end - start + 1 : size).append("\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end)
                .append('/').append(size).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (head) { out.flush(); return; }

        PowerManager.WakeLock cpuLock = null;
        WifiManager.WifiLock wifiLock = null;
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mediateka:Stream");
                cpuLock.acquire(2 * 60 * 60 * 1000L);
            }
            WifiManager wifi = (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Mediateka:StreamWiFi");
                wifiLock.acquire();
            }
            try (InputStream source = resolver.openInputStream(film.uri)) {
                if (source == null) throw new IllegalStateException("Video source unavailable");
                long skipped = 0;
                while (skipped < start) {
                    long n = source.skip(start - skipped);
                    if (n <= 0) {
                        if (source.read() < 0) return;
                        n = 1;
                    }
                    skipped += n;
                }
                byte[] buffer = new byte[32 * 1024];
                long remaining = size >= 0 ? end - start + 1 : Long.MAX_VALUE;
                while (remaining > 0) {
                    int n = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (n < 0) break;
                    if (n > 0) {
                        out.write(buffer, 0, n);
                        remaining -= n;
                    }
                }
                out.flush();
            }
        } finally {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        }
    }

    private static void rangeNotSatisfiable(OutputStream out, long size) throws Exception {
        String response = "HTTP/1.1 416 Range Not Satisfiable\r\n"
                + (size >= 0 ? "Content-Range: bytes */" + size + "\r\n" : "")
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void respondJson(OutputStream out, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    private static void respond(OutputStream out, int status, String message, boolean head) throws Exception {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " Error\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        if (!head) out.write(data);
        out.flush();
    }
}
