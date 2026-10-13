package com.serialtransfer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground host/downloader so activity closure doesn't terminate a transfer. */
public final class TransferService extends Service {
    static final String CMD = "command";
    static final String START_SERVER = "start_server";
    static final String STOP_SERVER = "stop_server";
    static final String DOWNLOAD = "download";
    static final String PAUSE = "pause";
    static final int NOTICE_ID = 71;
    static final String CHANNEL_ID = "mediateka_transfer";

    static volatile boolean serverRunning = false;
    static volatile boolean downloading = false;
    static volatile String serverIp = "";
    static volatile String accessCode = "";
    static volatile String progressText = "Готово к работе";
    static volatile long progressBytes = 0;
    static volatile long progressTotal = -1;
    static volatile int queueRevision = 0;

    private HttpFileServer server;
    private DownloadEngine engine;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private PowerManager.WakeLock wake;

    static void send(Context context, String command) {
        Intent intent = new Intent(context, TransferService.class).putExtra(CMD, command);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTICE_ID, notification("Медиатека готова"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String command = intent.getStringExtra(CMD);
        if (START_SERVER.equals(command)) startServer();
        else if (STOP_SERVER.equals(command)) stopServer();
        else if (DOWNLOAD.equals(command)) startDownload(intent);
        else if (PAUSE.equals(command)) pauseDownload();
        return START_NOT_STICKY;
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // Restart to publish a fresh folder selection / index.
                    if (server != null) { server.stop(); server = null; }
                    serverRunning = false;
                    List<MediaLibrary.Film> entries = MediaLibrary.scan(TransferService.this);
                    // A saved pairing code is essential: the tablet must not be
                    // forced to pair again every time the phone restarts.
                    android.content.SharedPreferences pairing = MediaLibrary.prefs(TransferService.this);
                    String code = pairing.getString("stable_access_code", "");
                    if (code == null || !code.matches("[0-9]{8}")) {
                        code = String.format(Locale.US, "%08d", new SecureRandom().nextInt(100000000));
                        if (!pairing.edit().putString("stable_access_code", code).commit()) {
                            throw new IllegalStateException("Не удалось сохранить код подключения");
                        }
                    }
                    HttpFileServer next = new HttpFileServer(TransferService.this, entries, code);
                    next.start();
                    server = next;
                    accessCode = code;
                    serverIp = localIpv4();
                    serverRunning = true;
                    progressText = "Раздача включена · " + entries.size() + " файлов";
                    notice(progressText);
                } catch (Exception failure) {
                    serverRunning = false;
                    progressText = "Ошибка раздачи: " + failure.getMessage();
                    notice(progressText);
                    stopIfIdle();
                }
            }
        }, "StartMediaServer").start();
    }

    private void stopServer() {
        if (server != null) { server.stop(); server = null; }
        serverRunning = false;
        accessCode = "";
        serverIp = "";
        progressText = "Раздача остановлена";
        stopIfIdle();
    }

    private void startDownload(Intent intent) {
        if (downloading) return;
        String host = intent.getStringExtra("host");
        String code = intent.getStringExtra("code");
        String folder = intent.getStringExtra("destination");
        String items = intent.getStringExtra("items");
        if (host == null || code == null || folder == null || items == null) {
            progressText = "Не указаны параметры скачивания";
            stopIfIdle();
            return;
        }
        cancel.set(false);
        downloading = true;
        final String finalHost = host;
        final String finalCode = code;
        final String finalFolder = folder;
        final String finalItems = items;
        new Thread(new Runnable() {
            @Override public void run() {
                acquireWake();
                try {
                    engine = new DownloadEngine(TransferService.this, finalHost, finalCode,
                            Uri.parse(finalFolder), cancel, new DownloadEngine.Progress() {
                        private long lastAt = android.os.SystemClock.elapsedRealtime();
                        private long lastDone = 0;
                        private String lastLabel = "";
                        private String lastRate = "";
                        @Override public void onProgress(String label, long done, long total) {
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (!label.equals(lastLabel) || done < lastDone) {
                                lastLabel = label;
                                lastDone = done;
                                lastAt = now;
                                lastRate = "";
                            } else if (now - lastAt >= 950 && done > lastDone) {
                                double bytesPerSec = (done - lastDone) * 1000.0 / (now - lastAt);
                                lastRate = String.format(Locale.US, " · %.1f МБ/с", bytesPerSec / 1048576.0);
                                if (total > done && bytesPerSec > 0) {
                                    long seconds = (long) ((total - done) / bytesPerSec);
                                    lastRate += " · ~" + seconds / 60 + " мин";
                                }
                                lastDone = done;
                                lastAt = now;
                            }
                            progressText = label + lastRate;
                            progressBytes = done;
                            progressTotal = total;
                            notice(label + (total > 0 ? " · " + (100 * done / total) + "%" : ""));
                        }
                    });
                    engine.download(new JSONArray(finalItems));
                    if (cancel.get()) progressText = "Очередь сохранена · загрузка приостановлена";
                    else progressText = DownloadQueue.waiting(TransferService.this) == 0
                            ? "Все серии в очереди загружены"
                            : "Доступные серии загружены · остальные ждут телефон";
                } catch (Exception error) {
                    progressText = cancel.get()
                            ? "Очередь сохранена · загрузка приостановлена"
                            : "Соединение прервано · очередь сохранена";
                } finally {
                    engine = null;
                    downloading = false;
                    releaseWake();
                    notice(progressText);
                    stopIfIdle();
                }
            }
        }, "DownloadEpisodes").start();
    }

    private void pauseDownload() {
        DownloadQueue.setPaused(this, true);
        cancel.set(true);
        DownloadEngine current = engine;
        if (current != null) current.interrupt();
        progressText = "Останавливаю скачивание...";
    }

    private void stopIfIdle() {
        if (!serverRunning && !downloading) stopSelf();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Передача серий",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Локальный Wi-Fi сервер и скачивание файлов");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private Notification notification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Медиатека")
                .setContentText(content)
                .setContentIntent(tap)
                .setOngoing(serverRunning || downloading);
        if (Build.VERSION.SDK_INT < 26) builder.setPriority(Notification.PRIORITY_LOW);
        return builder.build();
    }

    private void notice(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE_ID, notification(text));
    }

    private synchronized void acquireWake() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mediateka:Transfer");
            wake.acquire(2 * 60 * 60 * 1000L);
        }
    }

    private synchronized void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
    }

    static String localIpv4() {
        try {
            List<String> preferred = new ArrayList<>();
            List<String> others = new ArrayList<>();
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                String name = iface.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("rmnet") || name.startsWith("tun") || name.startsWith("p2p")) continue;
                Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String value = addr.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan"))
                        preferred.add(value);
                    else others.add(value);
                }
            }
            if (!preferred.isEmpty()) return android.text.TextUtils.join(" / ", preferred);
            if (!others.isEmpty()) return android.text.TextUtils.join(" / ", others);
        } catch (Exception ignored) { }
        return "IP не найден — проверь Wi-Fi";
    }

    @Override public void onDestroy() {
        if (server != null) server.stop();
        if (engine != null) engine.interrupt();
        serverRunning = false;
        downloading = false;
        releaseWake();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
