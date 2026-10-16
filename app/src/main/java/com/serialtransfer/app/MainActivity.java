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
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.RadialGradient;
import android.graphics.LinearGradient;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.RectF;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.widget.Switch;
import android.widget.FrameLayout;
import android.os.StatFs;
import android.os.Environment;
import android.text.TextUtils;
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
import android.widget.SeekBar;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Low-memory native interface, compatible with Android 7. Pair once and reconnect later. */
public final class MainActivity extends Activity {
    static volatile boolean uiVisible;
    private static final int PICK_FILES = 101;
    private static final int PICK_SOURCE_FOLDER = 102;
    private static final int PICK_SD_FOLDER = 103;
    private static final int BG = Color.rgb(5, 15, 23);
    private static final int PANEL = Color.rgb(14, 32, 44);
    private static final int DIVIDER = Color.rgb(28, 57, 72);
    private static final int ACCENT = Color.rgb(25, 204, 231);
    private static final int WHITE = Color.rgb(234, 245, 251);
    private static final int MUTED = Color.rgb(140, 166, 180);

    private LinearLayout content;
    private LinearLayout targetsPanel;
    private LinearLayout remoteLibraryPanel;
    private TextView remoteMessage;
    private LinearLayout resumeCard;
    private TextView resumeDetails;
    private Button resumeWatchButton;
    private String chosenDevice = "";
    private int pocoEpoch;
    private boolean pocoIsPlaying;
    private TextView pocoBigPlay;
    private TextView pocoRemoteName;
    private final int POCO_BG = Color.rgb(2, 13, 24);
    private final int POCO_SURFACE = Color.rgb(10, 31, 45);
    private final int POCO_EDGE = Color.rgb(27, 68, 86);
    private final int POCO_CYAN = Color.rgb(6, 221, 245);
    private final int POCO_LIGHT = Color.rgb(235, 249, 255);
    private final int POCO_MUTED = Color.rgb(132, 163, 184);
    private long nextDevicesAt;
    private volatile boolean refreshingDevices;
    private final ExecutorService actions = Executors.newSingleThreadExecutor();
    private TextView hostInfo;
    private TextView compactStatus;
    private TextView linkStatus;
    private TextView transferStatus;
    private Button downloadButton;
    private Button pauseButton;
    private SeekBar remoteSeek;
    private TextView remoteNow;
    private TextView remotePlaying;
    private long nextCatalogCheck;
    private boolean diagnosticsRunning;
    private final List<CheckBox> episodeChecks = new ArrayList<>();
    private final Set<String> chosen = new HashSet<>();
    private JSONArray remoteFiles = new JSONArray();
    private String streamTicket = "";
    // page 0: library, 1: connected screens, 2: downloads/files, 3: settings.
    private int page;
    private String openCollection = "";
    private int visibleItems = 60;
    private String deviceLabel = "";
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
            if (compactStatus != null) {
                boolean active = mode == 0 ? TransferService.serverRunning : linkConnected;
                compactStatus.setText(mode == 0
                        ? (active ? "●  Раздача" : "○  Нет раздачи")
                        : (active ? "●  Подключено" : "○  Не подключено"));
                compactStatus.setTextColor(active ? ACCENT : MUTED);
            }
            if (pauseButton != null) pauseButton.setVisibility(TransferService.downloading ? View.VISIBLE : View.GONE);
            if (mode == 0 && page == 0) updateResumeCard();
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
                    && !TransferService.downloading) {
                nextReconnectAt = now + (linkConnected ? 75000 : 18000);
                connectSaved(true); // refresh catalogue and recover even with an empty download queue
            }
            if (mode == 0 && TransferService.serverRunning && targetsPanel != null
                    && now >= nextDevicesAt && !refreshingDevices) {
                nextDevicesAt = now + 9000;
                refreshRemoteDevices();
            }
            handler.postDelayed(this, 900);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        SharedPreferences prefs = MediaLibrary.prefs(this);
        // POCO/controller build: keep existing package and stored sender library.
        mode = 0;
        prefs.edit().putInt("screen", 0).apply();
        page = 0;
        chosenDevice = prefs.getString("selected_remote_device", "");
        try { remoteFiles = new JSONArray(prefs.getString("remote_cache", "[]")); }
        catch (Exception ignored) { remoteFiles = new JSONArray(); }
        render();
        handler.post(ticker);
        if (mode == 0) startSenderIfRemembered();
        else {
            startReceiverIfPaired();
            handler.postDelayed(new Runnable() {
            @Override public void run() { connectSaved(true); }
        }, 450);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 77);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        uiVisible = true;
    }
    @Override protected void onPause() {
        uiVisible = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        disposed = true;
        handler.removeCallbacksAndMessages(null);
        actions.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        seenQueueRevision = TransferService.queueRevision;
        hostInfo = null;
        targetsPanel = null;
        remoteLibraryPanel = null;
        remoteMessage = null;
        resumeCard = null;
        resumeDetails = null;
        resumeWatchButton = null;
        linkStatus = null;
        transferStatus = null;
        downloadButton = null;
        pauseButton = null;
        compactStatus = null;
        remoteSeek = null;
        remoteNow = null;
        remotePlaying = null;
        episodeChecks.clear();
        if (mode == 0) { renderPoco(); return; }

        LinearLayout screen = column();
        screen.setBackgroundColor(BG);
        LinearLayout header = row();
        header.setPadding(dp(17), dp(15), dp(14), dp(12));
        TextView logo = text("▷", 29, ACCENT, true);
        header.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(44)));
        LinearLayout headerText = column();
        headerText.addView(text("Медиатека", 20, WHITE, true));
        headerText.addView(text(mode == 0 ? "Библиотека POCO" : "Библиотека Huawei", 11, MUTED, false));
        header.addView(headerText, new LinearLayout.LayoutParams(0, -2, 1));
        compactStatus = text("○  Соединение", 11, MUTED, true);
        compactStatus.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(compactStatus);
        compactStatus.setOnClickListener(v -> navigate(1));
        screen.addView(header);
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        screen.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout work = row();
        final boolean sidebar = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && getResources().getConfiguration().screenWidthDp >= 650;
        if (sidebar) {
            LinearLayout rail = column();
            rail.setPadding(dp(8), dp(12), dp(8), dp(8));
            rail.setBackgroundColor(Color.rgb(9, 23, 34));
            addSideNavigation(rail);
            work.addView(rail, new LinearLayout.LayoutParams(dp(152), -1));
            View border = new View(this);
            border.setBackgroundColor(DIVIDER);
            work.addView(border, new LinearLayout.LayoutParams(dp(1), -1));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(16), dp(11), dp(16), dp(24));
        scroll.addView(content);
        work.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
        screen.addView(work, new LinearLayout.LayoutParams(-1, 0, 1));

        if (mode == 0) showSender(); else showReceiver();
        if (mode == 1 && page == 0 && !openCollection.isEmpty() && remoteFiles.length() > 0) buildBottomBar(screen);
        if (!sidebar) addBottomNavigation(screen);
        setContentView(screen);
    }

    private void navigate(int next) {
        if (page == next && openCollection.isEmpty()) return;
        page = next;
        openCollection = "";
        visibleItems = 60;
        render();
    }

    private String[] pageNames() {
        return mode == 0
                ? new String[]{"Главная", "Устройства", "Файлы", "Настройки"}
                : new String[]{"Главная", "Телефон", "Загрузки", "Настройки"};
    }

    private String[] pageIcons() { return new String[]{"⌂", "▣", "⇩", "⚙"}; }

    private void addBottomNavigation(LinearLayout screen) {
        LinearLayout nav = row();
        nav.setBackgroundColor(Color.rgb(9, 23, 34));
        nav.setPadding(dp(6), dp(6), dp(6), dp(6));
        for (int i = 0; i < 4; i++) {
            final int target = i;
            LinearLayout entry = column();
            entry.setGravity(Gravity.CENTER);
            entry.setPadding(dp(2), dp(4), dp(2), dp(4));
            if (page == i) entry.setBackground(background(PANEL, 9));
            TextView mark = text(pageIcons()[i], 21, page == i ? ACCENT : MUTED, false);
            mark.setGravity(Gravity.CENTER);
            entry.addView(mark);
            TextView title = text(pageNames()[i], 10, page == i ? ACCENT : MUTED, page == i);
            title.setGravity(Gravity.CENTER);
            entry.addView(title);
            entry.setOnClickListener(v -> navigate(target));
            nav.addView(entry, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        screen.addView(nav);
    }

    private void addSideNavigation(LinearLayout nav) {
        for (int i = 0; i < 4; i++) {
            final int target = i;
            TextView label = text(pageIcons()[i] + "   " + pageNames()[i], 13,
                    page == i ? ACCENT : MUTED, page == i);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setPadding(dp(10), 0, dp(3), 0);
            if (page == i) label.setBackground(background(PANEL, 9));
            nav.addView(label, spaced(0, 7));
            label.getLayoutParams().height = dp(48);
            label.setOnClickListener(v -> navigate(target));
        }
    }

    private void pageTitle(String title, String subtitle) {
        content.addView(text(title, 22, WHITE, true), spaced(4, 3));
        if (!subtitle.isEmpty()) content.addView(text(subtitle, 12, MUTED, false), spaced(0, 16));
        else content.addView(new View(this), spaced(0, 10));
    }

    private LinearLayout slimPanel() {
        LinearLayout box = panel();
        box.setPadding(dp(13), dp(12), dp(13), dp(12));
        return box;
    }

    private void cardRow(String icon, String title, String subtitle, String trail,
                         View.OnClickListener listener) {
        LinearLayout box = slimPanel();
        LinearLayout line = row();
        TextView symbol = text(icon, 23, ACCENT, false);
        symbol.setGravity(Gravity.CENTER);
        line.addView(symbol, new LinearLayout.LayoutParams(dp(39), dp(46)));
        LinearLayout desc = column();
        TextView main = text(title, 14, WHITE, true);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.addView(main);
        if (!subtitle.isEmpty()) desc.addView(text(subtitle, 11, MUTED, false), spaced(3, 0));
        line.addView(desc, new LinearLayout.LayoutParams(0, -2, 1));
        TextView end = text(trail, 16, ACCENT, false);
        end.setGravity(Gravity.CENTER);
        line.addView(end);
        box.addView(line);
        if (listener != null) box.setOnClickListener(listener);
        content.addView(box, spaced(0, 8));
    }

    private void thinHeading(String title, String action, View.OnClickListener onClick) {
        LinearLayout line = row();
        line.addView(text(title, 15, WHITE, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null && !action.isEmpty()) {
            TextView link = text(action, 12, ACCENT, true);
            link.setPadding(dp(7), dp(8), dp(2), dp(8));
            link.setOnClickListener(onClick);
            line.addView(link);
        }
        content.addView(line, spaced(12, 8));
    }

    private void roleSettings() {
        thinHeading("Роль приложения", null, null);
        cardRow("▣", "POCO · сервер и пульт",
                "Для просмотра установи отдельное APK «Медиатека — Экран»", "✓", null);
    }

    private void sourceFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_SOURCE_FOLDER);
    }
    private void sourceFilesPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FILES);
    }
    private void destinationPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_SD_FOLDER);
    }

    /** Folder-like collections are inferred only from actual filenames, not invented metadata. */
    private String collectionName(String filename) {
        String raw = filename.replace('_', ' ').replace('.', ' ').trim();
        java.util.regex.Matcher pattern = java.util.regex.Pattern.compile(
                "(?i)\\b(?:[0-9]{1,2}\\s*сезон|сезон\\s*[0-9]{1,2}|s[0-9]{1,2}e[0-9]{1,3}|[0-9]{1,3}\\s*серия|серия\\s*[0-9]{1,3})\\b")
                .matcher(raw);
        if (pattern.find() && pattern.start() > 1) {
            return raw.substring(0, pattern.start()).replaceAll("[-\\s]+$", "").trim();
        }
        return "Другие видео";
    }

        private void chooseMode(int next) {
        if (mode == next) return;
        mode = next;
        page = 0;
        openCollection = "";
        linkConnected = false;
        streamTicket = "";
        MediaLibrary.prefs(this).edit().putInt("screen", mode).apply();
        render();
        if (mode == 0) {
            TransferService.send(this, TransferService.STOP_RECEIVER);
            startSenderIfRemembered();
        } else {
            startReceiverIfPaired();
            connectSaved(true);
        }
    }


    // POCO PREMIUM: native UI only.  The viewer module and the network protocol
    // remain unchanged. No poster decoding, WebView, or third-party UI libraries.
    private GradientDrawable pocoShape(int first, int second, int radius, int edge) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{first, second});
        drawable.setCornerRadius(dp(radius));
        if (edge != 0) drawable.setStroke(dp(1), edge);
        return drawable;
    }

    private void pocoSurface(View target, boolean emphasized, int radius) {
        target.setBackground(pocoShape(emphasized ? Color.rgb(9, 65, 78) : POCO_SURFACE,
                emphasized ? Color.rgb(5, 31, 48) : Color.rgb(6, 24, 38),
                radius, emphasized ? Color.rgb(13, 179, 205) : POCO_EDGE));
        target.setElevation(dp(emphasized ? 5 : 2));
    }

    private LinearLayout pocoCard(boolean emphasized) {
        LinearLayout result = column();
        result.setPadding(dp(14), dp(14), dp(14), dp(14));
        pocoSurface(result, emphasized, 16);
        return result;
    }

    private TextView pocoText(String value, int size, boolean bold, int color) {
        TextView label = text(value, size, color, bold);
        label.setIncludeFontPadding(false);
        return label;
    }

    private LinearLayout.LayoutParams pocoSpace(int top, int bottom) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = dp(top); l.bottomMargin = dp(bottom);
        return l;
    }

    private TextView pocoTap(String label, boolean bright, View.OnClickListener listener) {
        TextView view = pocoText(label, 13, true, bright ? POCO_BG : POCO_LIGHT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMinHeight(dp(44));
        GradientDrawable normal = pocoShape(bright ? Color.rgb(11, 215, 239) :
                        Color.rgb(19, 45, 62), bright ? Color.rgb(12, 173, 204) :
                        Color.rgb(10, 31, 46), 12,
                bright ? Color.rgb(72, 240, 251) : POCO_EDGE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(
                Color.argb(65, 230, 250, 255)), normal, null));
        view.setOnClickListener(listener);
        return view;
    }

    private TextView pocoTapCircle(String value, boolean bright, int size,
                                   View.OnClickListener listener) {
        TextView v = pocoText(value, bright ? 29 : 21, true, bright ? POCO_BG : POCO_LIGHT);
        v.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                bright ? new int[]{POCO_CYAN, Color.rgb(16, 142, 198)}
                        : new int[]{Color.rgb(23, 57, 75), Color.rgb(11, 33, 48)});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(1), bright ? Color.rgb(92, 250, 255) : POCO_EDGE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x558CEAFF), bg, null));
        v.setOnClickListener(listener);
        v.setElevation(dp(4));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return v;
    }

    private void pocoAddHeading(String label, String action, View.OnClickListener onClick) {
        LinearLayout line = row();
        TextView heading = pocoText(label, 17, true, POCO_LIGHT);
        line.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            TextView link = pocoText(action + "  ›", 12, true, POCO_CYAN);
            link.setPadding(dp(6), dp(9), dp(2), dp(9));
            link.setOnClickListener(onClick);
            line.addView(link);
        }
        content.addView(line, pocoSpace(19, 10));
    }

    private void pocoInfoRow(LinearLayout parent, String symbol, String label,
                             String description, String tail, View.OnClickListener callback) {
        LinearLayout body = row();
        body.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout iconBg = column();
        iconBg.setGravity(Gravity.CENTER);
        iconBg.setBackground(pocoShape(Color.rgb(13, 62, 81), Color.rgb(9, 37, 54), 10, POCO_EDGE));
        TextView icon = pocoText(symbol, 22, true, POCO_CYAN);
        icon.setGravity(Gravity.CENTER);
        iconBg.addView(icon);
        body.addView(iconBg, new LinearLayout.LayoutParams(dp(41), dp(43)));
        LinearLayout details = column();
        details.setPadding(dp(11), dp(1), dp(4), 0);
        TextView title = pocoText(label, 13, true, POCO_LIGHT);
        title.setSingleLine(true); title.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(title);
        if (description != null && !description.isEmpty()) {
            TextView second = pocoText(description, 11, false, POCO_MUTED);
            second.setSingleLine(true); second.setEllipsize(TextUtils.TruncateAt.END);
            details.addView(second, pocoSpace(5, 0));
        }
        body.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = pocoText(tail, 18, false, POCO_CYAN);
        arrow.setGravity(Gravity.CENTER);
        body.addView(arrow);
        if (callback != null) {
            body.setOnClickListener(callback);
            body.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3656CDDD),
                    pocoShape(Color.TRANSPARENT, Color.TRANSPARENT, 10, 0), null));
        }
        parent.addView(body);
    }

    private void pocoSeparator(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(22, 49, 65));
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void renderPoco() {
        pocoEpoch++;
        pocoBigPlay = null;
        pocoRemoteName = null;
        resumeCard = null; resumeDetails = null; resumeWatchButton = null;
        hostInfo = null; targetsPanel = null; remotePlaying = null;
        remoteNow = null; remoteSeek = null; compactStatus = null;
        LinearLayout screen = column();
        screen.setBackground(pocoShape(POCO_BG, Color.rgb(3, 17, 30), 0, 0));
        screen.setFitsSystemWindows(true);
        LinearLayout top = row();
        top.setPadding(dp(19), dp(14), dp(19), dp(13));
        TextView logo = pocoText("▷", 36, true, POCO_CYAN);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(logo, new LinearLayout.LayoutParams(dp(43), dp(48)));
        LinearLayout caption = column();
        caption.addView(pocoText("Медиатека", 21, true, POCO_LIGHT));
        caption.addView(pocoText("БИБЛИОТЕКА POCO", 10, true, POCO_MUTED), pocoSpace(4, 0));
        top.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        compactStatus = pocoText("●  Проверка", 11, true, POCO_MUTED);
        compactStatus.setGravity(Gravity.CENTER_VERTICAL);
        compactStatus.setOnClickListener(v -> navigate(3));
        top.addView(compactStatus);
        screen.addView(top);
        View headerEdge = new View(this);
        headerEdge.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x000FC5DF, 0xAA0FC5DF, 0x000FC5DF}));
        screen.addView(headerEdge, new LinearLayout.LayoutParams(-1, dp(1)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        content = column();
        content.setPadding(dp(16), dp(17), dp(16), dp(28));
        scroll.addView(content);
        screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (page == 0) pocoHome();
        else if (page == 1) pocoDevices();
        else if (page == 2) pocoRemote();
        else if (page == 3) pocoFiles();
        else pocoSettings();
        pocoBottomNav(screen);
        setContentView(screen);
        if (TransferService.serverRunning && targetsPanel != null) refreshRemoteDevices();
    }

    private void pocoBottomNav(LinearLayout root) {
        String[] names = {"Главная", "Устройства", "Пульт", "Файлы", "Настройки"};
        String[] icons = {"⌂", "▣", "◉", "▤", "⚙"};
        LinearLayout nav = row();
        nav.setPadding(dp(7), dp(7), dp(7), dp(5));
        nav.setBackground(pocoShape(0xFF081E2C, 0xFF041422, 0, 0));
        for (int i=0; i<names.length; i++) {
            final int target=i;
            LinearLayout tile=column();
            tile.setGravity(Gravity.CENTER);
            if(page==i) tile.setBackground(pocoShape(0xFF064257, 0xFF082B3D, 11, 0));
            TextView glyph=pocoText(icons[i], 22, page==i, page==i?POCO_CYAN:POCO_MUTED);
            glyph.setGravity(Gravity.CENTER);
            tile.addView(glyph);
            TextView text=pocoText(names[i], 9, page==i, page==i?POCO_CYAN:POCO_MUTED);
            text.setGravity(Gravity.CENTER);
            tile.addView(text, pocoSpace(4,0));
            tile.setOnClickListener(v -> navigate(target));
            nav.addView(tile, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        root.addView(nav);
    }

    private String pocoDeviceName() {
        if (chosenDevice.isEmpty()) return "Экран не выбран";
        return MediaLibrary.prefs(this).getString("selected_remote_name", "Подключённый экран");
    }

    private void pocoHome() {
        content.addView(pocoText("Продолжить просмотр", 19, true, POCO_LIGHT), pocoSpace(1, 11));
        resumeCard = pocoCard(true);
        resumeCard.addView(pocoText("ПОСЛЕДНИЙ ПРОСМОТР", 10, true, POCO_CYAN));
        resumeDetails = pocoText("Синхронизирую историю просмотра…", 13, false, POCO_LIGHT);
        resumeCard.addView(resumeDetails, pocoSpace(10, 8));
        resumeWatchButton = button("▶   Продолжить на экране", true);
        resumeWatchButton.setBackground(pocoShape(POCO_CYAN, Color.rgb(13, 162, 186), 12, 0));
        resumeCard.addView(resumeWatchButton, pocoSpace(5, 0));
        resumeWatchButton.setOnClickListener(v -> {
            if (chosenDevice.isEmpty()) { navigate(1); return; }
            try {
                JSONObject history = new JSONObject(MediaLibrary.prefs(this)
                        .getString("remote_watch_" + chosenDevice, ""));
                String id=history.optString("id", "");
                if (id.matches("[0-9a-f]{24}")) sendToRemote("play", id);
                else toast("Пока нет сохранённого просмотра");
            } catch (Exception e) { toast("Пока нет сохранённого просмотра"); }
        });
        content.addView(resumeCard, pocoSpace(0, 13));
        updateResumeCard();
        LinearLayout chosenCard=pocoCard(false);
        chosenCard.addView(pocoText("АКТИВНЫЙ ЭКРАН", 10, true, POCO_CYAN));
        pocoInfoRow(chosenCard, "▣", pocoDeviceName(),
                chosenDevice.isEmpty()?"Выбери устройство для просмотра":"Пульт и воспроизведение",
                "›", v -> navigate(1));
        content.addView(chosenCard, pocoSpace(0, 6));
        pocoAddHeading("Быстрый доступ", null, null);
        LinearLayout shortcuts=row();
        String[] labels={"Сериалы","Устройства","Файлы"};
        String[] glyphs={"▤","▣","⇩"};
        int[] pages={3,1,3};
        for(int i=0;i<3;i++) {
            final int destination=pages[i];
            LinearLayout tile=pocoCard(false);
            tile.setGravity(Gravity.CENTER);
            TextView g=pocoText(glyphs[i], 25, true, POCO_CYAN); g.setGravity(Gravity.CENTER);
            tile.addView(g);
            tile.addView(pocoText(labels[i], 11, true, POCO_LIGHT), pocoSpace(9, 0));
            tile.setOnClickListener(v -> navigate(destination));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0, dp(90), 1);
            if (i>0) lp.leftMargin=dp(7);
            shortcuts.addView(tile, lp);
        }
        content.addView(shortcuts);
        pocoAddHeading("Мои сериалы", "Все", v -> navigate(3));
        LinearLayout preview=pocoCard(false);
        preview.addView(pocoText("Загружаю библиотеку…", 12, false, POCO_MUTED));
        content.addView(preview);
        pocoLoadCollections(preview, 5, true, pocoEpoch);
        TextView add=pocoTap("＋   Добавить сериалы", false, v -> sourceFolderPicker());
        content.addView(add, pocoSpace(12, 0));
    }

    private void pocoDevices() {
        pocoPageIntro("Устройства", "Подключай экраны и управляй просмотром");
        LinearLayout tip=pocoCard(true);
        tip.addView(pocoText("◉   ВЫБРАННЫЙ ЭКРАН", 11, true, POCO_CYAN));
        tip.addView(pocoText(pocoDeviceName(), 19, true, POCO_LIGHT), pocoSpace(11, 4));
        tip.addView(pocoText(chosenDevice.isEmpty()?"Выбери экран из списка ниже":
                "Управление и запуск серий доступны на вкладке «Пульт»",
                12, false, POCO_MUTED));
        if (!chosenDevice.isEmpty()) {
            tip.addView(pocoTap("▶   Открыть пульт", true, v -> navigate(2)), pocoSpace(13, 0));
        }
        content.addView(tip, pocoSpace(0, 12));
        pocoAddHeading("Доступные устройства", "Обновить", v -> refreshRemoteDevices());
        targetsPanel=column();
        targetsPanel.addView(pocoText("Поиск экранов в локальной сети…", 13, false, POCO_MUTED));
        content.addView(targetsPanel);
        LinearLayout hint=pocoCard(false);
        hint.addView(pocoText("КАК ПОДКЛЮЧИТЬ", 10, true, POCO_CYAN));
        hint.addView(pocoText("Установи «Медиатека — Экран» на Huawei или ТВ. " +
                "Один раз подключи его к POCO по коду из раздела «Файлы». " +
                "Когда экран появится в списке, выбери его.", 12, false, POCO_MUTED), pocoSpace(8,0));
        content.addView(hint, pocoSpace(16,0));
        content.addView(pocoTap("Показать код подключения", false, v -> navigate(3)), pocoSpace(10,0));
    }

    private void pocoRemote() {
        pocoPageIntro("Пульт", "Дистанционное воспроизведение");
        targetsPanel=column(); // refresh state without needing a second network listener
        LinearLayout selected=pocoCard(false);
        selected.addView(pocoText("УСТРОЙСТВО ВОСПРОИЗВЕДЕНИЯ", 10, true, POCO_CYAN));
        LinearLayout sr=row();
        pocoRemoteName=pocoText(pocoDeviceName(), 16, true, POCO_LIGHT);
        sr.addView(pocoRemoteName,new LinearLayout.LayoutParams(0, -2, 1));
        sr.addView(pocoTap("Изменить  ›", false, v -> navigate(1)),
                new LinearLayout.LayoutParams(dp(119), dp(44)));
        selected.addView(sr,pocoSpace(10,0));
        content.addView(selected);
        LinearLayout playback=pocoCard(true);
        FrameLayout hero=new FrameLayout(this);
        hero.addView(new PocoWave(this));
        LinearLayout title=column();
        title.setGravity(Gravity.BOTTOM);
        title.setPadding(dp(13),dp(38),dp(13),dp(18));
        title.addView(pocoText("СЕЙЧАС ВОСПРОИЗВОДИТСЯ", 10, true, POCO_CYAN));
        remotePlaying=pocoText("Ожидание запуска серии", 18, true, POCO_LIGHT);
        remotePlaying.setMaxLines(2); remotePlaying.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(remotePlaying,pocoSpace(9,0));
        hero.addView(title,new FrameLayout.LayoutParams(-1,-1));
        playback.addView(hero,new LinearLayout.LayoutParams(-1,dp(140)));
        remoteNow=pocoText("00:00 / 00:00", 12, false, POCO_MUTED);
        remoteNow.setGravity(Gravity.RIGHT);
        playback.addView(remoteNow,pocoSpace(10,4));
        remoteSeek=new SeekBar(this);
        remoteSeek.setMax(1000);
        remoteSeek.setProgressTintList(ColorStateList.valueOf(POCO_CYAN));
        remoteSeek.setThumbTintList(ColorStateList.valueOf(POCO_CYAN));
        remoteSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean byUser) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int duration=MediaLibrary.prefs(MainActivity.this)
                        .getInt("remote_duration_v7_"+chosenDevice,0);
                if(duration>0) seekRemote((int)((long)bar.getProgress()*duration/1000L));
            }
        });
        playback.addView(remoteSeek);
        LinearLayout controls=row(); controls.setGravity(Gravity.CENTER);
        controls.addView(pocoTapCircle("↶",false,60,v -> sendToRemote("seek_back","")));
        pocoBigPlay=pocoTapCircle("▶",true,85,v -> {
            sendToRemote(pocoIsPlaying?"pause":"resume","");
        });
        LinearLayout.LayoutParams center=new LinearLayout.LayoutParams(dp(85),dp(85));
        center.leftMargin=dp(21); center.rightMargin=dp(21);
        controls.addView(pocoBigPlay,center);
        controls.addView(pocoTapCircle("↷",false,60,v -> sendToRemote("seek_forward","")));
        playback.addView(controls,pocoSpace(8,12));
        LinearLayout skip=row();
        TextView prev=pocoTap("|◀  Предыдущая",false,v -> sendToRemote("previous",""));
        TextView next=pocoTap("Следующая  ▶|",false,v -> sendToRemote("next",""));
        skip.addView(prev,new LinearLayout.LayoutParams(0,dp(46),1));
        LinearLayout.LayoutParams nlp=new LinearLayout.LayoutParams(0,dp(46),1);
        nlp.leftMargin=dp(8); skip.addView(next,nlp);
        playback.addView(skip);
        content.addView(playback,pocoSpace(13,0));
        pocoAddHeading("Управление",null,null);
        LinearLayout volume=pocoCard(false);
        volume.addView(pocoText("ЗВУК НА УСТРОЙСТВЕ",10,true,POCO_MUTED));
        LinearLayout volRow=row();
        volRow.addView(pocoTap("−  Тише",false,v -> sendToRemote("volume_down","")),
                new LinearLayout.LayoutParams(0,dp(45),1));
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(45),1); rp.leftMargin=dp(8);
        volRow.addView(pocoTap("＋  Громче",false,v -> sendToRemote("volume_up","")),rp);
        volume.addView(volRow,pocoSpace(10,0));
        content.addView(volume);
        content.addView(pocoTap("▤   Выбрать серию",true,v -> navigate(0)),pocoSpace(13,0));
        content.addView(pocoTap("■   Остановить просмотр",false,v -> sendToRemote("stop","")),pocoSpace(8,0));
    }

    private void pocoFiles() {
        if (!openCollection.isEmpty()) {
            pocoPageIntro(openCollection,"Серии · нажми ▶ для запуска на выбранном экране");
            content.addView(pocoTap("‹   Ко всем сериалам",false,v -> {openCollection="";render();}),pocoSpace(0,10));
            LinearLayout rows=pocoCard(false);
            rows.addView(pocoText("Обновляю список…",12,false,POCO_MUTED));
            content.addView(rows);
            pocoLoadCollections(rows, 600, false, pocoEpoch);
            return;
        }
        pocoPageIntro("Файлы", "Библиотека на телефоне");
        LinearLayout server=pocoCard(true);
        server.addView(pocoText("СЕРВЕР МЕДИАТЕКИ",10,true,POCO_CYAN));
        hostInfo=pocoText("Проверяю локальную сеть…",13,true,POCO_LIGHT);
        server.addView(hostInfo,pocoSpace(9,7));
        LinearLayout actions=row();
        actions.addView(pocoTap("▶  Включить",true,v -> {
            MediaLibrary.prefs(this).edit().putBoolean("auto_share",true).apply();
            TransferService.send(this,TransferService.START_SERVER);
        }),new LinearLayout.LayoutParams(0,dp(43),1));
        LinearLayout.LayoutParams off=new LinearLayout.LayoutParams(0,dp(43),1);
        off.leftMargin=dp(8);
        actions.addView(pocoTap("■  Выключить",false,v -> {
            MediaLibrary.prefs(this).edit().putBoolean("auto_share",false).apply();
            TransferService.send(this,TransferService.STOP_SERVER);
        }),off);
        server.addView(actions,pocoSpace(5,0));
        content.addView(server);
        content.addView(pocoTap("▱   Выбрать папку с сериалами",true,v -> sourceFolderPicker()),pocoSpace(13,7));
        content.addView(pocoTap("＋   Добавить отдельные видео",false,v -> sourceFilesPicker()));
        pocoStorage();
        pocoAddHeading("Библиотека", "Обновить", v -> render());
        LinearLayout collection=pocoCard(false);
        collection.addView(pocoText("Читаю список…",12,false,POCO_MUTED));
        content.addView(collection);
        pocoLoadCollections(collection, 600, true, pocoEpoch);
        TextView clear=pocoText("Очистить список библиотеки",12,false,POCO_MUTED);
        clear.setPadding(dp(9),dp(14),dp(9),dp(14));
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Убрать файлы из библиотеки?")
                .setMessage("Исходные фильмы на POCO не удаляются.")
                .setNegativeButton("Отмена",null)
                .setPositiveButton("Убрать",(dialog,which) -> {
                    MediaLibrary.clearSources(this);
                    TransferService.send(this,TransferService.STOP_SERVER);
                    render();
                }).show());
        content.addView(clear,pocoSpace(5,0));
    }

    private void pocoStorage() {
        try {
            StatFs stats=new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total=stats.getTotalBytes(); long free=stats.getAvailableBytes();
            LinearLayout storage=pocoCard(false);
            storage.addView(pocoText("ПАМЯТЬ ТЕЛЕФОНА",10,true,POCO_CYAN));
            storage.addView(pocoText(MediaLibrary.readableSize(free)+" свободно из "+
                    MediaLibrary.readableSize(total),12,false,POCO_MUTED),pocoSpace(8,8));
            View track=new View(this);
            GradientDrawable fill=pocoShape(Color.rgb(5,184,202),Color.rgb(8,86,123),6,0);
            track.setBackground(fill);
            LinearLayout line=row();
            line.setBackground(pocoShape(0xFF13293A,0xFF13293A,5,0));
            LinearLayout.LayoutParams width=new LinearLayout.LayoutParams(0,dp(5),
                    Math.max(0.01f,(float)(total-free)/Math.max(total,1)));
            line.addView(track,width);
            line.addView(new View(this),new LinearLayout.LayoutParams(0,dp(5),
                    Math.max(0.01f,(float)free/Math.max(total,1))));
            storage.addView(line);
            content.addView(storage,pocoSpace(13,0));
        } catch (Exception ignored) { }
    }

    private void pocoSettings() {
        pocoPageIntro("Настройки", "Подключение · Воспроизведение · Библиотека");
        pocoAddHeading("Подключение",null,null);
        LinearLayout net=pocoCard(false);
        pocoSwitch(net,"Раздавать при запуске","Если в библиотеке есть фильмы",
                "auto_share",true);
        pocoSeparator(net);
        pocoInfoRow(net,"◎","Данные подключения","Показать IP и код раздачи","›",v -> navigate(3));
        content.addView(net);
        pocoAddHeading("Воспроизведение",null,null);
        LinearLayout playback=pocoCard(false);
        playback.addView(pocoText("УПРАВЛЕНИЕ НА POCO", 10, true, POCO_CYAN));
        playback.addView(pocoText("Запуск серии, пауза, перемотка, звук и выбор экрана "
                + "доступны в разделе «Пульт». Настройки самого плеера (автоследующая "
                + "серия, VLC и сохранение громкости) пока задаются на устройстве «Экран».",
                12, false, POCO_MUTED), pocoSpace(9, 0));
        playback.addView(pocoTap("◉   Открыть пульт", false, v -> navigate(2)),
                pocoSpace(11, 0));
        content.addView(playback);
        pocoAddHeading("Файлы и библиотека",null,null);
        LinearLayout files=pocoCard(false);
        pocoInfoRow(files,"▱","Папка с сериалами","Выбрать другую папку","›",v -> sourceFolderPicker());
        pocoSeparator(files);
        pocoInfoRow(files,"◌","Обновить библиотеку","Перечитать доступные серии","›",v -> {
            navigate(3);
        });
        content.addView(files);
        pocoAddHeading("Диагностика",null,null);
        LinearLayout tools=pocoCard(false);
        pocoInfoRow(tools,"⌁","Проверка локальной сети","Поиск устройств и диагностика Wi-Fi","›",
                v -> diagnoseNetwork());
        pocoSeparator(tools);
        pocoInfoRow(tools,"▤","История просмотра","Продолжить просмотр — на Главной","›",v -> navigate(0));
        content.addView(tools);
        content.addView(pocoText("Медиатека · POCO · Нативный Android",11,false,POCO_MUTED),
                pocoSpace(24,0));
    }

    private void pocoSwitch(LinearLayout parent,String name,String subtitle,
                             String preference,boolean defaultValue) {
        LinearLayout line=row(); line.setPadding(dp(9),dp(12),dp(6),dp(12));
        LinearLayout labels=column();
        labels.addView(pocoText(name,13,true,POCO_LIGHT));
        labels.addView(pocoText(subtitle,11,false,POCO_MUTED),pocoSpace(4,0));
        line.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);
        toggle.setShowText(false);
        toggle.setThumbTintList(ColorStateList.valueOf(POCO_CYAN));
        toggle.setTrackTintList(ColorStateList.valueOf(0xFF236578));
        toggle.setChecked(MediaLibrary.prefs(this).getBoolean(preference,defaultValue));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            MediaLibrary.prefs(this).edit().putBoolean(preference,enabled).apply();
            if ("auto_share".equals(preference)) {
                TransferService.send(this,enabled ? TransferService.START_SERVER : TransferService.STOP_SERVER);
            }
        });
        line.addView(toggle);
        parent.addView(line);
    }

    private void pocoPageIntro(String title,String subtitle) {
        content.addView(pocoText(title,25,true,POCO_LIGHT),pocoSpace(3,6));
        content.addView(pocoText(subtitle,12,false,POCO_MUTED),pocoSpace(0,16));
    }

    private void pocoLoadCollections(final LinearLayout destination,final int limit,
                                     final boolean groupsOnly,final int version) {
        new Thread(() -> {
            final List<MediaLibrary.Film> all=MediaLibrary.scan(this);
            runOnUiThread(() -> {
                if(disposed || version!=pocoEpoch) return;
                destination.removeAllViews();
                if(all.isEmpty()) {
                    destination.addView(pocoText("В библиотеке пока нет серий. Выбери папку с видео.",
                            13,false,POCO_MUTED));
                    return;
                }
                if(groupsOnly || openCollection.isEmpty()) {
                    java.util.LinkedHashMap<String,Integer> groups=new java.util.LinkedHashMap<>();
                    for(MediaLibrary.Film item:all) {
                        String key=collectionName(item.name);
                        groups.put(key,groups.containsKey(key)?groups.get(key)+1:1);
                    }
                    int shown=0;
                    for(java.util.Map.Entry<String,Integer> group:groups.entrySet()) {
                        if(shown++>=limit) break;
                        final String key=group.getKey();
                        if(shown>1) pocoSeparator(destination);
                        pocoInfoRow(destination,"▱",key,group.getValue()+" серий",
                                "›",v -> {page=3;openCollection=key;render();});
                    }
                    if(groups.size()>limit) destination.addView(pocoTap("Показать все сериалы",false,
                            v -> navigate(3)),pocoSpace(9,0));
                } else {
                    int shown=0;
                    for(MediaLibrary.Film episode:all) {
                        if(!openCollection.equals(collectionName(episode.name))) continue;
                        if(shown++>=limit) break;
                        if(shown>1) pocoSeparator(destination);
                        LinearLayout row=row();row.setPadding(dp(8),dp(10),dp(4),dp(10));
                        LinearLayout details=column();
                        TextView name=pocoText(cleanFilename(episode.name),13,true,POCO_LIGHT);
                        name.setMaxLines(2);
                        details.addView(name);
                        String detail=MediaLibrary.readableSize(episode.size);
                        if(!chosenDevice.isEmpty()) {
                            try {
                                JSONObject watch=new JSONObject(MediaLibrary.prefs(this)
                                        .getString("remote_watch_"+chosenDevice+"_"+episode.id,""));
                                if(watch.optBoolean("finished")) detail+=" · Просмотрено ✓";
                                else if(watch.optInt("position")>5000) detail+=" · "+
                                        clock(watch.optInt("position"));
                            } catch(Exception ignored) {}
                        }
                        details.addView(pocoText(detail,11,false,POCO_MUTED),pocoSpace(5,0));
                        row.addView(details,new LinearLayout.LayoutParams(0,-2,1));
                        TextView play=pocoTap("▶",true,v -> sendToRemote("play",episode.id));
                        row.addView(play,new LinearLayout.LayoutParams(dp(48),dp(44)));
                        destination.addView(row);
                    }
                    if(shown>limit) destination.addView(pocoTap("Показать ещё",false,v -> {
                        pocoLoadCollections(destination,limit+80,false,pocoEpoch);
                    }),pocoSpace(9,0));
                }
            });
        },"PocoLibraryUi").start();
    }

    /** Precomputed vector-light waves; no bitmap allocation or continuous animation. */
    private static final class PocoWave extends View {
        private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
        PocoWave(Context context) {super(context);}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w=getWidth(),h=getHeight();
            if(w<=0||h<=0)return;
            brush.setShader(new LinearGradient(0,0,w,h,
                    new int[]{0xFF071E30,0xFF0C3D57,0xFF051522},null,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0,0,w,h),15,15,brush);
            brush.setShader(null);
            brush.setStyle(Paint.Style.STROKE);
            for(int strand=0;strand<5;strand++) {
                android.graphics.Path path=new android.graphics.Path();
                float shift=strand*7f;
                path.moveTo(-10,h*.62f+shift);
                path.cubicTo(w*.27f,h*.09f+shift,w*.47f,h*.95f+shift,w+.5f,h*.26f+shift);
                brush.setColor(strand==0?0xAA21EBF6:0x2236BBDD);
                brush.setStrokeWidth(strand==0?2.2f:1.1f);
                canvas.drawPath(path,brush);
            }
            brush.setStyle(Paint.Style.FILL);
        }
    }

    private void showSender() {
        if (page == 1) {
            pageTitle("Устройства", "Выбери экран для удалённого просмотра");
            targetsPanel = slimPanel();
            targetsPanel.addView(text("Ищу подключённые устройства…", 12, MUTED, false));
            content.addView(targetsPanel, spaced(0, 8));
            action("Обновить устройства", false, v -> refreshRemoteDevices());
            thinHeading("Пульт управления", "", null);
            if (chosenDevice.isEmpty()) {
                cardRow("▣", "Устройство не выбрано", "Открой Медиатеку на Huawei или приставке", "", null);
            } else {
                cardRow("▣", "Экран выбран", "Команды отправляются на него", "✓", null);
                rowActions("◀◀", "▶▶", v -> sendToRemote("previous", ""), v -> sendToRemote("next", ""));
                rowActions("−30 сек", "+30 сек", v -> sendToRemote("seek_back", ""), v -> sendToRemote("seek_forward", ""));
                rowActions("Пауза", "▶ Продолжить", v -> sendToRemote("pause", ""), v -> sendToRemote("resume", ""));
                // Current playback state is received every few seconds from the receiver.
                LinearLayout playback = slimPanel();
                remotePlaying = text("Ожидание данных плеера…", 13, WHITE, true);
                playback.addView(remotePlaying);
                remoteNow = text("", 12, MUTED, false);
                playback.addView(remoteNow, spaced(6, 2));
                remoteSeek = new SeekBar(this);
                remoteSeek.setMax(1000);
                remoteSeek.setProgressTintList(ColorStateList.valueOf(ACCENT));
                remoteSeek.setThumbTintList(ColorStateList.valueOf(ACCENT));
                remoteSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    boolean dragging;
                    @Override public void onStartTrackingTouch(SeekBar seek) { dragging = true; }
                    @Override public void onProgressChanged(SeekBar seek, int value, boolean fromUser) { }
                    @Override public void onStopTrackingTouch(SeekBar seek) {
                        dragging = false;
                        int duration = MediaLibrary.prefs(MainActivity.this)
                                .getInt("remote_duration_v7_" + chosenDevice, 0);
                        if (duration > 0) seekRemote((int)((long)seek.getProgress() * duration / 1000L));
                    }
                });
                playback.addView(remoteSeek);
                content.addView(playback, spaced(9, 8));
                rowActions("− Громкость", "+ Громкость", v -> sendToRemote("volume_down", ""), v -> sendToRemote("volume_up", ""));
                link("■  Остановить воспроизведение", v -> sendToRemote("stop", ""));
            }
            refreshRemoteDevices();
        } else if (page == 2) {
            pageTitle("Файлы на телефоне", "Управление папками для раздачи");
            LinearLayout status = slimPanel();
            hostInfo = text("Проверяю сервер…", 13, ACCENT, true);
            status.addView(hostInfo);
            content.addView(status, spaced(0, 12));
            action("+  Выбрать папку с сериалами", true, v -> sourceFolderPicker());
            action("Добавить отдельные видео", false, v -> sourceFilesPicker());
            thinHeading("Библиотека", null, null);
            LinearLayout catalogStatus = slimPanel();
            TextView count = text("Загружаю список…", 14, WHITE, true);
            TextView preview = text("", 12, MUTED, false);
            catalogStatus.addView(count); catalogStatus.addView(preview, spaced(6, 0));
            content.addView(catalogStatus, spaced(0, 10));
            scanLocal(count, preview);
            link("Очистить библиотеку", v -> new AlertDialog.Builder(this)
                    .setTitle("Убрать файлы из библиотеки?")
                    .setMessage("Сами видео на POCO останутся.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Убрать", (d, w) -> {
                        MediaLibrary.clearSources(this);
                        TransferService.send(this, TransferService.STOP_SERVER);
                        render();
                    }).show());
        } else if (page == 3) {
            pageTitle("Настройки", "Подключение, передача и устройство");
            roleSettings();
            thinHeading("Раздача по Wi-Fi", null, null);
            LinearLayout box = slimPanel();
            hostInfo = text("Проверяю адрес…", 13, ACCENT, true);
            box.addView(hostInfo);
            content.addView(box, spaced(0, 8));
            rowActions("Включить", "Выключить", v -> {
                MediaLibrary.prefs(this).edit().putBoolean("auto_share", true).apply();
                TransferService.send(this, TransferService.START_SERVER);
            }, v -> {
                MediaLibrary.prefs(this).edit().putBoolean("auto_share", false).apply();
                TransferService.send(this, TransferService.STOP_SERVER);
            });
            note("IP и код вводятся один раз. В домашней сети устройства ищутся автоматически.");
            settingsSwitch("Автопереход к следующей серии", "auto_next_episode_v7", true);
            settingsSwitch("Запоминать громкость плеера", "remember_volume_v7", false);
            settingsSwitch("Открывать во внешнем плеере (без удалённой перемотки и истории)",
                    "prefer_vlc_v7", false);
            action("Диагностика Wi-Fi", false, v -> diagnoseNetwork());
            cardRow("▤", "Папки и файлы", "Добавить или убрать видео", "›", v -> navigate(2));
        } else {
            pageTitle(openCollection.isEmpty() ? "Мои сериалы" : openCollection,
                    openCollection.isEmpty() ? "Выбери видео для воспроизведения" : "Нажми ▶ для запуска на выбранном экране");
            cardRow("▣", chosenDevice.isEmpty() ? "Выбрать экран" : "Экран выбран",
                    chosenDevice.isEmpty() ? "Huawei, приставка или магнитола" : "Управление доступно на вкладке Устройства",
                    "›", v -> navigate(1));
            resumeCard = slimPanel();
            resumeCard.addView(text("Продолжить просмотр", 15, WHITE, true));
            resumeDetails = text("", 12, MUTED, false);
            resumeCard.addView(resumeDetails, spaced(7, 9));
            resumeWatchButton = button("▶  Продолжить на устройстве", true);
            resumeCard.addView(resumeWatchButton, spaced(6, 0));
            resumeWatchButton.setOnClickListener(v -> {
                try {
                    JSONObject watch = new JSONObject(MediaLibrary.prefs(this)
                            .getString("remote_watch_" + chosenDevice, ""));
                    String episode = watch.optString("id", "");
                    if (!episode.matches("[0-9a-f]{24}")) { toast("История просмотра не найдена"); return; }
                    sendToRemote("play", episode);
                } catch (Exception ignored) { toast("История просмотра не найдена"); }
            });
            content.addView(resumeCard, spaced(0, 10));
            updateResumeCard();
            remoteLibraryPanel = column();
            remoteLibraryPanel.addView(text("Загружаю библиотеку…", 12, MUTED, false));
            content.addView(remoteLibraryPanel, spaced(8, 10));
            scanSenderEpisodes();
            action("+  Добавить сериалы", false, v -> navigate(2));
        }
    }

        private void startReceiverIfPaired() {
        SharedPreferences prefs = MediaLibrary.prefs(this);
        if (!prefs.getString("remote_host", "").isEmpty()
                && prefs.getString("remote_code", "").matches("[0-9]{8}")) {
            TransferService.send(this, TransferService.START_RECEIVER);
        }
    }

    private static String clock(int ms) {
        long seconds = Math.max(0, ms) / 1000L;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void seekRemote(final int positionMs) {
        final String target = chosenDevice;
        if (target.isEmpty()) return;
        actions.execute(() -> {
            try { RemoteControlClient.seek(TransferService.accessCode, target, positionMs); }
            catch (Exception e) { runOnUiThread(() -> { if (!disposed) toast("Перемотка: " + e.getMessage()); }); }
        });
    }

    private void updateRemoteProgress(JSONObject state) {
        String id = state.optString("player_id", "");
        int current = state.optInt("position", 0);
        int duration = state.optInt("duration", 0);
        boolean playing = state.optBoolean("playing", false);
        MediaLibrary.prefs(this).edit().putInt("remote_duration_v7_" + chosenDevice, duration).apply();
        pocoIsPlaying = playing;
        if (pocoBigPlay != null) pocoBigPlay.setText(playing ? "Ⅱ" : "▶");
        if (remotePlaying != null) {
            String name = "Нет активного просмотра";
            if (id.matches("[0-9a-f]{24}")) {
                name = id;
                name = state.optString("player_name", "Серия воспроизводится");
            }
            remotePlaying.setText((playing ? "▶ " : "Ⅱ ") + name);
        }
        if (remoteNow != null) remoteNow.setText(clock(current) + " / " + clock(duration));
        if (remoteSeek != null && duration > 0 && !remoteSeek.isPressed())
            remoteSeek.setProgress((int)Math.min(1000, ((long)current * 1000L / duration)));
    }

    private void refreshRemoteDevices() {
        if (refreshingDevices || !TransferService.serverRunning || targetsPanel == null) return;
        refreshingDevices = true;
        new Thread(() -> {
            JSONArray devices = new JSONArray();
            try { devices = RemoteControlClient.devices(TransferService.accessCode); }
            catch (Exception ignored) { }
            final JSONArray found = devices;
            runOnUiThread(() -> {
                refreshingDevices = false;
                if (disposed || mode != 0 || targetsPanel == null) return;
                targetsPanel.removeAllViews();
                if (found.length() == 0) {
                    targetsPanel.addView(text("Пока нет подключённых экранов. Открой Медиатеку на Huawei и подключи его к POCO.",
                            12, MUTED, false));
                    return;
                }
                for (int i = 0; i < found.length(); i++) {
                    JSONObject device = found.optJSONObject(i);
                    if (device == null) continue;
                    String id = device.optString("id", "");
                    String label = device.optString("name", "Android");
                    boolean active = chosenDevice.equals(id);
                    if (active) updateRemoteProgress(device);
                    if (chosenDevice.isEmpty() && found.length() == 1) {
                        chosenDevice = id;
                        MediaLibrary.prefs(this).edit()
                                .putString("selected_remote_device", id)
                                .putString("selected_remote_name", label).apply();
                    }
                    LinearLayout device = pocoCard(active);
                    LinearLayout line = row();
                    TextView glyph = pocoText(label.toLowerCase(Locale.ROOT).contains("huawei")
                            || label.toLowerCase(Locale.ROOT).contains("tab") ? "▯" : "▣",
                            26, true, POCO_CYAN);
                    glyph.setGravity(Gravity.CENTER);
                    line.addView(glyph, new LinearLayout.LayoutParams(dp(43), dp(51)));
                    LinearLayout title = column();
                    TextView name = pocoText(label, 15, true, POCO_LIGHT);
                    name.setSingleLine(true); name.setEllipsize(TextUtils.TruncateAt.END);
                    title.addView(name);
                    title.addView(pocoText("● Онлайн · " + (active ? "Выбран" : "Доступен"),
                            11, false, POCO_CYAN), pocoSpace(6, 0));
                    line.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
                    device.addView(line);
                    device.addView(pocoTap(active ? "✓  Активный экран  ·  Пульт" : "▶  Выбрать экран",
                            active, v -> {
                                if (active) { navigate(2); return; }
                                chosenDevice = id;
                                MediaLibrary.prefs(this).edit()
                                        .putString("selected_remote_device", id)
                                        .putString("selected_remote_name", label).apply();
                                nextDevicesAt = 0;
                                render();
                            }), pocoSpace(9, 0));
                    targetsPanel.addView(device, pocoSpace(0, 9));
                }
            });
        }, "MediaDevices").start();
    }

        private void scanSenderEpisodes() {
        final LinearLayout destination = remoteLibraryPanel;
        final String currentCollection = openCollection;
        new Thread(() -> {
            final List<MediaLibrary.Film> available = MediaLibrary.scan(this);
            runOnUiThread(() -> {
                if (disposed || mode != 0 || page != 0 || destination != remoteLibraryPanel) return;
                destination.removeAllViews();
                if (available.isEmpty()) {
                    destination.addView(text("Пока нет серий. Добавь папку с POCO во вкладке «Файлы».",
                            13, MUTED, false));
                    return;
                }
                if (currentCollection.isEmpty()) {
                    java.util.Map<String, Integer> groups = new java.util.LinkedHashMap<>();
                    for (MediaLibrary.Film f : available) {
                        String group = collectionName(f.name);
                        groups.put(group, groups.containsKey(group) ? groups.get(group) + 1 : 1);
                    }
                    for (java.util.Map.Entry<String, Integer> pair : groups.entrySet()) {
                        String title = pair.getKey();
                        LinearLayout section = slimPanel();
                        LinearLayout info = row();
                        TextView icon = text("▱", 22, ACCENT, false);
                        info.addView(icon, new LinearLayout.LayoutParams(dp(38), -2));
                        LinearLayout labels = column();
                        labels.addView(text(title, 14, WHITE, true));
                        labels.addView(text(pair.getValue() + " видео", 11, MUTED, false), spaced(3, 0));
                        info.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                        info.addView(text("›", 22, ACCENT, false));
                        section.addView(info);
                        section.setOnClickListener(v -> { openCollection = title; visibleItems = 60; render(); });
                        destination.addView(section, spaced(0, 8));
                    }
                } else {
                    TextView back = text("‹   Все сериалы", 13, ACCENT, true);
                    back.setPadding(dp(3), dp(10), dp(3), dp(12));
                    back.setOnClickListener(v -> { openCollection = ""; render(); });
                    destination.addView(back);
                    int displayed = 0;
                    for (MediaLibrary.Film f : available) {
                        if (!currentCollection.equals(collectionName(f.name))) continue;
                        if (displayed++ >= visibleItems) continue;
                        LinearLayout line = slimPanel();
                        LinearLayout inner = row();
                        LinearLayout info = column();
                        TextView name = text(cleanFilename(f.name), 13, WHITE, true);
                        name.setMaxLines(2);
                        info.addView(name);
                        String detail = MediaLibrary.readableSize(f.size);
                        if (!chosenDevice.isEmpty()) {
                            String history = MediaLibrary.prefs(this).getString(
                                    "remote_watch_" + chosenDevice + "_" + f.id, "");
                            try {
                                JSONObject item = new JSONObject(history);
                                if (item.optBoolean("finished")) detail += "  ·  ✓ Просмотрено";
                                else if (item.optInt("position") > 5000)
                                    detail += "  ·  Продолжить с " + clock(item.optInt("position"));
                            } catch (Exception ignored) { }
                        }
                        info.addView(text(detail, 11, MUTED, false), spaced(3, 0));
                        inner.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
                        Button play = button("▶", true);
                        inner.addView(play, new LinearLayout.LayoutParams(dp(47), dp(44)));
                        play.setOnClickListener(v -> sendToRemote("play", f.id));
                        line.addView(inner);
                        destination.addView(line, spaced(0, 6));
                    }
                    if (displayed > visibleItems) {
                        Button more = button("Показать ещё", false);
                        destination.addView(more, spaced(8, 8));
                        more.setOnClickListener(v -> { visibleItems += 60; render(); });
                    }
                }
            });
        }, "LightCatalog").start();
    }

    private String cleanFilename(String filename) {
        return filename.replaceAll("(?i)\\.(mp4|mkv|avi|webm|mov|m4v|ts|mpg|mpeg|3gp)$", "")
                .replace('_', ' ').trim();
    }

        private void sendToRemote(final String action, final String fileId) {
        final String target = chosenDevice;
        final String code = TransferService.accessCode;
        if (target.isEmpty()) { toast("Сначала выбери устройство для просмотра"); return; }
        if (!TransferService.serverRunning) { toast("Сначала включи раздачу на POCO"); return; }
        actions.execute(new Runnable() {
            @Override public void run() {
                String message;
                try {
                    RemoteControlClient.send(code, target, action, fileId);
                    message = "Команда отправлена";
                } catch (Exception e) {
                    message = "Не удалось связаться с экраном: " + e.getMessage();
                }
                final String result = message;
                runOnUiThread(() -> { if (!disposed) toast(result); });
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
        boolean paired = !prefs.getString("remote_host", "").isEmpty()
                && prefs.getString("remote_code", "").matches("[0-9]{8}");
        String tree = prefs.getString(MediaLibrary.DEST_TREE, "");
        if (page == 1) {
            pageTitle("Телефон", "Соединение сохраняется после первого подключения");
            LinearLayout connection = slimPanel();
            linkStatus = text(paired ? statusMessage : "Телефон не подключён", 15,
                    paired ? ACCENT : MUTED, true);
            connection.addView(linkStatus);
            if (paired) connection.addView(text("IP: " + prefs.getString("remote_host", ""),
                    12, MUTED, false), spaced(7, 0));
            content.addView(connection, spaced(0, 10));
            action(paired ? "Обновить подключение" : "Подключить POCO", true,
                    v -> { if (paired) connectSaved(false); else showPairingDialog(); });
            action("Изменить IP и код", false, v -> showPairingDialog());
            note("POCO и Huawei должны находиться в одной локальной Wi-Fi-сети.");
        } else if (page == 2) {
            pageTitle("Загрузки", "Сохранённые серии и очередь");
            int done = 0;
            for (int i = 0; i < remoteFiles.length(); i++) {
                JSONObject e = remoteFiles.optJSONObject(i);
                if (e != null && DownloadQueue.DONE.equals(DownloadQueue.status(this, e.optString("id")))) done++;
            }
            cardRow("✓", "Скачано: " + done, "Видео сохраняются на microSD", "", null);
            cardRow("⇩", "В очереди: " + DownloadQueue.waiting(this),
                    DownloadQueue.paused(this) ? "Приостановлено" : TransferService.progressText, "", null);
            thinHeading("Папка загрузок", null, null);
            cardRow("▱", tree.isEmpty() ? "Выбрать microSD" : "Папка выбрана",
                    tree.isEmpty() ? "Нужна для сохранения серий" : "Нажми, чтобы изменить", "›", v -> destinationPicker());
            action("Скачать все недостающие", true, v -> downloadAllMissing());
            if (DownloadQueue.paused(this)) {
                action("Продолжить очередь", false, v -> {
                    DownloadQueue.setPaused(this, false);
                    startQueueDownload(false);
                    render();
                });
            }
            if (TransferService.downloading) action("Пауза", false, v -> {
                TransferService.send(this, TransferService.PAUSE);
                render();
            });
        } else if (page == 3) {
            pageTitle("Настройки", "Ничего лишнего на главном экране");
            roleSettings();
            thinHeading("Подключение", null, null);
            cardRow("▣", paired ? "POCO сохранён" : "POCO не настроен",
                    "IP и код соединения", "›", v -> showPairingDialog());
            thinHeading("Хранилище", null, null);
            cardRow("▱", tree.isEmpty() ? "microSD не выбрана" : "microSD подключена",
                    "Выбрать папку для фильмов", "›", v -> destinationPicker());
            settingsSwitch("Автопереход к следующей серии", "auto_next_episode_v7", true);
            settingsSwitch("Запоминать громкость плеера", "remember_volume_v7", false);
            settingsSwitch("Открывать во внешнем плеере (без удалённой перемотки и истории)",
                    "prefer_vlc_v7", false);
            action("Диагностика Wi-Fi", false, v -> diagnoseNetwork());
            note("Очередь загрузок и уже скачанные серии сохраняются. Автопоиск работает в домашней сети и точке доступа, когда маршрутизатор разрешает обмен между устройствами.");
        } else {
            pageTitle(openCollection.isEmpty() ? "Мои сериалы" : openCollection,
                    openCollection.isEmpty() ? "Смотреть или скачать с POCO" : "Выбери нужную серию");
            cardRow("▣", paired ? (linkConnected ? "POCO подключён" : "POCO — соединение сохранено") : "Подключить POCO",
                    paired ? "Открыть управление соединением" : "Один раз введи IP и код", "›", v -> navigate(1));
            JSONObject latest = WatchHistory.last(this);
            if (latest != null && !latest.optBoolean("finished")
                    && WatchHistory.resumeAt(this, latest.optString("id")) > 0) {
                final String resumeId = latest.optString("id");
                cardRow("▶", "Продолжить просмотр", latest.optString("name", "Серия")
                        + " · " + clock(latest.optInt("position")), "›", v -> openStream(resumeId));
            }
            if (remoteFiles.length() == 0) {
                cardRow("▱", "Список пока пуст", "Подключись к телефону и обнови серии", "", null);
                action(paired ? "Обновить сериалы" : "Подключить телефон", true,
                        v -> { if (paired) connectSaved(false); else showPairingDialog(); });
                return;
            }
            final Set<String> queued = DownloadQueue.queuedIds(this);
            if (openCollection.isEmpty()) {
                java.util.Map<String,Integer> groups = new java.util.LinkedHashMap<>();
                for (int i = 0; i < remoteFiles.length(); i++) {
                    JSONObject file = remoteFiles.optJSONObject(i);
                    if (file == null) continue;
                    String name = collectionName(file.optString("name"));
                    groups.put(name, groups.containsKey(name) ? groups.get(name) + 1 : 1);
                }
                thinHeading(remoteFiles.length() + " видео", "Обновить", v -> connectSaved(false));
                for (java.util.Map.Entry<String,Integer> entry : groups.entrySet()) {
                    cardRow("▱", entry.getKey(), entry.getValue() + " видео", "›",
                            v -> { openCollection = entry.getKey(); visibleItems = 60; render(); });
                }
            } else {
                TextView back = text("‹   Все сериалы", 13, ACCENT, true);
                back.setPadding(dp(4), dp(9), dp(4), dp(12));
                back.setOnClickListener(v -> { openCollection = ""; render(); });
                content.addView(back);
                thinHeading("Серии", "Выбрать все", v -> setAll(true));
                int visible = 0;
                for (int i = 0; i < remoteFiles.length(); i++) {
                    JSONObject episode = remoteFiles.optJSONObject(i);
                    if (episode == null || !openCollection.equals(collectionName(episode.optString("name")))) continue;
                    if (visible++ >= visibleItems) continue;
                    final String id = episode.optString("id");
                    String state = DownloadQueue.status(this, id);
                    boolean waiting = queued.contains(id);
                    boolean downloaded = DownloadQueue.DONE.equals(state);
                    if (downloaded) chosen.remove(id);
                    String stateLabel = downloaded ? "✓ Скачано" : waiting ? "◷ В очереди" :
                            DownloadQueue.PARTIAL.equals(state) ? "↻ Продолжить" :
                                    MediaLibrary.readableSize(episode.optLong("size", -1));
                    JSONObject watched = WatchHistory.item(this, id);
                    if (watched != null) {
                        if (watched.optBoolean("finished")) stateLabel += "  ·  ✓ Просмотрено";
                        else if (watched.optInt("position") >= 5000)
                            stateLabel += "  ·  " + clock(watched.optInt("position"));
                    }
                    LinearLayout card = slimPanel();
                    LinearLayout line = row();
                    CheckBox check = new CheckBox(this);
                    check.setTag(id);
                    check.setButtonTintList(ColorStateList.valueOf(ACCENT));
                    check.setChecked(chosen.contains(id));
                    check.setEnabled(!downloaded && !waiting);
                    check.setOnCheckedChangeListener((b, selected) -> {
                        if (selected) chosen.add(id); else chosen.remove(id);
                        updateDownloadButton();
                    });
                    line.addView(check, new LinearLayout.LayoutParams(dp(38), dp(43)));
                    LinearLayout texts = column();
                    TextView name = text(cleanFilename(episode.optString("name", "Серия")), 13, WHITE, true);
                    name.setMaxLines(2);
                    texts.addView(name);
                    texts.addView(text(stateLabel, 11, downloaded ? ACCENT : MUTED, false), spaced(4, 0));
                    line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
                    Button play = button("▶", false);
                    play.setTextColor(ACCENT);
                    line.addView(play, new LinearLayout.LayoutParams(dp(47), dp(43)));
                    play.setOnClickListener(v -> openStream(id));
                    card.addView(line);
                    content.addView(card, spaced(0, 6));
                    episodeChecks.add(check);
                }
                if (visible > visibleItems) {
                    action("Показать ещё", false, v -> { visibleItems += 60; render(); });
                }
                link("Снять выбор", v -> setAll(false));
                cardRow("⇩", "Скачать все недостающие", "Сохранить на карту microSD", "›",
                        v -> downloadAllMissing());
            }
        }
    }

        private void buildBottomBar(LinearLayout screen) {
        LinearLayout bar = column();
        bar.setBackgroundColor(Color.rgb(10, 25, 35));
        bar.setPadding(dp(13), dp(7), dp(13), dp(7));
        transferStatus = text(TransferService.progressText, 11, MUTED, false);
        transferStatus.setMaxLines(1);
        transferStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(transferStatus, spaced(0, 4));
        LinearLayout controls = row();
        downloadButton = button("Скачать выбранные", true);
        controls.addView(downloadButton, new LinearLayout.LayoutParams(0, dp(45), 1));
        downloadButton.setOnClickListener(v -> beginDownload());
        pauseButton = button("Ⅱ", false);
        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(dp(49), dp(45));
        pauseParams.leftMargin = dp(7);
        controls.addView(pauseButton, pauseParams);
        pauseButton.setVisibility(TransferService.downloading ? View.VISIBLE : View.GONE);
        pauseButton.setOnClickListener(v -> TransferService.send(this, TransferService.PAUSE));
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

    private void updateResumeCard() {
        if (resumeCard == null || resumeDetails == null || resumeWatchButton == null) return;
        try {
            JSONObject item = new JSONObject(MediaLibrary.prefs(this)
                    .getString("remote_watch_" + chosenDevice, ""));
            String id = item.optString("id", "");
            int position = item.optInt("position", 0);
            int duration = item.optInt("duration", 0);
            boolean ready = id.matches("[0-9a-f]{24}") && position >= 5000
                    && !item.optBoolean("finished")
                    && (duration <= 0 || duration - position >= 15000);
            resumeCard.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (!ready) return;
            String name = item.optString("name", "Серия");
            int seconds = Math.max(0, position / 1000);
            String stamp = String.format(Locale.US, "%02d:%02d:%02d",
                    seconds / 3600, (seconds / 60) % 60, seconds % 60);
            resumeDetails.setText(name + "\nСохранено на " + stamp + " · можно продолжить после перезапуска");
        } catch (Exception ignored) { resumeCard.setVisibility(View.GONE); }
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

    private void settingsSwitch(String title, String key, boolean defaultValue) {
        CheckBox check = new CheckBox(this);
        check.setText(title);
        check.setTextSize(13);
        check.setTextColor(WHITE);
        check.setButtonTintList(ColorStateList.valueOf(ACCENT));
        check.setChecked(MediaLibrary.prefs(this).getBoolean(key, defaultValue));
        check.setOnCheckedChangeListener((v, enabled) -> MediaLibrary.prefs(this)
                .edit().putBoolean(key, enabled).apply());
        content.addView(check, spaced(5, 5));
    }

    private void diagnoseNetwork() {
        if (diagnosticsRunning) return;
        diagnosticsRunning = true;
        Toast.makeText(this, "Проверяю Wi-Fi…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            SharedPreferences p = MediaLibrary.prefs(this);
            String storedHost = p.getString("remote_host", "");
            String code = p.getString("remote_code", "");
            java.util.List<String> found = LanDiscovery.find(this);
            boolean storedWorks = false;
            if (!storedHost.isEmpty() && code.matches("[0-9]{8}")) {
                try { DownloadEngine.list(storedHost, code); storedWorks = true; }
                catch (Exception ignored) { }
            }
            final String message = "IP POCO (если сервер включён): " + TransferService.serverIp
                    + "\nАдрес сохранён: " + (storedHost.isEmpty() ? "нет" : storedHost)
                    + "\nСохранённый адрес: " + (storedWorks ? "доступен" : "не отвечает или неверный код")
                    + "\nПоиск в сети: " + (found.isEmpty() ? "ничего не найдено" : found.toString())
                    + "\n\nЕсли поиск пуст, проверь гостевую сеть, изоляцию клиентов и что оба устройства в одной сети Wi-Fi.";
            runOnUiThread(() -> {
                diagnosticsRunning = false;
                if (!disposed) new AlertDialog.Builder(this)
                        .setTitle("Диагностика подключения").setMessage(message)
                        .setPositiveButton("Понятно", null).show();
            });
        }, "MediatekaNetworkCheck").start();
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
                    startReceiverIfPaired();
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
                // Home routers have a different gateway: use UDP discovery, then verify
                // each candidate with the previously paired secret before trusting it.
                if (result == null) {
                    for (String candidate : LanDiscovery.find(MainActivity.this)) {
                        try {
                            JSONObject listing = new JSONObject(DownloadEngine.list(candidate, key));
                            result = listing.getJSONArray("files");
                            ticket = listing.optString("stream_ticket", "");
                            connectedHost = candidate;
                            failure = null;
                            break;
                        } catch (Exception rejected) { failure = rejected; }
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
            if (compactStatus != null) {
                boolean active = mode == 0 ? TransferService.serverRunning : linkConnected;
                compactStatus.setText(active ? "●  Подключено" : "○  Не подключено");
                compactStatus.setTextColor(active ? ACCENT : MUTED);
            }
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

    /** The internal player owns resume bookmarks. It may open VLC on request. */
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
            Intent intent = new Intent(this, RemotePlayerActivity.class);
            intent.putExtra("remote_action", "play");
            intent.putExtra("remote_id", episodeId);
            intent.putExtra("remote_ticket", streamTicket);
            startActivity(intent);
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
