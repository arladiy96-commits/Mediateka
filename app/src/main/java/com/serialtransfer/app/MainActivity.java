package com.serialtransfer.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.DhcpInfo;
import android.net.Uri;
import android.content.ActivityNotFoundException;
import java.net.URLEncoder;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Low-memory native interface, compatible with Android 7. Pair once and reconnect later. */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 101;
    private static final int PICK_SOURCE_FOLDER = 102;
    private static final int PICK_SD_FOLDER = 103;
    private static final int BG = Color.rgb(14, 21, 28);
    private static final int PANEL = Color.rgb(27, 39, 49);
    private static final int DIVIDER = Color.rgb(44, 60, 69);
    private static final int ACCENT = Color.rgb(102, 205, 181);
    private static final int WHITE = Color.rgb(241, 245, 245);
    private static final int MUTED = Color.rgb(154, 171, 177);

    private LinearLayout content;
    private TextView hostInfo;
    private TextView linkStatus;
    private TextView transferStatus;
    private Button downloadButton;
    private Button pauseButton;
    private final List<CheckBox> episodeChecks = new ArrayList<>();
    private final Set<String> chosen = new HashSet<>();
    private JSONArray remoteFiles = new JSONArray();
    private String streamTicket = "";
    private int mode;
    private boolean connecting;
    private boolean disposed;
    private boolean linkConnected;
    private int seenQueueRevision;
    private long nextReconnectAt;
    private String statusMessage = "Готово к подключению";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (disposed) return;
            if (hostInfo != null) {
                if (TransferService.serverRunning) {
                    hostInfo.setText("● Раздача включена\n"
                            + TransferService.serverIp + ":" + HttpFileServer.PORT
                            + "\nКод: " + TransferService.accessCode);
                    hostInfo.setTextColor(ACCENT);
                } else {
                    hostInfo.setText("○ Раздача выключена\nНажми «Включить раздачу»");
                    hostInfo.setTextColor(MUTED);
                }
            }
            if (linkStatus != null) linkStatus.setText(statusMessage);
            if (pauseButton != null) pauseButton.setVisibility(TransferService.downloading ? View.VISIBLE : View.GONE);
            if (transferStatus != null) {
                String description = TransferService.progressText;
                if (TransferService.downloading && TransferService.progressTotal > 0) {
                    description += "\n" + MediaLibrary.readableSize(TransferService.progressBytes)
                            + " из " + MediaLibrary.readableSize(TransferService.progressTotal);
                }
                transferStatus.setText(description);
            }
            if (mode == 1 && seenQueueRevision != TransferService.queueRevision) {
                seenQueueRevision = TransferService.queueRevision;
                render();
            }
            // When the app stays open, try reconnecting after Wi-Fi returns.
            // A manual pause deliberately disables automatic resuming.
            long now = android.os.SystemClock.elapsedRealtime();
            if (mode == 1 && now >= nextReconnectAt && !connecting
                    && !TransferService.downloading && !DownloadQueue.paused(MainActivity.this)
                    && DownloadQueue.waiting(MainActivity.this) > 0) {
                nextReconnectAt = now + 45000;
                connectSaved(true);
            }
            handler.postDelayed(this, 900);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        SharedPreferences prefs = MediaLibrary.prefs(this);
        mode = prefs.getInt("screen", 0);
        try { remoteFiles = new JSONArray(prefs.getString("remote_cache", "[]")); }
        catch (Exception ignored) { remoteFiles = new JSONArray(); }
        render();
        handler.post(ticker);
        if (mode == 0) startSenderIfRemembered();
        else handler.postDelayed(new Runnable() {
            @Override public void run() { connectSaved(true); }
        }, 450);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 77);
        }
    }

    @Override protected void onDestroy() {
        disposed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void render() {
        seenQueueRevision = TransferService.queueRevision;
        hostInfo = null;
        linkStatus = null;
        transferStatus = null;
        downloadButton = null;
        pauseButton = null;
        episodeChecks.clear();

        LinearLayout screen = column();
        screen.setBackgroundColor(BG);
        screen.setPadding(0, 0, 0, 0);

        LinearLayout top = column();
        top.setPadding(dp(18), dp(18), dp(18), dp(10));
        top.addView(text("МЕДИАТЕКА", 23, WHITE, true));
        top.addView(text("Сериалы рядом, без интернета", 12, MUTED, false));
        screen.addView(top);

        LinearLayout tabs = row();
        tabs.setPadding(dp(14), dp(3), dp(14), dp(10));
        Button sender = button("Телефон", mode == 0);
        Button receiver = button("Планшет", mode == 1);
        tabs.addView(sender, new LinearLayout.LayoutParams(0, dp(42), 1));
        LinearLayout.LayoutParams tab2 = new LinearLayout.LayoutParams(0, dp(42), 1);
        tab2.leftMargin = dp(6);
        tabs.addView(receiver, tab2);
        sender.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseMode(0); }
        });
        receiver.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { chooseMode(1); }
        });
        screen.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(14), dp(2), dp(14), dp(16));
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (mode == 0) showSender();
        else {
            showReceiver();
            buildBottomBar(screen);
        }
        setContentView(screen);
    }

    private void chooseMode(int next) {
        if (mode == next) return;
        mode = next;
        linkConnected = false;
        streamTicket = "";
        MediaLibrary.prefs(this).edit().putInt("screen", mode).apply();
        render();
        if (mode == 0) startSenderIfRemembered();
        else connectSaved(true);
    }

    private void showSender() {
        sectionTitle("Доступ с планшета", "Телефон и Huawei должны быть в одной сети Wi-Fi.");
        LinearLayout statusBox = panel();
        hostInfo = text("Проверяю раздачу...", 17, ACCENT, true);
        statusBox.addView(hostInfo);
        content.addView(statusBox, spaced(0, 12));
        rowActions("Включить / обновить", "Остановить",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        MediaLibrary.prefs(MainActivity.this).edit().putBoolean("auto_share", true).apply();
                        TransferService.send(MainActivity.this, TransferService.START_SERVER);
                        toast("Обновляю список сериалов");
                    }
                }, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        MediaLibrary.prefs(MainActivity.this).edit().putBoolean("auto_share", false).apply();
                        TransferService.send(MainActivity.this, TransferService.STOP_SERVER);
                    }
                });
        note("Код постоянный: после первого подключения Huawei запомнит телефон.");

        sectionTitle("Твои серии", "Можно добавить отдельные файлы или всю папку с сезонами.");
        LinearLayout library = panel();
        final TextView count = text("Читаю библиотеку...", 15, WHITE, true);
        final TextView preview = text("", 12, MUTED, false);
        library.addView(count);
        LinearLayout.LayoutParams previewParams = spaced(6, 0);
        library.addView(preview, previewParams);
        content.addView(library, spaced(0, 10));
        scanLocal(count, preview);
        action("Выбрать папку с сериалами", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_SOURCE_FOLDER);
            }
        });
        action("Добавить отдельные серии", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(intent, PICK_FILES);
            }
        });
        link("Очистить список серий", new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Очистить библиотеку?")
                        .setMessage("Файлы на телефоне останутся. Удалится только список для раздачи.")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Очистить", (d, w) -> {
                            MediaLibrary.clearSources(MainActivity.this);
                            TransferService.send(MainActivity.this, TransferService.STOP_SERVER);
                            render();
                        }).show();
            }
        });
    }

    private void scanLocal(final TextView count, final TextView preview) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MediaLibrary.Film> files = MediaLibrary.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (disposed) return;
                        count.setText(files.size() + " серий в библиотеке");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(files.size(), 4); i++) {
                            if (i > 0) sb.append('\n');
                            sb.append("· ").append(files.get(i).name);
                        }
                        if (files.size() > 4) sb.append("\n… и ещё ").append(files.size() - 4);
                        preview.setText(sb.length() == 0 ? "Пока пусто. Добавь первую серию." : sb.toString());
                    }
                });
            }
        }, "LibraryPreview").start();
    }

    private void startSenderIfRemembered() {
        SharedPreferences p = MediaLibrary.prefs(this);
        boolean hasTree = !p.getString(MediaLibrary.SOURCE_TREE, "").isEmpty();
        boolean hasFiles = !p.getStringSet(MediaLibrary.SOURCE_FILES,
                java.util.Collections.<String>emptySet()).isEmpty();
        if ((hasTree || hasFiles) && p.getBoolean("auto_share", true) && !TransferService.serverRunning) {
            TransferService.send(this, TransferService.START_SERVER);
        }
    }

    private void showReceiver() {
        SharedPreferences prefs = MediaLibrary.prefs(this);
        String host = prefs.getString("remote_host", "");
        boolean remembered = !host.isEmpty() && prefs.getString("remote_code", "").matches("[0-9]{8}");
        sectionTitle("Телефон", remembered ? "Подключение сохранено — повторный ввод не нужен." :
                "Первый раз введи IP и код с экрана телефона.");
        LinearLayout connection = panel();
        linkStatus = text(remembered ? "Соединяюсь с телефоном..." : "Телефон ещё не подключён", 15,
                remembered ? ACCENT : MUTED, true);
        connection.addView(linkStatus);
        if (remembered) {
            TextView saved = text("Сохранён: " + host, 12, MUTED, false);
            connection.addView(saved, spaced(7, 0));
        }
        content.addView(connection, spaced(0, 9));
        if (!remembered) {
            action("Подключить телефон", true, new View.OnClickListener() {
                @Override public void onClick(View v) { showPairingDialog(); }
            });
        } else {
            rowActions("Обновить серии", "Настройки",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { connectSaved(false); }
                    }, new View.OnClickListener() {
                        @Override public void onClick(View v) { showPairingDialog(); }
                    });
        }

        sectionTitle("Куда сохранять", "Выбранная папка сохранится для следующих загрузок.");
        String tree = prefs.getString(MediaLibrary.DEST_TREE, "");
        LinearLayout folder = panel();
        folder.addView(text(tree.isEmpty() ? "○ Выбери папку на microSD" :
                "✓ Папка для серий выбрана", 14, tree.isEmpty() ? MUTED : ACCENT, true));
        content.addView(folder, spaced(0, 9));
        action(tree.isEmpty() ? "Выбрать microSD" : "Изменить папку сохранения", false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(intent, PICK_SD_FOLDER);
                    }
                });

        int waiting = DownloadQueue.waiting(this);
        if (waiting > 0) {
            LinearLayout queuePanel = panel();
            queuePanel.addView(text("Очередь сохранена · " + waiting + " серий", 15, ACCENT, true));
            queuePanel.addView(text(DownloadQueue.paused(this)
                    ? "На паузе. Нажми «Скачать недостающие», чтобы продолжить."
                    : "После подключения к POCO очередь продолжится автоматически.",
                    12, MUTED, false), spaced(5, 0));
            content.addView(queuePanel, spaced(8, 8));
        }
        sectionTitle("Доступные серии", remoteFiles.length() == 0 ?
                "После подключения здесь появится библиотека телефона." :
                "▶ — смотреть по Wi-Fi без скачивания; галочка — добавить в очередь.");
        if (remoteFiles.length() > 0) {
            int finished = 0;
            int missing = 0;
            for (int i = 0; i < remoteFiles.length(); i++) {
                JSONObject item = remoteFiles.optJSONObject(i);
                if (item == null) continue;
                if (DownloadQueue.DONE.equals(DownloadQueue.status(this, item.optString("id")))) finished++;
                else missing++;
            }
            note("На microSD: " + finished + " · Ещё не скачано: " + missing);
            if (missing > 0) {
                action("Скачать недостающие · " + missing, true, new View.OnClickListener() {
                    @Override public void onClick(View v) { downloadAllMissing(); }
                });
            }
            LinearLayout selection = row();
            TextView summary = text(remoteFiles.length() + " файлов", 14, MUTED, false);
            selection.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
            linkInto(selection, "Выбрать все", new View.OnClickListener() {
                @Override public void onClick(View v) { setAll(true); }
            });
            linkInto(selection, "Снять", new View.OnClickListener() {
                @Override public void onClick(View v) { setAll(false); }
            });
            content.addView(selection, spaced(0, 10));
            final Set<String> queuedIds = DownloadQueue.queuedIds(this);
            LinearLayout listing = panel();
            listing.setPadding(dp(7), dp(5), dp(7), dp(5));
            for (int i = 0; i < remoteFiles.length(); i++) {
                JSONObject episode = remoteFiles.optJSONObject(i);
                if (episode == null) continue;
                final String id = episode.optString("id");
                CheckBox check = new CheckBox(this);
                check.setTag(id);
                String state = DownloadQueue.status(this, id);
                boolean queued = queuedIds.contains(id);
                if (DownloadQueue.DONE.equals(state)) chosen.remove(id);
                String stateLabel = DownloadQueue.DONE.equals(state) ? "✓ Скачано на microSD"
                        : queued ? "◷ В очереди"
                        : DownloadQueue.PARTIAL.equals(state) ? "↻ Можно продолжить"
                        : "Не скачано";
                check.setText(episode.optString("name", "Серия") + "\n"
                        + MediaLibrary.readableSize(episode.optLong("size", -1))
                        + " · " + stateLabel);
                check.setTextSize(13);
                check.setTextColor(WHITE);
                check.setPadding(dp(7), dp(7), dp(4), dp(7));
                check.setButtonTintList(ColorStateList.valueOf(ACCENT));
                check.setChecked(chosen.contains(id));
                check.setEnabled(!DownloadQueue.DONE.equals(state) && !queued);
                if (!check.isEnabled()) check.setTextColor(MUTED);
                check.setOnCheckedChangeListener((b, selected) -> {
                    if (selected) chosen.add(id); else chosen.remove(id);
                    updateDownloadButton();
                });
                LinearLayout episodeRow = row();
                episodeRow.addView(check, new LinearLayout.LayoutParams(0, -2, 1));
                Button play = button("▶", false);
                play.setContentDescription("Смотреть: " + episode.optString("name", "Серия"));
                LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(46), dp(45));
                playParams.leftMargin = dp(3);
                episodeRow.addView(play, playParams);
                play.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openStream(id); }
                });
                listing.addView(episodeRow, new LinearLayout.LayoutParams(-1, -2));
                episodeChecks.add(check);
                if (i != remoteFiles.length() - 1) {
                    View line = new View(this);
                    line.setBackgroundColor(DIVIDER);
                    listing.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
                }
            }
            content.addView(listing);
        } else {
            LinearLayout placeholder = panel();
            placeholder.addView(text("Здесь появятся серии с POCO", 14, MUTED, false));
            content.addView(placeholder);
        }
    }

    private void buildBottomBar(LinearLayout screen) {
        LinearLayout bar = column();
        bar.setBackgroundColor(PANEL);
        bar.setPadding(dp(14), dp(6), dp(14), dp(10));
        transferStatus = text(TransferService.progressText, 12, MUTED, false);
        transferStatus.setMaxLines(2);
        bar.addView(transferStatus, spaced(0, 6));
        LinearLayout controls = row();
        downloadButton = button("Скачать серии", true);
        controls.addView(downloadButton, new LinearLayout.LayoutParams(0, dp(49), 1));
        downloadButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { beginDownload(); }
        });
        pauseButton = button("Пауза", false);
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(dp(94), dp(49));
        pauseParams.leftMargin = dp(8);
        controls.addView(pauseButton, pauseParams);
        pauseButton.setVisibility(TransferService.downloading ? View.VISIBLE : View.GONE);
        pauseButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                TransferService.send(MainActivity.this, TransferService.PAUSE);
            }
        });
        bar.addView(controls);
        updateDownloadButton();
        screen.addView(bar);
    }

    private void updateDownloadButton() {
        if (downloadButton == null) return;
        int selected = chosen.size();
        downloadButton.setText(selected == 0 ? "Выбери серии" :
                "Добавить в загрузки · " + selected);
        downloadButton.setEnabled(selected > 0);
        downloadButton.setAlpha(selected > 0 ? 1f : .55f);
    }

    private void setAll(boolean selected) {
        chosen.clear();
        for (CheckBox check : episodeChecks) {
            if (!check.isEnabled()) continue;
            check.setChecked(selected);
            if (selected) chosen.add((String) check.getTag());
        }
        updateDownloadButton();
    }

    /** Stores the pairing once. Existing pairings can be adjusted under Settings. */
    private void showPairingDialog() {
        final SharedPreferences prefs = MediaLibrary.prefs(this);
        LinearLayout form = column();
        form.setPadding(dp(19), dp(8), dp(19), dp(3));
        form.addView(text("IP телефона", 13, MUTED, false));
        final EditText host = input("Например, 10.42.103.251",
                prefs.getString("remote_host", ""), false);
        form.addView(host, spaced(5, 14));
        form.addView(text("Код из 8 цифр", 13, MUTED, false));
        final EditText code = input("Код с экрана POCO",
                prefs.getString("remote_code", ""), true);
        form.addView(code, spaced(5, 2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Подключение к POCO")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String parsed;
                    try { parsed = DownloadEngine.validatedHost(host.getText().toString()); }
                    catch (Exception e) { host.setError("Проверь IP"); return; }
                    String secret = code.getText().toString().trim();
                    if (!secret.matches("[0-9]{8}")) { code.setError("Нужно 8 цифр"); return; }
                    prefs.edit().putString("remote_host", parsed)
                            .putString("remote_code", secret).apply();
                    remoteFiles = new JSONArray();
                    chosen.clear();
                    linkConnected = false;
                    streamTicket = "";
                    prefs.edit().remove("remote_cache").apply();
                    dialog.dismiss();
                    statusMessage = "Подключаюсь...";
                    render();
                    connectSaved(false);
                }));
        dialog.show();
    }

    private void connectSaved(boolean automatic) {
        if (mode != 1 || connecting) return;
        SharedPreferences p = MediaLibrary.prefs(this);
        final String rememberedHost = p.getString("remote_host", "");
        final String key = p.getString("remote_code", "");
        if (rememberedHost.isEmpty() || !key.matches("[0-9]{8}")) return;
        connecting = true;
        nextReconnectAt = android.os.SystemClock.elapsedRealtime() + 45000;
        statusMessage = "Соединяюсь с POCO...";
        if (linkStatus != null) linkStatus.setText(statusMessage);
        new Thread(new Runnable() {
            @Override public void run() {
                String connectedHost = rememberedHost;
                JSONArray result = null;
                Exception failure = null;
                String ticket = "";
                try {
                    JSONObject listing = new JSONObject(DownloadEngine.list(connectedHost, key));
                    result = listing.getJSONArray("files");
                    ticket = listing.optString("stream_ticket", "");
                } catch (Exception e) { failure = e; }
                // An Android hotspot can change its IP after a reboot. The Wi-Fi
                // gateway usually points to the sender when POCO hosts the hotspot.
                if (result == null) {
                    String gateway = wifiGateway();
                    if (!gateway.isEmpty() && !gateway.equals(rememberedHost)) {
                        try {
                            JSONObject listing = new JSONObject(DownloadEngine.list(gateway, key));
                            result = listing.getJSONArray("files");
                            ticket = listing.optString("stream_ticket", "");
                            connectedHost = gateway;
                            failure = null;
                        } catch (Exception e) { failure = e; }
                    }
                }
                // Reconcile in this worker thread: SD-card document queries may be slow.
                if (result != null) {
                    MediaLibrary.prefs(MainActivity.this).edit()
                            .putString("remote_host", connectedHost)
                            .putString("remote_cache", result.toString()).commit();
                    DownloadQueue.reconcile(MainActivity.this, result, rememberedHost, connectedHost);
                }
                final JSONArray episodes = result;
                final String playbackTicket = ticket;
                final String host = connectedHost;
                final Exception error = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (disposed) return;
                        connecting = false;
                        if (episodes != null) {
                            linkConnected = true;
                            streamTicket = playbackTicket;
                            remoteFiles = episodes;
                            // Preserve already selected episodes when refreshing.
                            Set<String> available = new HashSet<>();
                            for (int i = 0; i < episodes.length(); i++) {
                                JSONObject item = episodes.optJSONObject(i);
                                if (item != null) available.add(item.optString("id"));
                            }
                            chosen.retainAll(available);
                            statusMessage = "● Подключено к POCO · " + episodes.length() + " серий";
                            render();
                            if (!DownloadQueue.paused(MainActivity.this)
                                    && DownloadQueue.waiting(MainActivity.this) > 0) {
                                startQueueDownload(true);
                            }
                        } else {
                            linkConnected = false;
                            streamTicket = "";
                            statusMessage = "Нет связи с POCO. Очередь сохранена — проверь Wi-Fi.";
                            if (!automatic && error != null) toast("Подключение: " + error.getMessage());
                            if (linkStatus != null) linkStatus.setText(statusMessage);
                        }
                    }
                });
            }
        }, "ReconnectSavedPhone").start();
    }

    private String wifiGateway() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return "";
            DhcpInfo dhcp = wifi.getDhcpInfo();
            if (dhcp == null || dhcp.gateway == 0) return "";
            int value = dhcp.gateway;
            return String.format(Locale.US, "%d.%d.%d.%d", value & 255,
                    (value >> 8) & 255, (value >> 16) & 255, (value >> 24) & 255);
        } catch (Exception ignored) { return ""; }
    }

    /** Stream in a third-party video player, such as VLC, without saving on this device. */
    private void openStream(String episodeId) {
        if (!linkConnected || !streamTicket.matches("[0-9a-f]{48}")) {
            toast("Нет ссылки для просмотра. Обнови раздачу на POCO и список серий.");
            return;
        }
        if (!episodeId.matches("[0-9a-f]{24}")) {
            toast("Неверный файл");
            return;
        }
        try {
            String host = DownloadEngine.validatedHost(
                    MediaLibrary.prefs(this).getString("remote_host", ""));
            String url = "http://" + host + ":" + HttpFileServer.PORT
                    + "/stream?id=" + URLEncoder.encode(episodeId, "UTF-8")
                    + "&ticket=" + URLEncoder.encode(streamTicket, "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            intent.addCategory(Intent.CATEGORY_DEFAULT);
            try {
                startActivity(Intent.createChooser(intent, "Смотреть через видеоплеер"));
            } catch (ActivityNotFoundException notInstalled) {
                toast("Установи VLC или другой плеер для сетевого видео");
            }
        } catch (Exception problem) {
            toast("Не удалось открыть видео: " + problem.getMessage());
        }
    }

    private void beginDownload() {
        if (MediaLibrary.prefs(this).getString(MediaLibrary.DEST_TREE, "").isEmpty()) {
            toast("Сначала выбери папку на microSD");
            return;
        }
        if (chosen.isEmpty()) { toast("Отметь нужные серии"); return; }
        JSONArray selected = new JSONArray();
        for (int i = 0; i < remoteFiles.length(); i++) {
            JSONObject item = remoteFiles.optJSONObject(i);
            if (item != null && chosen.contains(item.optString("id"))) selected.put(item);
        }
        int added = DownloadQueue.enqueue(this, selected);
        chosen.clear();
        DownloadQueue.setPaused(this, false);
        toast(added > 0 ? "Серии добавлены в очередь" : "Все выбранные серии уже в очереди");
        render();
        startQueueDownload(false);
    }

    private void downloadAllMissing() {
        if (remoteFiles.length() == 0) { toast("Нет списка серий"); return; }
        if (MediaLibrary.prefs(this).getString(MediaLibrary.DEST_TREE, "").isEmpty()) {
            toast("Сначала выбери папку на microSD");
            return;
        }
        int added = DownloadQueue.enqueue(this, remoteFiles);
        chosen.clear();
        DownloadQueue.setPaused(this, false);
        toast(added > 0 ? "Добавлено в очередь: " + added : "Продолжаю сохранённую очередь");
        render();
        startQueueDownload(false);
    }

    /** A saved queue can resume after a reconnect without reselecting episodes. */
    private void startQueueDownload(boolean automatic) {
        SharedPreferences p = MediaLibrary.prefs(this);
        String root = p.getString(MediaLibrary.DEST_TREE, "");
        if (root.isEmpty()) {
            if (!automatic) toast("Сначала выбери папку на microSD");
            return;
        }
        if (DownloadQueue.paused(this) && automatic) return;
        if (TransferService.downloading) return;
        if (!linkConnected) {
            if (!automatic) toast("Очередь сохранена. Включи Wi-Fi и раздачу на POCO");
            return;
        }
        JSONArray selected = DownloadQueue.availablePending(this, remoteFiles);
        if (selected.length() == 0) return;
        String host;
        try { host = DownloadEngine.validatedHost(p.getString("remote_host", "")); }
        catch (Exception e) { if (!automatic) toast("Проверь IP телефона"); return; }
        String code = p.getString("remote_code", "");
        if (!code.matches("[0-9]{8}")) return;
        Intent intent = new Intent(this, TransferService.class);
        intent.putExtra(TransferService.CMD, TransferService.DOWNLOAD);
        intent.putExtra("host", host);
        intent.putExtra("code", code);
        intent.putExtra("destination", root);
        intent.putExtra("items", selected.toString());
        TransferService.progressText = "Скачивание " + selected.length() + " серий...";
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == PICK_SOURCE_FOLDER || requestCode == PICK_SD_FOLDER) {
                Uri uri = data.getData();
                if (uri == null) return;
                int need = requestCode == PICK_SD_FOLDER
                        ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        : Intent.FLAG_GRANT_READ_URI_PERMISSION;
                int granted = data.getFlags() & need;
                getContentResolver().takePersistableUriPermission(uri, granted);
                String key = requestCode == PICK_SD_FOLDER ? MediaLibrary.DEST_TREE : MediaLibrary.SOURCE_TREE;
                String previous = MediaLibrary.prefs(this).getString(key, "");
                MediaLibrary.prefs(this).edit().putString(key, uri.toString()).apply();
                if (requestCode == PICK_SD_FOLDER && !previous.equals(uri.toString())) {
                    DownloadQueue.destinationChanged(this);
                }
                if (requestCode == PICK_SOURCE_FOLDER) {
                    MediaLibrary.prefs(this).edit().putBoolean("auto_share", true).apply();
                    TransferService.send(this, TransferService.START_SERVER);
                }
                toast(requestCode == PICK_SD_FOLDER ? "Папка сохранена" : "Библиотека обновляется");
            } else if (requestCode == PICK_FILES) {
                List<Uri> uris = new ArrayList<>();
                ClipData clips = data.getClipData();
                if (clips != null) for (int i = 0; i < clips.getItemCount(); i++)
                    uris.add(clips.getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
                for (Uri uri : uris) {
                    try { getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception ignored) { }
                }
                MediaLibrary.addFiles(this, uris);
                MediaLibrary.prefs(this).edit().putBoolean("auto_share", true).apply();
                TransferService.send(this, TransferService.START_SERVER);
                toast("Добавлено: " + uris.size());
            }
            render();
        } catch (Exception e) { toast("Ошибка доступа: " + e.getMessage()); }
    }

    // Small native UI helpers — no WebView, images, animation engine or large dependencies.
    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density * value + .5f); }
    private LinearLayout column() { LinearLayout box = new LinearLayout(this); box.setOrientation(1); return box; }
    private LinearLayout row() { LinearLayout box = new LinearLayout(this); box.setGravity(Gravity.CENTER_VERTICAL); return box; }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable b = new GradientDrawable(); b.setColor(color); b.setCornerRadius(dp(radius)); return b;
    }
    private LinearLayout panel() {
        LinearLayout pane = column(); pane.setPadding(dp(15), dp(14), dp(15), dp(14));
        pane.setBackground(background(PANEL, 12)); return pane;
    }
    private TextView text(String message, int size, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(message); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }
    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom); return lp;
    }
    private Button button(String value, boolean prominent) {
        Button b = new Button(this); b.setAllCaps(false); b.setText(value); b.setTextSize(13);
        b.setTextColor(prominent ? BG : WHITE); b.setBackground(background(prominent ? ACCENT : PANEL, 10));
        b.setPadding(dp(8), dp(5), dp(8), dp(5)); return b;
    }
    private void action(String title, boolean prominent, View.OnClickListener click) {
        Button b = button(title, prominent); b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.bottomMargin = dp(8); content.addView(b, lp);
    }
    private void rowActions(String first, String second, View.OnClickListener one, View.OnClickListener two) {
        LinearLayout bar = row();
        Button primary = button(first, true);
        Button secondary = button(second, false);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(46), 1.15f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(46), .85f);
        b.leftMargin = dp(7);
        bar.addView(primary, a); bar.addView(secondary, b);
        primary.setOnClickListener(one); secondary.setOnClickListener(two);
        content.addView(bar, spaced(0, 8));
    }
    private void sectionTitle(String title, String sub) {
        content.addView(text(title, 18, WHITE, true), spaced(12, 5));
        note(sub);
    }
    private void note(String description) { content.addView(text(description, 12, MUTED, false), spaced(0, 10)); }
    private void link(String title, View.OnClickListener action) {
        TextView view = text(title, 13, MUTED, false);
        view.setPadding(dp(8), dp(10), dp(8), dp(10));
        view.setOnClickListener(action); content.addView(view);
    }
    private void linkInto(LinearLayout bar, String label, View.OnClickListener listener) {
        TextView t = text(label, 12, ACCENT, false);
        t.setPadding(dp(8), dp(5), dp(8), dp(5)); t.setOnClickListener(listener); bar.addView(t);
    }
    private EditText input(String hint, String saved, boolean code) {
        EditText box = new EditText(this);
        box.setSingleLine(true); box.setText(saved); box.setHint(hint); box.setTextColor(WHITE);
        box.setHintTextColor(MUTED); box.setTextSize(14);
        box.setInputType(code ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        box.setPadding(dp(13), dp(11), dp(13), dp(11)); box.setBackground(background(PANEL, 9));
        return box;
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
}
