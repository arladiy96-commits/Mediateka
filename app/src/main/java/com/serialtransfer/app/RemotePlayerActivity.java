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
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.graphics.drawable.GradientDrawable;
import java.util.Locale;

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
    private Button zoomButton;
    private SeekBar seekBar;
    private TextView timeElapsed;
    private TextView timeTotal;
    private boolean scrubbing;
    private final Runnable progressUpdate = new Runnable() {
        @Override public void run() {
            if (video != null && seekBar != null && !scrubbing) {
                int duration = video.getDuration();
                int current = video.getCurrentPosition();
                if (duration > 0) {
                    seekBar.setProgress((int) Math.min(1000L, Math.max(0L,
                            (long) current * 1000L / duration)));
                    timeTotal.setText(time(duration));
                }
                timeElapsed.setText(time(Math.max(0, current)));
            }
            uiHandler.postDelayed(this, 900);
        }
    };
    private FrameLayout fullScreen;
    private LinearLayout topOverlay;
    private LinearLayout bottomOverlay;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean overlaysVisible = true;
    private boolean cropToFill = true;
    private final Runnable hideOverlays = new Runnable() {
        @Override public void run() { setOverlaysVisible(false); }
    };
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        build();
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }

    /** Full-screen lightweight player: no posters, no persistent panels over video. */
    private void build() {
        fullScreen = new FrameLayout(this);
        fullScreen.setBackgroundColor(Color.BLACK);
        fullScreen.setClipChildren(true);
        fullScreen.setClipToPadding(true);

        video = new VideoView(this);
        fullScreen.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setOnClickListener(v -> toggleOverlays());
        fullScreen.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateVideoScale());
        video.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateVideoScale());
        video.setOnPreparedListener(mp -> {
            mp.setScreenOnWhilePlaying(true);
            video.start();
            playing = true;
            setPauseText();
            video.post(() -> updateVideoScale());
            video.postDelayed(() -> updateVideoScale(), 250);
            scheduleOverlaysHide();
        });
        video.setOnCompletionListener(mp -> step(1));
        video.setOnErrorListener((mp, what, extra) -> {
            playing = false;
            setPauseText();
            setOverlaysVisible(true);
            Toast.makeText(this, "Плеер не поддерживает формат. Попробуй VLC.",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        topOverlay = new LinearLayout(this);
        topOverlay.setGravity(Gravity.CENTER_VERTICAL);
        topOverlay.setPadding(dp(14), dp(7), dp(12), dp(7));
        topOverlay.setBackgroundColor(Color.argb(185, 6, 16, 24));
        Button close = button("‹", () -> finish());
        close.setTextSize(26);
        topOverlay.addView(close, new LinearLayout.LayoutParams(dp(46), dp(45)));
        heading = new TextView(this);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(15);
        heading.setTypeface(null, 1);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setText("Медиатека");
        topOverlay.addView(heading, new LinearLayout.LayoutParams(0, dp(45), 1));
        Button fill = button("⛶", () -> {
            cropToFill = !cropToFill;
            updateVideoScale();
            showOverlaysBriefly();
        });
        fill.setContentDescription("Вписать или заполнить экран");
        zoomButton = fill;
        topOverlay.addView(zoomButton, new LinearLayout.LayoutParams(dp(48), dp(45)));
        fullScreen.addView(topOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        bottomOverlay = new LinearLayout(this);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setPadding(dp(18), dp(12), dp(18), dp(13));
        bottomOverlay.setBackgroundColor(Color.argb(186, 6, 16, 24));

        LinearLayout timeline = new LinearLayout(this);
        timeline.setGravity(Gravity.CENTER_VERTICAL);
        timeElapsed = smallTime("00:00");
        timeTotal = smallTime("00:00");
        timeline.addView(timeElapsed, new LinearLayout.LayoutParams(dp(54), -2));
        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        if (Build.VERSION.SDK_INT >= 21) {
            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(25, 204, 231)));
            seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.rgb(25, 204, 231)));
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { scrubbing = true; }
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                int duration = video.getDuration();
                if (duration > 0) video.seekTo((int) ((long) duration * bar.getProgress() / 1000L));
                scrubbing = false;
                showOverlaysBriefly();
            }
        });
        timeline.addView(seekBar, new LinearLayout.LayoutParams(0, dp(40), 1));
        timeline.addView(timeTotal, new LinearLayout.LayoutParams(dp(54), -2));
        bottomOverlay.addView(timeline);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        addControl(controls, "|◀", () -> step(-1));
        addControl(controls, "−10", () -> seek(-10000));
        pauseButton = addControl(controls, "Ⅱ", () -> toggle());
        pauseButton.setTextSize(22);
        pauseButton.setTextColor(Color.rgb(25, 204, 231));
        addControl(controls, "+10", () -> seek(10000));
        addControl(controls, "▶|", () -> step(1));
        bottomOverlay.addView(controls);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        Button external = button("Открыть в VLC", () -> openExternal());
        external.setTextSize(12);
        actions.addView(external, new LinearLayout.LayoutParams(dp(145), dp(37)));
        bottomOverlay.addView(actions);
        fullScreen.addView(bottomOverlay, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(fullScreen);
        showOverlaysBriefly();
    }

    private TextView smallTime(String initial) {
        TextView view = new TextView(this);
        view.setText(initial);
        view.setTextSize(12);
        view.setTextColor(Color.rgb(211, 228, 239));
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private String time(int milliseconds) {
        int seconds = milliseconds / 1000;
        int minutes = seconds / 60;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds % 60);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Equal X/Y scaling fills the display without distorting the video. */
    private void updateVideoScale() {
        if (video == null || fullScreen == null) return;
        if (!cropToFill) {
            video.setScaleX(1f);
            video.setScaleY(1f);
            return;
        }
        int screenWidth = fullScreen.getWidth();
        int screenHeight = fullScreen.getHeight();
        int videoWidth = video.getWidth();
        int videoHeight = video.getHeight();
        if (screenWidth <= 0 || screenHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return;
        float scale = Math.max((float) screenWidth / videoWidth,
                (float) screenHeight / videoHeight);
        video.setPivotX(videoWidth / 2f);
        video.setPivotY(videoHeight / 2f);
        video.setScaleX(scale);
        video.setScaleY(scale);
    }

    private void setOverlaysVisible(boolean visible) {
        overlaysVisible = visible;
        if (topOverlay != null) topOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (bottomOverlay != null) bottomOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) uiHandler.removeCallbacks(hideOverlays);
    }

    private void scheduleOverlaysHide() {
        uiHandler.removeCallbacks(hideOverlays);
        if (overlaysVisible) uiHandler.postDelayed(hideOverlays, 4500);
    }

    private void showOverlaysBriefly() {
        setOverlaysVisible(true);
        scheduleOverlaysHide();
    }

    private void toggleOverlays() {
        if (overlaysVisible) setOverlaysVisible(false);
        else showOverlaysBriefly();
    }

    private Button button(String title, Runnable action) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(3), dp(2), dp(3), dp(2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(175, 20, 42, 55));
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        b.setOnClickListener(v -> { action.run(); showOverlaysBriefly(); });
        return b;
    }

    private Button addControl(LinearLayout container, String title, Runnable action) {
        Button b = button(title, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(45), 1);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        container.addView(b, lp);
        return b;
    }

    private void setPauseText() { if (pauseButton != null) pauseButton.setText(playing ? "Ⅱ" : "▶"); }

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
            video.setScaleX(1f);
            video.setScaleY(1f);
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
        uiHandler.removeCallbacks(progressUpdate);
        uiHandler.post(progressUpdate);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { accept(intent); }
        };
        IntentFilter filter = new IntentFilter(ACTION_PLAYER);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
    }

    @Override protected void onPause() {
        visible = false;
        uiHandler.removeCallbacks(progressUpdate);
        if (receiver != null) { unregisterReceiver(receiver); receiver = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        super.onDestroy();
    }
}
