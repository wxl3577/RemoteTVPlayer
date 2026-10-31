package com.wxl.remotetvplayer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final String HELP = "← / → 快退 / 快进 10 秒    ↓ 暂停 / 继续    ↑ 重新读取地址";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private PlayerView video;
    private TextView overlay;
    private ExoPlayer player;
    private Future<?> request;
    private boolean active;
    private boolean wantPlay = true;
    private int generation;
    private int failures;
    private String currentUrl;
    private long savedPosition;
    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);
    private final Runnable retry = () -> loadSource(false);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new PlayerView(this);
        video.setUseController(false);
        video.setKeepContentOnPlayerReset(true);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(20);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(24, 16, 24, 16);
        overlay.setBackgroundColor(0xCC101827);
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        panel.setMargins(32, 0, 32, 28);
        root.addView(overlay, panel);
        setContentView(root);
        if (state != null) {
            currentUrl = state.getString("url");
            savedPosition = state.getLong("position", 0);
            wantPlay = state.getBoolean("playing", true);
        }
        fullscreen();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("RemoteTVPlayer/1.0")
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this,
                new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(wantPlay);
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (!active) return;
                if (state == Player.STATE_BUFFERING) show("正在缓冲…\n" + HELP, false);
                if (state == Player.STATE_READY) {
                    failures = 0;
                    main.removeCallbacks(retry);
                    show((player.getPlayWhenReady() ? "正在播放" : "已暂停") + "\n" + HELP,
                            player.getPlayWhenReady());
                }
                if (state == Player.STATE_ENDED) show("播放结束\n↑ 重新读取并播放    ← 后退 10 秒", false);
            }
            @Override public void onPlayerError(PlaybackException error) {
                // Do not display/log signed URLs from exception messages.
                String detail;
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
                    detail = "电视无法解码此视频，请更换兼容的视频格式";
                } else {
                    detail = "播放失败：" + error.getErrorCodeName() + "\n请检查视频直链是否过期或网络是否可用";
                }
                scheduleRetry(detail);
            }
        });
        loadSource(false);
    }

    private void loadSource(boolean restart) {
        if (!active) return;
        main.removeCallbacks(retry);
        if (request != null) request.cancel(true);
        final int ticket = ++generation;
        show("正在读取远程播放地址…\n" + HELP, false);
        request = worker.submit(() -> {
            try {
                String url = RemoteSource.fetch();
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    long position = 0;
                    if (!restart && url.equals(currentUrl)) {
                        position = player.getMediaItemCount() > 0 ? player.getCurrentPosition() : savedPosition;
                    }
                    if (restart) wantPlay = true;
                    currentUrl = url;
                    savedPosition = position;
                    player.setMediaItem(MediaItem.fromUri(url), position);
                    player.setPlayWhenReady(wantPlay);
                    player.prepare();
                });
            } catch (IOException error) {
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    String message = error.getMessage();
                    // Only our known application messages are safe to show.
                    if (message == null || !(message.startsWith("读取 movie.txt")
                            || message.startsWith("movie.txt") || message.startsWith("文本"))) {
                        message = "无法连接文本服务器，请检查网络";
                    }
                    scheduleRetry(message);
                });
            }
        });
    }

    private void scheduleRetry(String message) {
        int seconds = Math.min(30, 5 << Math.min(failures++, 3));
        show(message + "\n" + seconds + " 秒后重新读取，或按 ↑ 立即重试", false);
        main.removeCallbacks(retry);
        main.postDelayed(retry, seconds * 1000L);
    }

    private void show(String text, boolean temporary) {
        main.removeCallbacks(hideOverlay);
        overlay.setText(text);
        overlay.setVisibility(View.VISIBLE);
        if (temporary) main.postDelayed(hideOverlay, 3500);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean handled = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || player == null) return true;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getRepeatCount() == 0) { failures = 0; loadSource(true); }
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (player.isCurrentMediaItemSeekable()) {
                long position = Math.max(0, player.getCurrentPosition()
                        + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000));
                long duration = player.getDuration();
                if (duration != C.TIME_UNSET) position = Math.min(position, duration);
                player.seekTo(position);
                show((key == KeyEvent.KEYCODE_DPAD_LEFT ? "快退 10 秒  " : "快进 10 秒  ")
                        + formatTime(position) + "\n" + HELP, true);
            } else {
                show("当前视频暂不支持快进快退\n" + HELP, true);
            }
        } else if (event.getRepeatCount() == 0) {
            if (player.getMediaItemCount() == 0 || player.getPlayerError() != null) {
                loadSource(true);
            } else {
                wantPlay = !player.getPlayWhenReady();
                if (player.getPlaybackState() == Player.STATE_ENDED && wantPlay) player.seekTo(0);
                player.setPlayWhenReady(wantPlay);
                show((wantPlay ? "继续播放" : "已暂停") + "\n" + HELP, wantPlay);
            }
        }
        return true;
    }

    private static String formatTime(long ms) {
        long s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("url", currentUrl);
        state.putLong("position", player != null ? player.getCurrentPosition() : savedPosition);
        state.putBoolean("playing", player != null ? player.getPlayWhenReady() : wantPlay);
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        if (request != null) request.cancel(true);
        if (player != null) {
            if (player.getMediaItemCount() > 0) savedPosition = player.getCurrentPosition();
            wantPlay = player.getPlayWhenReady();
            video.setPlayer(null);
            player.release();
            player = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullscreen();
    }

    @SuppressWarnings("deprecation") private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
