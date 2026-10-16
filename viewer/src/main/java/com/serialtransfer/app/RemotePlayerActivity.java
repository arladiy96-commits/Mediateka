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
import android.view.MotionEvent;
import android.view.GestureDetector;
import android.widget.Toast;
import android.view.WindowManager;
import android.text.TextUtils;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;
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
    static volatile String playbackId = "";
    static volatile int playbackPosition;
    static volatile int playbackDuration;
    static volatile boolean playbackPlaying;
    private MediaPlayer preparedPlayer;
    private GestureDetector gesture;
    private int lastGoodPosition;
    private int stalledSeconds;
    private long bufferingSince;
    private boolean isBuffering;
    private boolean manualPause;
    private boolean retrying;
    private boolean externalPlayer;
    private int autoNextDelayMs = 5000;
    private TextView connectionHint;
    private boolean lockedControls;
    private int lastSavedVolume = -1;
    private final Runnable retryStream = new Runnable() {
        @Override public void run() {
            if (!retrying || isFinishing() || currentId.isEmpty()) return;
            new Thread(() -> {
                String candidate = MediaLibrary.prefs(RemotePlayerActivity.this)
                        .getString("remote_host", "");
                String secret = MediaLibrary.prefs(RemotePlayerActivity.this)
                        .getString("remote_code", "");
                String refreshedTicket = "";
                try {
                    JSONObject result = new JSONObject(DownloadEngine.list(candidate, secret));
                    refreshedTicket = result.optString("stream_ticket", "");
                    MediaLibrary.prefs(RemotePlayerActivity.this).edit()
                            .putString("remote_cache", result.optJSONArray("files") == null
                                    ? "[]" : result.optJSONArray("files").toString()).apply();
                } catch (Exception offline) {
                    for (String possible : LanDiscovery.find(RemotePlayerActivity.this)) {
                        try {
                            JSONObject result = new JSONObject(DownloadEngine.list(possible, secret));
                            refreshedTicket = result.optString("stream_ticket", "");
                            candidate = possible;
                            MediaLibrary.prefs(RemotePlayerActivity.this).edit()
                                    .putString("remote_host", candidate)
                                    .putString("remote_cache", result.optJSONArray("files") == null
                                            ? "[]" : result.optJSONArray("files").toString()).commit();
                            break;
                        } catch (Exception denied) { }
                    }
                }
                final String newHost = candidate;
                final String newTicket = refreshedTicket;
                uiHandler.post(() -> {
                    if (!retrying || isFinishing()) return;
                    if (newTicket.matches("[0-9a-f]{48}")) {
                        host = newHost;
                        ticket = newTicket;
                        retrying = false;
                        isBuffering = false;
                        if (connectionHint != null) connectionHint.setVisibility(View.GONE);
                        restorePosition = Math.max(lastGoodPosition,
                                WatchHistory.resumeAt(RemotePlayerActivity.this, currentId));
                        String resumeId = currentId;
                        currentId = ""; // avoid overwriting saved position when switching URL
                        playId(resumeId);
                    } else {
                        uiHandler.postDelayed(retryStream, 6500);
                    }
                });
            }, "MediatekaStreamReconnect").start();
        }
    };

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
                playbackId = currentId;
                playbackPosition = Math.max(0, current);
                playbackDuration = Math.max(0, duration);
                playbackPlaying = video.isPlaying() && !retrying;
                if (isBuffering && !retrying && !manualPause && bufferingSince > 0
                        && android.os.SystemClock.elapsedRealtime() - bufferingSince > 16000) {
                    startRecovery();
                }
                if (video.isPlaying() && current > 0) {
                    if (current > lastGoodPosition) {
                        lastGoodPosition = current;
                        stalledSeconds = 0;
                    } else if (!retrying && !isBuffering) {
                        stalledSeconds++;
                        if (stalledSeconds > 20) startRecovery();
                    }
                }
                if (duration > 0) {
                    seekBar.setProgress((int) Math.min(1000L, Math.max(0L,
                            (long) current * 1000L / duration)));
                    timeTotal.setText(time(duration));
                }
                timeElapsed.setText(time(Math.max(0, current)));
                if (video.isPlaying() && !currentId.isEmpty() && !retrying) savePosition(false);
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
    private boolean completedPlayback;
    private int restorePosition;
    private String currentTitle = "Серия";
    private BroadcastReceiver receiver;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();
        autoNextDelayMs = MediaLibrary.prefs(this).getBoolean("auto_next_episode_v7", true) ? 5000 : -1;
        if (MediaLibrary.prefs(this).getBoolean("remember_volume_v7", false))
            lastSavedVolume = MediaLibrary.prefs(this).getInt("player_volume_v7", -1);
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
        // VideoView uses SurfaceView, which does not reliably dispatch click events.
        // An invisible tap layer above the video catches touches even after overlays hide.
        View tapLayer = new View(this);
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!lockedControls) toggleOverlays();
                else showOverlaysBriefly();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (lockedControls) { lockedControls = false; showOverlaysBriefly(); }
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (lockedControls) return true;
                if (e.getX() < fullScreen.getWidth() / 2f) seek(-10000);
                else seek(10000);
                showOverlaysBriefly();
                return true;
            }
        });
        tapLayer.setOnTouchListener((v, event) -> {
            return gesture.onTouchEvent(event);
        });
        fullScreen.addView(tapLayer, new FrameLayout.LayoutParams(-1, -1));
        fullScreen.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateVideoScale());
        video.addOnLayoutChangeListener((v, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateVideoScale());
        video.setOnPreparedListener(mp -> {
            preparedPlayer = mp;
            mp.setOnInfoListener((player, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    isBuffering = true;
                    bufferingSince = android.os.SystemClock.elapsedRealtime();
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    isBuffering = false;
                    bufferingSince = 0;
                }
                return false;
            });
            mp.setScreenOnWhilePlaying(true);
            if (lastSavedVolume >= 0 && MediaLibrary.prefs(this)
                    .getBoolean("remember_volume_v7", false)) {
                AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (audio != null) audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                        Math.min(lastSavedVolume, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
            }
            video.start();
            if (restorePosition > 0) {
                int duration = video.getDuration();
                int position = duration > 0 ? Math.min(restorePosition, Math.max(0, duration - 1000))
                        : restorePosition;
                video.seekTo(position);
            }
            restorePosition = 0;
            retrying = false;
            isBuffering = false;
            manualPause = false;
            stalledSeconds = 0;
            playing = true;
            setPauseText();
            video.post(() -> updateVideoScale());
            video.postDelayed(() -> updateVideoScale(), 250);
            scheduleOverlaysHide();
        });
        video.setOnCompletionListener(mp -> {
            savePosition(true);
            completedPlayback = true;
            if (autoNextDelayMs > 0 && !isFinishing()) {
                Toast.makeText(this, "Следующая серия через 5 секунд", Toast.LENGTH_SHORT).show();
                uiHandler.postDelayed(() -> {
                    if (completedPlayback && !isFinishing()) step(1);
                }, autoNextDelayMs);
            } else {
                showOverlaysBriefly();
            }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            savePosition(false);
            playing = false;
            setPauseText();
            if (extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT
                    || what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) {
                startRecovery();
            } else {
                setOverlaysVisible(true);
                Toast.makeText(this, "Неподдерживаемый формат. Попробуй VLC.",
                        Toast.LENGTH_LONG).show();
            }
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
        Button tracks = button("☷", () -> trackPicker());
        tracks.setContentDescription("Аудиодорожки и субтитры");
        topOverlay.addView(tracks, new LinearLayout.LayoutParams(dp(45), dp(45)));
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
                uiHandler.postDelayed(() -> savePosition(false), 350);
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
        Button lock = button("🔒 Блокировка", () -> {
            lockedControls = !lockedControls;
            Toast.makeText(this, lockedControls ? "Экран заблокирован. Долгое касание разблокирует"
                    : "Управление разблокировано", Toast.LENGTH_SHORT).show();
            if (lockedControls) setOverlaysVisible(false);
        });
        actions.addView(lock, new LinearLayout.LayoutParams(dp(165), dp(37)));
        bottomOverlay.addView(actions);
        connectionHint = new TextView(this);
        connectionHint.setText("Нет Wi-Fi · ожидание подключения…");
        connectionHint.setTextSize(13);
        connectionHint.setTextColor(Color.WHITE);
        connectionHint.setBackgroundColor(Color.argb(210, 7, 21, 30));
        connectionHint.setGravity(Gravity.CENTER);
        connectionHint.setPadding(dp(12), dp(8), dp(12), dp(8));
        connectionHint.setVisibility(View.GONE);
        fullScreen.addView(connectionHint, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        fullScreen.setOnLongClickListener(v -> { lockedControls = false; showOverlaysBriefly(); return true; });
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
            if (video.isPlaying()) {
                video.pause(); playing = false; manualPause = true;
                setPauseText(); savePosition(false);
            }
        } else if ("resume".equals(action)) {
            if (!currentUrl.isEmpty()) {
                manualPause = false;
                if (retrying) uiHandler.post(retryStream);
                else { video.start(); playing = true; }
                setPauseText();
            }
        } else if ("stop".equals(action)) {
            savePosition(false);
            video.stopPlayback(); playing = false; setPauseText(); finish();
        } else if ("seek_to".equals(action)) {
            seekAbsolute(command.getIntExtra("remote_ms", 0));
        } else if ("seek_forward".equals(action)) seek(30000);
        else if ("seek_back".equals(action)) seek(-30000);
        else if ("next".equals(action)) step(1);
        else if ("previous".equals(action)) step(-1);
        else if ("volume_up".equals(action)) changeVolume(true);
        else if ("volume_down".equals(action)) changeVolume(false);
    }

    private void startRecovery() {
        if (retrying || currentId.isEmpty() || manualPause) return;
        retrying = true;
        isBuffering = true;
        playbackPlaying = false;
        lastGoodPosition = Math.max(lastGoodPosition, video.getCurrentPosition());
        WatchHistory.save(this, currentId, currentTitle, lastGoodPosition,
                Math.max(0, video.getDuration()), false);
        if (connectionHint != null) connectionHint.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(retryStream);
        uiHandler.postDelayed(retryStream, 1500);
    }

    private void seekAbsolute(int ms) {
        if (currentId.isEmpty()) return;
        int duration = video.getDuration();
        int target = Math.max(0, ms);
        if (duration > 0) target = Math.min(target, duration - 1000);
        video.seekTo(target);
        lastGoodPosition = target;
        stalledSeconds = 0;
        uiHandler.postDelayed(() -> savePosition(false), 400);
    }

    private void trackPicker() {
        MediaPlayer media = preparedPlayer;
        if (media == null) { Toast.makeText(this, "Дорожки ещё не загружены", Toast.LENGTH_SHORT).show(); return; }
        try {
            MediaPlayer.TrackInfo[] tracks = media.getTrackInfo();
            java.util.ArrayList<Integer> indexes = new java.util.ArrayList<>();
            java.util.ArrayList<String> captions = new java.util.ArrayList<>();
            for (int i = 0; i < tracks.length; i++) {
                int type = tracks[i].getTrackType();
                if (type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO
                        && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE
                        && type != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) continue;
                indexes.add(i);
                String lang = tracks[i].getLanguage();
                captions.add((type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO ? "Звук" : "Субтитры")
                        + " · " + (lang == null ? "неизвестно" : lang) + " · " + (i + 1));
            }
            if (indexes.isEmpty()) {
                Toast.makeText(this, "Нет доступных дорожек. Попробуй VLC", Toast.LENGTH_LONG).show();
                return;
            }
            new android.app.AlertDialog.Builder(this).setTitle("Дорожки")
                    .setItems(captions.toArray(new String[0]), (dialog, which) -> {
                        try { media.selectTrack(indexes.get(which)); }
                        catch (Exception e) { Toast.makeText(this,
                                "Дорожка не поддерживается. Попробуй VLC", Toast.LENGTH_SHORT).show(); }
                    }).setNegativeButton("Отмена", null).show();
        } catch (Exception error) {
            Toast.makeText(this, "Выбор дорожек не поддерживается", Toast.LENGTH_SHORT).show();
        }
    }

    private void changeVolume(boolean louder) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio != null) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    louder ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            lastSavedVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (MediaLibrary.prefs(this).getBoolean("remember_volume_v7", false))
                MediaLibrary.prefs(this).edit().putInt("player_volume_v7", lastSavedVolume).apply();
        }
    }

    private void playId(String id) {
        if (!currentId.isEmpty()) savePosition(false);
        currentId = id;
        playbackId = id;
        completedPlayback = false;
        stalledSeconds = 0;
        lastGoodPosition = Math.max(restorePosition, WatchHistory.resumeAt(this, id));
        restorePosition = lastGoodPosition;
        uiHandler.removeCallbacks(retryStream);
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
            currentTitle = title;
            heading.setText(title);
            video.setScaleX(1f);
            video.setScaleY(1f);
            if (MediaLibrary.prefs(this).getBoolean("prefer_vlc_v7", false)) {
                openExternal();
                return;
            }
            video.setVideoURI(Uri.parse(currentUrl));
            video.requestFocus();
        } catch (Exception e) { Toast.makeText(this, "Не удалось открыть серию", Toast.LENGTH_SHORT).show(); }
    }

    private void toggle() {
        if (video.isPlaying()) {
            video.pause(); playing = false; manualPause = true; savePosition(false);
        } else if (!currentUrl.isEmpty()) {
            manualPause = false; completedPlayback = false;
            if (retrying) uiHandler.post(retryStream);
            else { video.start(); playing = true; }
        }
        setPauseText();
    }

    private void savePosition(boolean finished) {
        if (currentId.isEmpty() || (completedPlayback && !finished)) return;
        try {
            int position = video.getCurrentPosition();
            int duration = video.getDuration();
            if (finished && duration > 0) position = duration;
            if (retrying && !finished) position = Math.max(position, lastGoodPosition);
            if (!finished && position <= 0) return;
            WatchHistory.save(this, currentId, currentTitle, position, duration, finished);
        } catch (Exception ignored) { }
    }

    private void seek(int millis) {
        try {
            int position = video.getCurrentPosition();
            int duration = video.getDuration();
            long target = Math.max(0L, (long) position + millis);
            if (duration > 0) target = Math.min(target, duration - 1L);
            video.seekTo((int)target);
            uiHandler.postDelayed(() -> savePosition(false), 450);
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
            savePosition(false);
            video.pause();
            if (MediaLibrary.prefs(this).getBoolean("prefer_vlc_v7", false)) {
                Intent vlc = new Intent(intent);
                vlc.setPackage("org.videolan.vlc");
                try { startActivity(vlc); }
                catch (Exception missing) {
                    startActivity(Intent.createChooser(intent, "Внешний плеер"));
                }
            } else startActivity(Intent.createChooser(intent, "Внешний плеер"));
            Toast.makeText(this, "Внешним плеером управляй на устройстве", Toast.LENGTH_LONG).show();
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
        savePosition(false);
        visible = false;
        uiHandler.removeCallbacks(progressUpdate);
        if (receiver != null) { unregisterReceiver(receiver); receiver = null; }
        super.onPause();
    }

    @Override protected void onDestroy() {
        savePosition(false);
        uiHandler.removeCallbacksAndMessages(null);
        video.stopPlayback();
        playbackId = "";
        playbackPosition = 0;
        playbackDuration = 0;
        playbackPlaying = false;
        super.onDestroy();
    }
}
