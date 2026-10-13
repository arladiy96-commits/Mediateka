package com.serialtransfer.app;

import android.content.ContentResolver;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny LAN-only HTTP server using the device's content:// sources. */
final class HttpFileServer {
    static final int PORT = 8765;
    private final ContentResolver resolver;
    private final Map<String, MediaLibrary.Film> byId = new LinkedHashMap<>();
    private final ExecutorService clients = Executors.newFixedThreadPool(2);
    private final String code;
    private volatile boolean running;
    private ServerSocket serverSocket;

    HttpFileServer(Context context, List<MediaLibrary.Film> files, String code) {
        resolver = context.getContentResolver();
        this.code = code;
        for (MediaLibrary.Film file : files) byId.put(file.id, file);
    }

    void start() throws Exception {
        serverSocket = new ServerSocket(PORT, 8);
        running = true;
        Thread listener = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        clients.execute(new Runnable() {
                            @Override public void run() { handle(socket); }
                        });
                    } catch (Exception error) {
                        if (!running) break;
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
            // Serve LAN clients only, never requests from a public cellular IP.
            if (!s.getInetAddress().isSiteLocalAddress() && !s.getInetAddress().isLoopbackAddress()) return;
            s.setSoTimeout(20000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line == null || line.length() > 8192) return;
            String[] first = line.split(" ");
            if (first.length < 2 || !"GET".equals(first[0])) {
                respond(out, 405, "text/plain", "Only GET allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String requestPath = first[1];
            String providedCode = "";
            long start = 0;
            for (int i = 0; i < 60; i++) {
                line = reader.readLine();
                if (line == null || line.isEmpty()) break;
                if (line.length() > 8192) return;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-media-key:")) providedCode = line.substring(line.indexOf(':') + 1).trim();
                else if (lower.startsWith("range:")) {
                    String value = line.substring(line.indexOf(':') + 1).trim();
                    if (value.startsWith("bytes=") && value.endsWith("-")) {
                        try { start = Long.parseLong(value.substring(6, value.length() - 1)); }
                        catch (Exception ignored) { start = 0; }
                    }
                }
            }
            if (!code.equals(providedCode)) {
                respond(out, 403, "text/plain", "Wrong pairing code".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (requestPath.equals("/list")) {
                JSONObject data = new JSONObject();
                data.put("protocol", 1);
                JSONArray entries = new JSONArray();
                for (MediaLibrary.Film item : byId.values()) entries.put(item.asJson());
                data.put("files", entries);
                respond(out, 200, "application/json; charset=utf-8", data.toString().getBytes(StandardCharsets.UTF_8));
            } else if (requestPath.startsWith("/file?id=")) {
                String id = URLDecoder.decode(requestPath.substring(9), "UTF-8");
                MediaLibrary.Film film = byId.get(id);
                if (film == null) {
                    respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if (start < 0 || (film.size >= 0 && start >= film.size && start != 0)) {
                    respond(out, 416, "text/plain", "Bad range".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                sendFile(out, film, start);
            } else {
                respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { /* Client disconnected. */ }
    }

    private void sendFile(OutputStream out, MediaLibrary.Film item, long offset) throws Exception {
        try (InputStream source = resolver.openInputStream(item.uri)) {
            if (source == null) throw new IllegalStateException("Unable to open source file");
            long skipped = 0;
            while (skipped < offset) {
                long n = source.skip(offset - skipped);
                if (n <= 0) {
                    if (source.read() == -1) throw new IllegalStateException("End of file");
                    n = 1;
                }
                skipped += n;
            }
            boolean partial = offset > 0;
            StringBuilder head = new StringBuilder();
            head.append(partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: application/octet-stream\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            if (item.size >= 0) {
                head.append("Content-Length: ").append(item.size - offset).append("\r\n");
                if (partial) head.append("Content-Range: bytes ").append(offset).append('-')
                        .append(item.size - 1).append('/').append(item.size).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[64 * 1024];
            int length;
            while ((length = source.read(buffer)) >= 0) {
                if (length > 0) out.write(buffer, 0, length);
            }
            out.flush();
        }
    }

    private static void respond(OutputStream output, int status, String type, byte[] body) throws Exception {
        String text = status == 200 ? "OK" : "Error";
        String header = "HTTP/1.1 " + status + " " + text + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }
}
