package com.serialtransfer.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mediateka Screen: lightweight receiver-only UI for Android 7 / 1GB RAM.
 * The video stream, remote protocol and download engine are unchanged.
 * No artwork, WebView, local server, animation or heavy dependency.
 */
public final class MainActivity extends Activity {
    static volatile boolean uiVisible;

    private static final int BG = Color.rgb(5, 15, 24);
    private static final int PANEL = Color.rgb(13, 32, 44);
    private static final int PANEL_LIGHT = Color.rgb(20, 46, 59);
    private static final int TEXT = Color.rgb(239, 249, 251);
    private static final int MUTED = Color.rgb(146, 170, 184);
    private static final int CYAN = Color.rgb(48, 198, 219);
    private static final int BORDER = Color.rgb(35, 69, 84);
    private static final int PICK_SD = 33;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Set<String> selected = new HashSet<>();

    private volatile boolean closed, connecting, connected;
    private String status = "Не подключено";
    private String ticket = "";
    private JSONArray files = new JSONArray();
    private int page = 0;                 // 0 watch, 1 downloads
    private int filter = 0;               // 0 all, 1 downloaded, 2 missing
    private int visibleCount = 36;
    private int lastQueueRevision = -1;
    private long lastConnectAttempt;
    private LinearLayout body;
    private TextView connectionIndicator, progressIndicator, selectionIndicator;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (connectionIndicator != null) {
                connectionIndicator.setText(connected ? "● POCO" : "○ POCO");
                connectionIndicator.setTextColor(connected ? CYAN : MUTED);
            }
            if (progressIndicator != null) progressIndicator.setText(TransferService.progressText);
            if (page == 1 && lastQueueRevision != TransferService.queueRevision) {
                lastQueueRevision = TransferService.queueRevision;
                draw();
            }
            if (!connected && paired() && !connecting && uiVisible
                    && SystemClock.elapsedRealtime() - lastConnectAttempt > 25000) connect();
            handler.postDelayed(this, 1500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        try { files = new JSONArray(prefs().getString("remote_cache", "[]")); }
        catch (Exception ignored) { }
        draw();
        handler.post(ticker);
        if (paired()) {
            TransferService.send(this, TransferService.START_RECEIVER);
            connect();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        uiVisible = true;
        // A completed episode may have changed its bookmark while this Activity was hidden.
        if (body != null && !closed) draw();
    }

    @Override protected void onPause() {
        uiVisible = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        super.onDestroy();
    }

    private SharedPreferences prefs() { return MediaLibrary.prefs(this); }
    private boolean paired() { return prefs().getString("remote_code", "").matches("[0-9]{8}"); }
    private int dp(float n) { return (int) (getResources().getDisplayMetrics().density * n + .5f); }

    private GradientDrawable shape(int color, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, dp(top), 0, dp(bottom));
        return p;
    }

