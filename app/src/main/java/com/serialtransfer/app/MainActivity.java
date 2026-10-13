package com.serialtransfer.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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

/** Pure Android Views: fast on Huawei MediaPad T3 7 (Android 7, 1 GB RAM). */
public final class MainActivity extends Activity {
    private static final int PICK_FILES = 101;
    private static final int PICK_SOURCE_FOLDER = 102;
    private static final int PICK_SD_FOLDER = 103;
    private static final int BG = Color.rgb(15, 21, 29);
    private static final int PANEL = Color.rgb(27, 37, 48);
    private static final int ACCENT = Color.rgb(104, 206, 185);
    private static final int WHITE = Color.rgb(243, 245, 248);
    private static final int MUTED = Color.rgb(156, 172, 185);

    private LinearLayout content;
    private TextView liveStatus;
    private TextView liveServer;
    private TextView liveDownload;
    private EditText ipField;
    private EditText codeField;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> chosen = new HashSet<>();
    private JSONArray remoteFiles = new JSONArray();
    private List<MediaLibrary.Film> localFiles = new ArrayList<>();
    private int mode = 0;
    private boolean checking = false;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (liveStatus != null) liveStatus.setText(TransferService.progressText);
            if (liveServer != null) {
                liveServer.setText(TransferService.serverRunning
                        ? "АДРЕС: " + TransferService.serverIp + ":" + HttpFileServer.PORT
                          + "\nКОД: " + TransferService.accessCode
                        : "Раздача выключена");
            }
            if (liveDownload != null) {
                String detail = TransferService.progressText;
                long total = TransferService.progressTotal;
                if (TransferService.downloading && total > 0)
                    detail += "\n" + MediaLibrary.readableSize(TransferService.progressBytes)
                            + " / " + MediaLibrary.readableSize(total);
                liveDownload.setText(detail);
            }
            handler.postDelayed(this, 850);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        mode = MediaLibrary.prefs(this).getInt("screen", 0);
        render();
        handler.post(ticker);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 77);
        }
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        super.onDestroy();
    }

    private void render() {
        liveStatus = null;
        liveServer = null;
        liveDownload = null;
        ipField = null;
        codeField = null;
        LinearLayout screen = column();
        screen.setBackgroundColor(BG);

        TextView title = label("МЕДИАТЕКА", 24, WHITE, true);
        title.setPadding(dp(18), dp(25), dp(18), dp(2));
        screen.addView(title);
        TextView tagline = label("Сериалы без облака · локальный Wi-Fi", 13, MUTED, false);
        tagline.setPadding(dp(18), 0, dp(18), dp(15));
        screen.addView(tagline);

        LinearLayout tabs = row();
        tabs.setPadding(dp(12), dp(2), dp(12), dp(12));
        Button sender = button("ТЕЛЕФОН", mode == 0);
        Button receiver = button("ПЛАНШЕТ", mode == 1);
        tabs.addView(sender, rowWeight());
        tabs.addView(receiver, rowWeight());
        sender.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { chooseMode(0); }
        });
        receiver.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { chooseMode(1); }
        });
        screen.addView(tabs);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(16), dp(4), dp(16), dp(34));
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(screen);
        if (mode == 0) showSender(); else showReceiver();
    }

    private void chooseMode(int next) {
        if (mode == next) return;
        saveReceiverInputs();
        mode = next;
        MediaLibrary.prefs(this).edit().putInt("screen", mode).apply();
        render();
    }

    private void showSender() {
        cardTitle("1 · Выбери серии", "Можно добавить целую папку с сезонами или отдельные видеофайлы.");
        action("Выбрать папку с сериалами", true, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(picker, PICK_SOURCE_FOLDER);
            }
        });
        action("Добавить несколько файлов", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                picker.setType("*/*");
                picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(picker, PICK_FILES);
            }
        });
        action("Очистить библиотеку", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                MediaLibrary.clearSources(MainActivity.this);
                localFiles.clear();
                toast("Список очищен. Перезапусти раздачу для обновления.");
                render();
            }
        });

        final TextView libraryCount = label("Подсчёт серий...", 14, MUTED, false);
        margin(libraryCount, 10, 5);
        content.addView(libraryCount);
        final TextView librarySample = label("", 13, MUTED, false);
        margin(librarySample, 3, 10);
        content.addView(librarySample);
        scanLocal(libraryCount, librarySample);

        separator();
        cardTitle("2 · Разреши доступ по Wi-Fi",
                "Подключи Huawei к точке доступа POCO. Затем включи раздачу.");
        action("Включить / обновить раздачу", true, new View.OnClickListener() {
            @Override public void onClick(View view) {
                TransferService.progressText = "Запускаю локальный сервер...";
                TransferService.send(MainActivity.this, TransferService.START_SERVER);
            }
        });
        action("Остановить раздачу", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                TransferService.send(MainActivity.this, TransferService.STOP_SERVER);
            }
        });
        liveServer = label("Раздача выключена", 19, ACCENT, true);
        liveServer.setPadding(dp(12), dp(14), dp(12), dp(14));
        liveServer.setBackground(background(PANEL, 12));
        margin(liveServer, 9, 8);
        content.addView(liveServer);
        note("На Huawei введи IP и 8-значный код, которые появятся здесь. Оба устройства должны быть в одной локальной Wi-Fi сети.");
        liveStatus = label("", 13, MUTED, false);
        margin(liveStatus, 10, 8);
        content.addView(liveStatus);
    }

    private void scanLocal(final TextView count, final TextView sample) {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<MediaLibrary.Film> files = MediaLibrary.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        localFiles = files;
                        count.setText("Найдено видео: " + files.size() + (files.size() >= MediaLibrary.LIMIT ? " (лимит 600)" : ""));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < Math.min(files.size(), 8); i++) {
                            sb.append("• ").append(files.get(i).name).append("\n");
                        }
                        sample.setText(sb.length() == 0 ? "Добавь хотя бы одну серию." : sb.toString().trim());
                    }
                });
            }
        }, "ScanLocalVideos").start();
    }

    private void showReceiver() {
        cardTitle("1 · Подключись к телефону", "Оба устройства должны быть в одной сети Wi-Fi. На телефоне нажми «Включить раздачу». ");
        SharedPreferences prefs = MediaLibrary.prefs(this);
        ipField = field("IP телефона · например 192.168.43.1",
                prefs.getString("remote_host", ""), false);
        codeField = field("8-значный код с телефона", prefs.getString("remote_code", ""), true);
        action("Подключиться · показать серии", true, new View.OnClickListener() {
            @Override public void onClick(View view) { connect(); }
        });
        liveStatus = label("", 13, MUTED, false);
        margin(liveStatus, 9, 8);
        content.addView(liveStatus);

        separator();
        cardTitle("2 · Папка на microSD", "Укажи папку на карте памяти один раз. Серии будут записываться сразу туда.");
        action("Выбрать папку для скачивания", false, new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(picker, PICK_SD_FOLDER);
            }
        });
        note(prefs.getString(MediaLibrary.DEST_TREE, "").isEmpty()
                ? "Папка ещё не выбрана." : "Папка для загрузок выбрана ✓");

        separator();
        cardTitle("3 · Выбери и скачай серии", "Можно отметить несколько серий. Они скачиваются по очереди.");
        if (remoteFiles.length() > 0) {
            LinearLayout actions = row();
            Button all = button("Все", false);
            Button none = button("Снять выбор", false);
            actions.addView(all, rowWeight());
            actions.addView(none, rowWeight());
            content.addView(actions);
            all.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { setAll(true); }
            });
            none.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { setAll(false); }
            });
            for (int i = 0; i < remoteFiles.length(); i++) {
                JSONObject movie = remoteFiles.optJSONObject(i);
                if (movie == null) continue;
                final String id = movie.optString("id");
                CheckBox check = new CheckBox(this);
                check.setText(movie.optString("name", "Серия") + "\n" + MediaLibrary.readableSize(movie.optLong("size", -1)));
                check.setTextColor(WHITE);
                check.setTextSize(14);
                check.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
                check.setPadding(dp(6), dp(9), dp(2), dp(9));
                check.setTag(id);
                check.setChecked(chosen.contains(id));
                check.setOnCheckedChangeListener((buttonView, checked) -> {
                    if (checked) chosen.add(id); else chosen.remove(id);
                });
                content.addView(check);
            }
        } else {
            note("Подключись к телефону, чтобы увидеть доступные серии.");
        }
        action("Скачать выбранные на microSD", true, new View.OnClickListener() {
            @Override public void onClick(View view) { beginDownload(); }
        });
        action("Пауза", false, new View.OnClickListener() {
            @Override public void onClick(View view) { TransferService.send(MainActivity.this, TransferService.PAUSE); }
        });
        liveDownload = label("", 14, ACCENT, false);
        margin(liveDownload, 10, 8);
        content.addView(liveDownload);
        note("Чтобы продолжить недокачанную серию после обрыва, подключись снова и нажми «Скачать выбранные». Уже завершённые серии приложение пропустит.");
    }

    private void setAll(boolean selected) {
        chosen.clear();
        if (selected) for (int i = 0; i < remoteFiles.length(); i++) {
            JSONObject film = remoteFiles.optJSONObject(i);
            if (film != null) chosen.add(film.optString("id"));
        }
        // Only the checkboxes need updating; avoid rebuilding the whole screen.
        for (int i = 0; i < content.getChildCount(); i++) {
            View view = content.getChildAt(i);
            if (view instanceof CheckBox) ((CheckBox) view).setChecked(selected);
        }
    }

    private void connect() {
        saveReceiverInputs();
        final String host = ipField.getText().toString();
        final String code = codeField.getText().toString().trim();
        try { DownloadEngine.validatedHost(host); }
        catch (Exception e) { toast(e.getMessage()); return; }
        if (code.length() != 8 || !code.matches("[0-9]{8}")) {
            toast("Введи 8 цифр кода с телефона");
            return;
        }
        if (checking) return;
        checking = true;
        liveStatus.setText("Подключаюсь к телефону...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String response = DownloadEngine.list(host, code);
                    final JSONArray results = new JSONObject(response).getJSONArray("files");
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            checking = false;
                            remoteFiles = results;
                            chosen.clear();
                            showConnected();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            checking = false;
                            if (liveStatus != null) liveStatus.setText("Ошибка: " + e.getMessage());
                        }
                    });
                }
            }
        }, "FetchEpisodeList").start();
    }

    private void showConnected() {
        render();
        toast("Подключено. Доступно серий: " + remoteFiles.length());
    }

    private void beginDownload() {
        saveReceiverInputs();
        String root = MediaLibrary.prefs(this).getString(MediaLibrary.DEST_TREE, "");
        if (root.isEmpty()) { toast("Сначала выбери папку на microSD"); return; }
        if (chosen.isEmpty()) { toast("Отметь серии для скачивания"); return; }
        if (TransferService.downloading) { toast("Скачивание уже идёт"); return; }
        JSONArray selected = new JSONArray();
        for (int i = 0; i < remoteFiles.length(); i++) {
            JSONObject item = remoteFiles.optJSONObject(i);
            if (item != null && chosen.contains(item.optString("id"))) selected.put(item);
        }
        String host;
        try { host = DownloadEngine.validatedHost(ipField.getText().toString()); }
        catch (Exception e) { toast(e.getMessage()); return; }
        String code = codeField.getText().toString().trim();
        if (!code.matches("[0-9]{8}")) { toast("Проверь код подключения"); return; }
        Intent intent = new Intent(this, TransferService.class);
        intent.putExtra(TransferService.CMD, TransferService.DOWNLOAD);
        intent.putExtra("host", host);
        intent.putExtra("code", code);
        intent.putExtra("destination", root);
        intent.putExtra("items", selected.toString());
        TransferService.progressText = "Очередь: " + selected.length() + " серий";
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        toast("Начата передача на microSD");
    }

    private void saveReceiverInputs() {
        if (ipField == null || codeField == null) return;
        MediaLibrary.prefs(this).edit().putString("remote_host", ipField.getText().toString().trim())
                .putString("remote_code", codeField.getText().toString().trim()).apply();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == PICK_SOURCE_FOLDER || requestCode == PICK_SD_FOLDER) {
                Uri uri = data.getData();
                if (uri == null) return;
                int desired = requestCode == PICK_SD_FOLDER
                        ? (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        : Intent.FLAG_GRANT_READ_URI_PERMISSION;
                int granted = data.getFlags() & desired;
                getContentResolver().takePersistableUriPermission(uri, granted);
                String key = requestCode == PICK_SD_FOLDER ? MediaLibrary.DEST_TREE : MediaLibrary.SOURCE_TREE;
                MediaLibrary.prefs(this).edit().putString(key, uri.toString()).apply();
                if (requestCode == PICK_SOURCE_FOLDER) toast("Папка добавлена. Нажми «Включить / обновить раздачу».");
                else toast("Место сохранения выбрано");
            } else if (requestCode == PICK_FILES) {
                List<Uri> uris = new ArrayList<>();
                ClipData clips = data.getClipData();
                if (clips != null) for (int i = 0; i < clips.getItemCount(); ++i)
                    uris.add(clips.getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
                for (Uri uri : uris) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (Exception ignored) { }
                }
                MediaLibrary.addFiles(this, uris);
                toast("Добавлено файлов: " + uris.size());
            }
            render();
        } catch (Exception e) {
            toast("Не удалось получить доступ к папке: " + e.getMessage());
        }
    }

    private int dp(int n) { return (int) (getResources().getDisplayMetrics().density * n + 0.5f); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams rowWeight() { return new LinearLayout.LayoutParams(0, dp(49), 1f); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(s);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private Button button(String title, boolean prominent) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(prominent ? BG : WHITE);
        b.setBackground(background(prominent ? ACCENT : PANEL, 10));
        b.setPadding(dp(8), dp(7), dp(8), dp(7));
        return b;
    }
    private void action(String title, boolean prominent, View.OnClickListener click) {
        Button b = button(title, prominent);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(49));
        params.bottomMargin = dp(8);
        content.addView(b, params);
        b.setOnClickListener(click);
    }
    private void margin(View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        params.bottomMargin = dp(bottom);
        view.setLayoutParams(params);
    }
    private void cardTitle(String title, String hint) {
        TextView head = label(title, 17, WHITE, true);
        margin(head, 12, 7);
        content.addView(head);
        note(hint);
    }
    private void note(String message) {
        TextView view = label(message, 13, MUTED, false);
        margin(view, 0, 14);
        content.addView(view);
    }
    private void separator() {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(46, 58, 70));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(14);
        params.bottomMargin = dp(6);
        content.addView(line, params);
    }
    private EditText field(String hint, String value, boolean numeric) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setTextSize(15);
        if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setPadding(dp(13), dp(10), dp(13), dp(10));
        input.setBackground(background(PANEL, 9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(49));
        params.bottomMargin = dp(9);
        content.addView(input, params);
        return input;
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
