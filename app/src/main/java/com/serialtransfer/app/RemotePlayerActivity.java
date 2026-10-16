package com.serialtransfer.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** Minimal player with controls addressable from POCO. Falls back to VLC for unsupported codecs. */
public final class RemotePlayerActivity extends Activity {
    static final String ACTION_PLAYER = "com.serialtransfer.app.REMOTE_PLAYER";
    static volatile boolean visible;

    private VideoView video;
    private TextView heading;
    private Button pauseButton;
    private String currentId = "";
    private String currentUrl = "";
    private String host = "";
    private String ticket = "";
    private boolean playing;
    private BroadcastReceiver receiver;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        build();
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        heading = new TextView(this);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(16);
        heading.setPadding(18, 12, 18, 10);
        heading.setText("МЕДИАТЕКА · Удалённый просмотр");
        root.addView(heading);
        video = new VideoView(this);
        root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        video.setOnPreparedListener(mp -> {
            mp.setScreenOnWhilePlaying(true);
            video.start();
            playing = true;
            setPauseText();
        });
        video.setOnCompletionListener(mp -> step(1));
        video.setOnErrorListener((mp, what, extra) -> {
            playing = false;
            setPauseText();
            Toast.makeText(this, "Этот формат может не поддерживаться. Открой в VLC.", Toast.LENGTH_LONG).show();
            return true;
        });
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(6, 8, 6, 8);
        addControl(controls, "◀◀", () -> step(-1));
        addControl(controls, "−30", () -> seek(-30000));
        pauseButton = addControl(controls, "Пауза", () -> toggle());
        addControl(controls, "+30", () -> seek(30000));
        addControl(controls, "▶▶", () -> step(1));
        root.addView(controls);
        LinearLayout secondary = new LinearLayout(this);
        addControl(secondary, "Открыть в VLC", () -> openExternal());
        addControl(secondary, "Закрыть", () -> finish());
        root.addView(secondary);
        setContentView(root);
    }

    private Button button(String title, Runnable action) {
        Button result = new Button(this);
        result.setText(title);
        result.setTextSize(12);
        result.setAllCaps(false);
        result.setOnClickListener(v -> action.run());
        return result;
    }

    private Button addControl(LinearLayout container, String title, Runnable action) {
        Button b = button(title, action);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(1, 2, 1, 2);
        container.addView(b, new LinearLayout.LayoutParams(0, 54, 1));
        return b;
    }

    private void setPauseText() { if (pauseButton != null) pauseButton.setText(playing ? "Пауза" : "Играть"); }

    private void accept(Intent command) {
        if (command == null) return;
        String action = command.getStringExtra("remote_action");
        if ("play".equals(action)) {
            String id = command.getStringExtra("remote_id");
            String newTicket = command.getStringExtra("remote_ticket");
            String newHost = MediaLibrary.prefs(this).getString("remote_host", "");
            try {
                newHost = DownloadEngine.validatedHost(newHost);
                if (id == null || !id.matches("[0-9a-f]{24}")
                        || newTicket == null || !newTicket.matches("[0-9a-f]{48}")) return;
                host = newHost;
                ticket = newTicket;
                playId(id);
            } catch (Exception ignored) { }
        } else if ("pause".equals(action)) {
            if (video.isPlaying()) { video.pause(); playing = false; setPauseText(); }
        } else if ("resume".equals(action)) {
            if (!currentUrl.isEmpty()) { video.start(); playing = true; setPauseText(); }
        } else if ("stop".equals(action)) {
            video.stopPlayback(); playing = false; setPauseText(); finish();
        } else if ("seek_forward".equals(action)) seek(30000);
        else if ("seek_back".equals(action)) seek(-30000);
        else if ("next".equals(action)) step(1);
        else if ("previous".equals(action)) step(-1);
        else if ("volume_up".equals(action)) changeVolume(true);
        else if ("volume_down".equals(action)) changeVolume(false);
    }

    private void changeVolume(boolean louder) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    louder ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
        }
    }

    private void playId(String id) {
        currentId = id;
        try {
            currentUrl = "http://" + host + ":" + HttpFileServer.PORT + "/stream?id="
                    + URLEncoder.encode(id, "UTF-8") + "&ticket=" + URLEncoder.encode(ticket, "UTF-8");
            JSONArray list = new JSONArray(MediaLibrary.prefs(this).getString("remote_cache", "[]"));
            String title = "Серия";
            for (int i = 0; i < list.length(); i++) {
                JSONObject obj = list.optJSONObject(i);
                if (obj != null && id.equals(obj.optString("id"))) {
                    title = obj.optString("name", title); break;
                }
            }
            heading.setText(title);
            video.setVideoURI(Uri.parse(currentUrl));
            video.requestFocus();
        } catch (Exception e) { Toast.makeText(this, "Не удалось открыть серию", Toast.LENGTH_SHORT).show(); }
    }

    private void toggle() {
        if (video.isPlaying()) { video.pause(); playing = false; }
        else if (!currentUrl.isEmpty()) { video.start(); playing = true; }
        setPauseText();
    }

    private void seek(int millis) {
        try {
            int position = video.getCurrentPosition();
            int duration = video.getDuration();
            long target = Math.max(0L, (long) position + millis);
            if (duration > 0) target = Math.min(target, duration - 1L);
            video.seekTo((int)target);
        } catch (Exception ignored) { }
    }

    private void step(int delta) {
        try {
            JSONArray list = new JSONArray(MediaLibrary.prefs(this).getString("remote_cache", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject obj = list.optJSONObject(i);
                if (obj != null && currentId.equals(obj.optString("id"))) {
                    int next = i + delta;
                    if (next >= 0 && next < list.length() && list.optJSONObject(next) != null)
                        playId(list.optJSONObject(next).optString("id"));
                    return;
                }
            }
        } catch (Exception ignored) { }
    }

    private void openExternal() {
        if (currentUrl.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(currentUrl), "video/*");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            video.pause();
            startActivity(Intent.createChooser(intent, "Внешний плеер"));
            Toast.makeText(this, "Управляй внешним плеером с его экрана", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Установи VLC", Toast.LENGTH_SHORT).show(); }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { accept(intent); }
        };
        IntentFilter filter = new IntentFilter(ACTION_PLAYER);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override protected void onPause() {
        visible = false;
        if (receiver != null) { unregisterReceiver(receiver); receiver = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        video.stopPlayback();
        super.onDestroy();
    }
}