    private TextView button(String value, boolean primary, Runnable onTap) {
        TextView b = text(value, 13, primary ? BG : TEXT, primary);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(44));
        b.setPadding(dp(10), dp(9), dp(10), dp(9));
        b.setBackground(shape(primary ? CYAN : PANEL_LIGHT,
                primary ? 0 : BORDER, 10));
        b.setClickable(true);
        b.setOnClickListener(v -> onTap.run());
        return b;
    }

    private TextView link(String label, Runnable onTap) {
        TextView v = text(label, 12, CYAN, true);
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        v.setPadding(dp(9), dp(10), 0, dp(10));
        v.setOnClickListener(w -> onTap.run());
        return v;
    }

    private LinearLayout card(int padding) {
        LinearLayout c = vertical();
        c.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        c.setBackground(shape(PANEL, BORDER, 12));
        return c;
    }

    private void heading(String heading, String description) {
        body.addView(text(heading, 18, TEXT, true), spaced(5, 3));
        if (description != null && !description.isEmpty())
            body.addView(text(description, 12, MUTED, false), spaced(0, 10));
    }

    private void draw() {
        LinearLayout root = vertical();
        root.setBackgroundColor(BG);

        // One small header instead of a technical dashboard.
        LinearLayout top = horizontal();
        top.setPadding(dp(15), dp(9), dp(14), dp(9));
        LinearLayout brand = vertical();
        brand.addView(text("▷  Медиатека", 19, TEXT, true));
        brand.addView(text("ЭКРАН", 10, MUTED, false));
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        connectionIndicator = text(connected ? "● POCO" : "○ POCO", 12, connected ? CYAN : MUTED, true);
        connectionIndicator.setPadding(dp(12), dp(12), dp(7), dp(12));
        connectionIndicator.setOnClickListener(v -> connect());
        top.addView(connectionIndicator);
        top.addView(link("⚙", this::optionsDialog), new LinearLayout.LayoutParams(dp(43), dp(46)));
        root.addView(top);
        View separator = new View(this);
        separator.setBackgroundColor(BORDER);
        root.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        body = vertical();
        body.setPadding(dp(14), dp(10), dp(14), dp(14));
        scroller.addView(body);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        progressIndicator = null;
        selectionIndicator = null;

        if (page == 0) libraryPage(); else downloadsPage();

        if (page == 0 && files.length() > 0) {
            LinearLayout downloadActions = horizontal();
            downloadActions.setPadding(dp(12), dp(6), dp(12), dp(5));
            selectionIndicator = button("↓ Выбранные (" + selected.size() + ")", false, () -> queue(false));
            downloadActions.addView(selectionIndicator, new LinearLayout.LayoutParams(0, dp(46), 1));
            View gap = new View(this);
            downloadActions.addView(gap, new LinearLayout.LayoutParams(dp(7), dp(1)));
            downloadActions.addView(button("↓ Недостающие", true, () -> queue(true)),
                    new LinearLayout.LayoutParams(0, dp(46), 1));
            root.addView(downloadActions);
        }

        LinearLayout nav = horizontal();
        nav.setPadding(dp(12), dp(6), dp(12), dp(7));
        nav.setBackgroundColor(Color.rgb(8, 24, 34));
        TextView watch = button("▷  Смотреть", page == 0, () -> { page = 0; draw(); });
        TextView loads = button("↓  Загрузки", page == 1, () -> { page = 1; draw(); });
        nav.addView(watch, new LinearLayout.LayoutParams(0, dp(44), 1));
        View gap = new View(this);
        nav.addView(gap, new LinearLayout.LayoutParams(dp(7), dp(1)));
        nav.addView(loads, new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(nav);
        setContentView(root);
    }

    private void libraryPage() {
        heading("Серии", "Смотри с POCO или открой скачанное");
        if (!paired()) {
            LinearLayout hint = card(12);
            hint.addView(text("Подключи POCO один раз", 14, TEXT, true));
            hint.addView(text("Дальше приложение будет искать телефон автоматически.", 12, MUTED, false), spaced(5, 9));
            hint.addView(button("Подключить", true, this::pairDialog));
            body.addView(hint, spaced(0, 10));
        } else if (!connected) {
            LinearLayout bar = horizontal();
            TextView label = text("Нет соединения · сохранённые серии доступны", 11, MUTED, false);
            bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            bar.addView(link("Обновить", this::connect));
            body.addView(bar, spaced(0, 10));
        } else {
            LinearLayout bar = horizontal();
            bar.addView(text("● Телефон подключён", 12, CYAN, true), new LinearLayout.LayoutParams(0, -2, 1));
            bar.addView(link("Обновить", this::connect));
            body.addView(bar, spaced(0, 10));
        }

        JSONObject resume = WatchHistory.last(this);
        if (resume != null && !resume.optBoolean("finished")) {
            String id = resume.optString("id", "");
            LinearLayout last = card(12);
            last.addView(text("ПРОДОЛЖИТЬ ПРОСМОТР", 10, CYAN, true));
            TextView label = text(readableName(resume.optString("name", "Серия")), 14, TEXT, true);
            label.setMaxLines(2);
            last.addView(label, spaced(6, 4));
            LinearLayout row = horizontal();
            row.addView(text(time(resume.optInt("position")), 12, MUTED, false),
                    new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(button("▶ Продолжить", true, () -> play(id)),
                    new LinearLayout.LayoutParams(dp(142), dp(43)));
            last.addView(row, spaced(2, 0));
            body.addView(last, spaced(0, 11));
        }

        if (files.length() == 0) {
            LinearLayout empty = card(13);
            empty.addView(text("Библиотека пока пустая", 14, TEXT, true));
            empty.addView(text("Добавь серии на POCO и включи раздачу. Уже скачанные видео можно открыть из загрузок.",
                    12, MUTED, false), spaced(6, 0));
            body.addView(empty, spaced(0, 8));
            return;
        }

        LinearLayout filterRow = horizontal();
        String[] modes = {"Все", "Скачано", "Недостающие"};
        for (int i = 0; i < modes.length; ++i) {
            final int value = i;
            TextView tab = button(modes[i], filter == i, () -> {
                filter = value;
                visibleCount = 36;
                draw();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(39), 1);
            if (i > 0) params.leftMargin = dp(5);
            filterRow.addView(tab, params);
        }
        body.addView(filterRow, spaced(0, 10));

        Set<String> queued = DownloadQueue.queuedIds(this);
        int visible = 0, total = 0;
        for (int i = 0; i < files.length(); ++i) {
            JSONObject entry = files.optJSONObject(i);
            if (entry == null) continue;
            String id = entry.optString("id", "");
            boolean done = DownloadQueue.DONE.equals(DownloadQueue.status(this, id));
            if ((filter == 1 && !done) || (filter == 2 && done)) continue;
            total++;
            if (visible >= visibleCount) continue;
            addEpisode(entry, done, queued.contains(id));
            visible++;
        }
        if (total == 0) {
            LinearLayout empty = card(12);
            empty.addView(text(filter == 1 ? "Пока ничего не скачано" : "Нет подходящих серий", 13, MUTED, false));
            body.addView(empty, spaced(0, 4));
        }
        if (total > visible) {
            final int left = total - visible;
            body.addView(button("Показать ещё · " + left, false, () -> {
                visibleCount += 36;
                draw();
            }), spaced(7, 5));
        }
    }

    private void addEpisode(JSONObject episode, boolean downloaded, boolean inQueue) {
        String id = episode.optString("id", "");
        LinearLayout outer = horizontal();
        outer.setPadding(dp(5), dp(4), dp(6), dp(4));
        outer.setBackground(shape(PANEL, 0, 9));

        CheckBox choice = new CheckBox(this);
        choice.setButtonTintList(ColorStateList.valueOf(CYAN));
        choice.setChecked(selected.contains(id));
        choice.setEnabled(!downloaded);
        choice.setOnCheckedChangeListener((b, checked) -> {
            if (checked) selected.add(id); else selected.remove(id);
            if (selectionIndicator != null)
                selectionIndicator.setText("↓ Выбранные (" + selected.size() + ")");
        });
        outer.addView(choice, new LinearLayout.LayoutParams(dp(42), dp(47)));

        LinearLayout details = vertical();
        TextView title = text(readableName(episode.optString("name", "Серия")), 13, TEXT, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title);
        String statusLabel = downloaded ? "Скачано · microSD" :
                inQueue ? "В очереди" : "На POCO";
        JSONObject mark = WatchHistory.item(this, id);
        if (mark != null) statusLabel += mark.optBoolean("finished") ? " · Просмотрено" : " · Продолжить";
        details.addView(text(MediaLibrary.readableSize(episode.optLong("size", -1))
                + " · " + statusLabel, 10, downloaded ? CYAN : MUTED, false), spaced(4, 0));
        details.setOnClickListener(v -> play(id));
        outer.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView playButton = button("▶", false, () -> play(id));
        outer.addView(playButton, new LinearLayout.LayoutParams(dp(45), dp(43)));
        body.addView(outer, spaced(0, 5));
    }

    private void downloadsPage() {
        heading("Загрузки", "Файлы сохраняются непосредственно на microSD");
        String folder = prefs().getString(MediaLibrary.DEST_TREE, "");
        LinearLayout storage = card(11);
        LinearLayout title = horizontal();
        LinearLayout labels = vertical();
        labels.addView(text("Папка сохранения", 13, TEXT, true));
        labels.addView(text(folder.isEmpty() ? "Не выбрана" : "microSD · готова", 11,
                folder.isEmpty() ? MUTED : CYAN, false), spaced(4, 0));
        title.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        title.addView(button("Изменить", false, this::pickSd),
                new LinearLayout.LayoutParams(dp(100), dp(42)));
        storage.addView(title);
        body.addView(storage, spaced(0, 12));

        LinearLayout progress = card(12);
        progress.addView(text("Очередь · " + DownloadQueue.waiting(this), 14, TEXT, true));
        progressIndicator = text(TransferService.progressText, 12, MUTED, false);
        progress.addView(progressIndicator, spaced(6, 8));
        LinearLayout actions = horizontal();
        actions.addView(button("▶ Продолжить", true, () -> {
            DownloadQueue.setPaused(this, false);
            autoResumeQueue();
            draw();
        }), new LinearLayout.LayoutParams(0, dp(43), 1));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(8), dp(1)));
        actions.addView(button("Ⅱ Пауза", false, () -> {
            TransferService.send(this, TransferService.PAUSE);
            draw();
        }), new LinearLayout.LayoutParams(0, dp(43), 1));
        progress.addView(actions);
        body.addView(progress, spaced(0, 12));

        JSONArray all = DownloadQueue.all(this);
        if (all.length() > 0) {
            body.addView(text("ОЖИДАЮТ СКАЧИВАНИЯ", 11, CYAN, true), spaced(2, 7));
            int count = Math.min(all.length(), 30);
            for (int i = 0; i < count; i++) {
                JSONObject job = all.optJSONObject(i);
                if (job == null) continue;
                LinearLayout row = card(10);
                TextView name = text(readableName(job.optString("name", "Серия")), 12, TEXT, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name);
                row.addView(text(MediaLibrary.readableSize(job.optLong("size", -1)) + " · "
                        + (DownloadQueue.paused(this) ? "Пауза" : "В очереди"), 11, MUTED, false), spaced(4, 0));
                body.addView(row, spaced(0, 5));
            }
            if (all.length() > count) body.addView(text("И ещё " + (all.length() - count)
                    + " в очереди", 12, MUTED, false), spaced(6, 5));
        }

        int downloaded = 0;
        for (int i = 0; i < files.length(); ++i) {
            JSONObject item = files.optJSONObject(i);
            if (item != null && DownloadQueue.DONE.equals(DownloadQueue.status(this, item.optString("id", "")))) downloaded++;
        }
        body.addView(text("СКАЧАНО · " + downloaded, 11, CYAN, true), spaced(14, 7));
        if (downloaded == 0) {
            body.addView(text("Здесь появятся готовые серии. Их можно смотреть без POCO.",
                    12, MUTED, false), spaced(0, 7));
        } else {
            int count = 0;
            for (int i = 0; i < files.length() && count < 35; ++i) {
                JSONObject item = files.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!DownloadQueue.DONE.equals(DownloadQueue.status(this, id))) continue;
                LinearLayout row = horizontal();
                row.setPadding(dp(12), dp(5), dp(7), dp(5));
                row.setBackground(shape(PANEL, 0, 9));
                TextView name = text(readableName(item.optString("name", "Серия")), 12, TEXT, false);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(button("▶", false, () -> play(id)),
                        new LinearLayout.LayoutParams(dp(44), dp(42)));
                body.addView(row, spaced(0, 5));
                count++;
            }
            if (downloaded > 35) body.addView(link("Все скачанные — во вкладке Смотреть", () -> {
                page = 0;
                filter = 1;
                draw();
            }), spaced(6, 4));
        }
    }

    private String readableName(String raw) {
        String name = raw == null ? "Серия" : raw;
        if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                || name.endsWith(".webm")) name = name.substring(0, name.lastIndexOf('.'));
        return name.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private String time(int millis) {
        int seconds = Math.max(0, millis) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void optionsDialog() {
        final boolean autoNext = prefs().getBoolean("auto_next_episode_v7", true);
        final boolean vlc = prefs().getBoolean("prefer_vlc_v7", false);
        final String[] actions = {
                "Обновить подключение",
                "Изменить IP и код POCO",
                "Выбрать папку microSD",
                "Следующая серия автоматически · " + (autoNext ? "Вкл" : "Выкл"),
                "VLC по умолчанию · " + (vlc ? "Вкл" : "Выкл")
        };
        new AlertDialog.Builder(this).setTitle("Параметры просмотра")
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0: connect(); break;
                        case 1: pairDialog(); break;
                        case 2: pickSd(); break;
                        case 3:
                            prefs().edit().putBoolean("auto_next_episode_v7", !autoNext).apply();
                            toast("Автоследующая серия: " + (!autoNext ? "включена" : "выключена"));
                            break;
                        case 4:
                            prefs().edit().putBoolean("prefer_vlc_v7", !vlc).apply();
                            toast("VLC: " + (!vlc ? "по умолчанию" : "выключен"));
                            break;
                    }
                }).setNegativeButton("Закрыть", null).show();
    }

    private void pickSd() {
        Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        select.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(select, PICK_SD);
    }

    private void pairDialog() {
        LinearLayout form = vertical();
        form.setPadding(dp(22), dp(10), dp(22), dp(4));
        EditText host = new EditText(this);
        host.setSingleLine(true);
        host.setText(prefs().getString("remote_host", ""));
        host.setHint("IP телефона (можно оставить пустым)");
        host.setTextColor(TEXT);
        host.setHintTextColor(MUTED);
        host.setInputType(InputType.TYPE_CLASS_TEXT);
        form.addView(host);
        EditText code = new EditText(this);
        code.setSingleLine(true);
        code.setText(prefs().getString("remote_code", ""));
        code.setHint("Код POCO · 8 цифр");
        code.setTextColor(TEXT);
        code.setHintTextColor(MUTED);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(code);
        new AlertDialog.Builder(this).setTitle("Подключение к POCO").setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, which) -> {
                    String password = code.getText().toString().trim();
                    if (!password.matches("[0-9]{8}")) { toast("Нужен код из 8 цифр"); return; }
                    String ip = host.getText().toString().trim();
                    try { if (!ip.isEmpty()) ip = DownloadEngine.validatedHost(ip); }
                    catch (Exception ignored) { toast("Неверный IP телефона"); return; }
                    prefs().edit().putString("remote_host", ip).putString("remote_code", password).commit();
                    TransferService.send(this, TransferService.START_RECEIVER);
                    connect();
                }).show();
    }

    private void connect() {
        if (closed || connecting) return;
        if (!paired()) { pairDialog(); return; }
        connecting = true;
        lastConnectAttempt = SystemClock.elapsedRealtime();
        network.execute(() -> {
            String secret = prefs().getString("remote_code", "");
            String current = prefs().getString("remote_host", "");
            List<String> targets = new ArrayList<>();
            if (!current.isEmpty()) targets.add(current);
            for (String ip : LanDiscovery.find(this)) if (!targets.contains(ip)) targets.add(ip);
            JSONObject response = null;
            String found = "";
            for (String ip : targets) {
                try { response = new JSONObject(DownloadEngine.list(ip, secret)); found = ip; break; }
                catch (Exception ignored) { }
            }
            final JSONObject result = response;
            final String resolved = found;
            JSONArray remoteFiles = null;
            if (result != null) {
                remoteFiles = result.optJSONArray("files");
                if (remoteFiles == null) remoteFiles = new JSONArray();
                // SAF file checks can be slow on a 1 GB Huawei: NEVER run on the UI thread.
                String previous = prefs().getString("remote_host", "");
                try { DownloadQueue.reconcile(this, remoteFiles, previous, resolved); }
                catch (Exception ignored) { }
                prefs().edit().putString("remote_host", resolved)
                        .putString("remote_cache", remoteFiles.toString()).commit();
            }
            final JSONArray incoming = remoteFiles;
            handler.post(() -> {
                if (closed) return;
                connecting = false;
                if (result == null) {
                    boolean wasConnected = connected;
                    connected = false;
                    status = "Нет связи с POCO";
                    if (wasConnected) draw();
                    return;
                }
                boolean changed = !incoming.toString().equals(files.toString()) || !connected;
                files = incoming;
                ticket = result.optString("stream_ticket", "");
                connected = true;
                status = "Подключено · " + files.length();
                if (changed) draw();
                autoResumeQueue();
            });
        });
    }

    private void play(String id) {
        if (!id.matches("[0-9a-f]{24}")) { toast("Серия недоступна"); return; }
        if (!connected || !ticket.matches("[0-9a-f]{48}")) {
            if (DownloadQueue.DONE.equals(DownloadQueue.status(this, id))) {
                String folder = prefs().getString(MediaLibrary.DEST_TREE, "");
                if (!folder.isEmpty()) try {
                    Uri file = DownloadQueue.knownFile(this, id, Uri.parse(folder),
                            prefs().getString("remote_host", ""), prefs().getString("remote_host", ""));
                    if (file != null) {
                        Intent watch = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(file, "video/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(watch, "Открыть скачанную серию"));
                        return;
                    }
                } catch (Exception ignored) { }
            }
            toast("Нет связи с POCO · скачанные серии доступны офлайн");
            return;
        }
        startActivity(new Intent(this, RemotePlayerActivity.class)
                .putExtra("remote_action", "play")
                .putExtra("remote_id", id)
                .putExtra("remote_ticket", ticket));
    }

    private void queue(boolean missing) {
        if (!connected) { toast("Подключи POCO для загрузки"); return; }
        if (prefs().getString(MediaLibrary.DEST_TREE, "").isEmpty()) {
            page = 1;
            draw();
            toast("Сначала выбери папку microSD");
            return;
        }
        JSONArray chosen = new JSONArray();
        for (int i = 0; i < files.length(); ++i) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (DownloadQueue.DONE.equals(DownloadQueue.status(this, id))) continue;
            if (missing || selected.contains(id)) chosen.put(item);
        }
        int added = DownloadQueue.enqueue(this, chosen);
        selected.clear();
        toast(added > 0 ? "Добавлено в очередь: " + added : "Новых серий нет");
        page = 1;
        draw();
        autoResumeQueue();
    }

    private void autoResumeQueue() {
        if (!connected || DownloadQueue.paused(this) || TransferService.downloading) return;
        String folder = prefs().getString(MediaLibrary.DEST_TREE, "");
        if (folder.isEmpty()) return;
        JSONArray pending = DownloadQueue.availablePending(this, files);
        if (pending.length() == 0) return;
        TransferService.download(this, prefs().getString("remote_host", ""),
                prefs().getString("remote_code", ""), folder, pending);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_SD || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            String last = prefs().getString(MediaLibrary.DEST_TREE, "");
            if (!tree.toString().equals(last)) {
                prefs().edit().putString(MediaLibrary.DEST_TREE, tree.toString()).commit();
                DownloadQueue.destinationChanged(this);
            }
            draw();
            autoResumeQueue();
        } catch (Exception e) { toast("Нет доступа к microSD"); }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
}
