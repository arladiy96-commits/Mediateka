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
import org.json.JSONObject;
import android.app.TaskStackBuilder;

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
    static final String START_RECEIVER = "start_receiver";
    static final String STOP_RECEIVER = "stop_receiver";
    private volatile boolean receiverRunning;
    private Thread receiverThread;

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
    private volatile boolean indexRunning;
    private Thread indexThread;
    private long nextDiscoveryAt;
    private long nextServiceRetryAt;
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
        if (intent == null) {
            android.content.SharedPreferences saved = MediaLibrary.prefs(this);
            if (saved.getInt("screen", 0) == 0 && saved.getBoolean("auto_share", true)
                    && (!saved.getString(MediaLibrary.SOURCE_TREE, "").isEmpty()
                    || !saved.getStringSet(MediaLibrary.SOURCE_FILES,
                        java.util.Collections.<String>emptySet()).isEmpty())) {
                startServer();
            } else if (saved.getInt("screen", 0) == 1
                    && saved.getString("remote_code", "").matches("[0-9]{8}")) {
                startReceiver();
            } else stopIfIdle();
            return START_STICKY;
        }
        String command = intent.getStringExtra(CMD);
        if (START_SERVER.equals(command)) startServer();
        else if (STOP_SERVER.equals(command)) stopServer();
        else if (DOWNLOAD.equals(command)) startDownload(intent);
        else if (PAUSE.equals(command)) pauseDownload();
        else if (START_RECEIVER.equals(command)) startReceiver();
        else if (STOP_RECEIVER.equals(command)) stopReceiver();
        return START_STICKY;
    }

    /** Receiver polls the phone; no inbound sockets or new pairing necessary. */
    private synchronized void startReceiver() {
        if (receiverRunning) return;
        receiverRunning = true;
        receiverThread = new Thread(new Runnable() {
            @Override public void run() {
                while (receiverRunning) {
                    try {
                        android.content.SharedPreferences prefs = MediaLibrary.prefs(TransferService.this);
                        String host = prefs.getString("remote_host", "");
                        String code = prefs.getString("remote_code", "");
                        if (!host.isEmpty() && code.matches("[0-9]{8}")) {
                            JSONObject command = RemoteControlClient.poll(TransferService.this, host, code);
                            if (receiverRunning && !"none".equals(command.optString("action", "none"))) {
                                if ("play".equals(command.optString("action"))) {
                                    try {
                                        JSONObject library = new JSONObject(DownloadEngine.list(host, code));
                                        MediaLibrary.prefs(TransferService.this).edit()
                                                .putString("remote_cache", library.getJSONArray("files").toString()).apply();
                                    } catch (Exception ignored) { }
                                }
                                handleRemoteCommand(command);
                            }
                        }
                    } catch (Exception ignored) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now >= nextDiscoveryAt) {
                            nextDiscoveryAt = now + 18000;
                            try {
                                android.content.SharedPreferences p = MediaLibrary.prefs(TransferService.this);
                                String secret = p.getString("remote_code", "");
                                if (secret.matches("[0-9]{8}")) {
                                    for (String candidate : LanDiscovery.find(TransferService.this)) {
                                        try {
                                            DownloadEngine.list(candidate, secret);
                                            p.edit().putString("remote_host", candidate).commit();
                                            break;
                                        } catch (Exception invalid) { }
                                    }
                                }
                            } catch (Exception ignoredDiscovery) { }
                        }
                    }
                    try { Thread.sleep(2500); } catch (InterruptedException interrupted) { break; }
                }
            }
        }, "RemotePlayerPoll");
        receiverThread.start();
        notice("Готов к управлению с POCO");
    }

    private synchronized void stopReceiver() {
        receiverRunning = false;
        if (receiverThread != null) receiverThread.interrupt();
        receiverThread = null;
        stopIfIdle();
    }

    private void handleRemoteCommand(JSONObject command) {
        String action = command.optString("action", "");
        Intent player = new Intent(this, RemotePlayerActivity.class)
                .putExtra("remote_action", action)
                .putExtra("remote_id", command.optString("id", ""))
                .putExtra("remote_ticket", command.optString("ticket", ""));
        if ("seek_to".equals(action)) player.putExtra("remote_ms", command.optInt("ms", 0));
        player.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (RemotePlayerActivity.visible) {
            Intent message = new Intent(RemotePlayerActivity.ACTION_PLAYER);
            message.setPackage(getPackageName());
            message.putExtras(player);
            sendBroadcast(message);
        } else if ("play".equals(action)) {
            if (Build.VERSION.SDK_INT < 29 || MainActivity.uiVisible) {
                try { startActivity(player); return; }
                catch (Exception ignored) { }
            }
            // Android 10+ restricts launching an Activity from the background.
            // A notification is the supported fallback when the app is not visible.
            PendingIntent tap = PendingIntent.getActivity(this, 88, player,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel controlChannel = new NotificationChannel("mediateka_remote_actions",
                        "Запуск видео с телефона", NotificationManager.IMPORTANCE_DEFAULT);
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .createNotificationChannel(controlChannel);
            }
            Notification.Builder alert = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "mediateka_remote_actions") : new Notification.Builder(this);
            alert.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Сериал выбран на POCO")
                    .setContentText("Нажми, чтобы смотреть на этом устройстве")
                    .setContentIntent(tap).setAutoCancel(true);
            if (Build.VERSION.SDK_INT < 26) alert.setPriority(Notification.PRIORITY_HIGH);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(72, alert.build());
        }
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
                    launchIndexRefresh();
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

    private void launchIndexRefresh() {
        indexRunning = true;
        if (indexThread != null) indexThread.interrupt();
        indexThread = new Thread(() -> {
            while (indexRunning && serverRunning) {
                try { Thread.sleep(90000); }
                catch (InterruptedException stopped) { return; }
                if (!indexRunning || !serverRunning) break;
                try {
                    List<MediaLibrary.Film> latest = MediaLibrary.scan(TransferService.this);
                    HttpFileServer active = server;
                    if (active != null && indexRunning) active.updateLibrary(latest);
                } catch (Exception ignored) { }
            }
        }, "MediatekaLibraryScan");
        indexThread.setDaemon(true);
        indexThread.start();
    }

    private void stopServer() {
        indexRunning = false;
        if (indexThread != null) indexThread.interrupt();
        indexThread = null;
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
        if (!serverRunning && !downloading && !receiverRunning) stopSelf();
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
                .setOngoing(serverRunning || downloading || receiverRunning);
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
        indexRunning = false;
        if (indexThread != null) indexThread.interrupt();
        receiverRunning = false;
        if (receiverThread != null) receiverThread.interrupt();
        if (server != null) server.stop();
        if (engine != null) engine.interrupt();
        serverRunning = false;
        downloading = false;
        releaseWake();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
