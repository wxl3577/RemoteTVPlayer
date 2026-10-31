package com.wxl.remotetvplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    private static final String HELP = "← / → 快退 / 快进 10 秒    ↓ 暂停 / 继续    ↑ 字幕 / 音轨设置";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService subtitleWorker = Executors.newSingleThreadExecutor();
    private PlayerView video;
    private TextView overlay;
    private TextView menu;
    private boolean menuOpen;
    private int menuRow;
    private int textSelection = 1;
    private int audioSelection;
    private SubtitleSpec externalSubtitle;
    private SubtitleSpec localSubtitle;
    private final List<SubtitleSpec> remoteSubtitles = new ArrayList<>();
    private String subtitleStatus = "可读取 subtitle.txt，或选择本地字幕文件";
    private Future<?> subtitleRequest;
    private int subtitleGeneration;
    private boolean subtitleListLoaded;
    private boolean pendingExternalSelection;
    private boolean pendingAudioSelection;
    private String desiredAudioKey;
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
        menu = new TextView(this);
        menu.setTextColor(Color.WHITE);
        menu.setTextSize(18);
        menu.setTag("settings-menu");
        menu.setPadding(24, 20, 24, 20);
        menu.setBackgroundColor(0xF0101827);
        menu.setVisibility(View.GONE);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
        root.addView(menu, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER));
        setContentView(root);
        if (state != null) {
            currentUrl = state.getString("url");
            savedPosition = state.getLong("position", 0);
            wantPlay = state.getBoolean("playing", true);
            String subtitleUri = state.getString("subtitleUri");
            if (subtitleUri != null) externalSubtitle = new SubtitleSpec(subtitleUri,
                    state.getString("subtitleLabel", "外挂字幕"), state.getString("subtitleMime", "application/x-subrip"));
        }
        String localUri = getPreferences(MODE_PRIVATE).getString("localSubtitleUri", null);
        if (localUri != null) localSubtitle = new SubtitleSpec(localUri,
                getPreferences(MODE_PRIVATE).getString("localSubtitleLabel", "本地字幕"),
                getPreferences(MODE_PRIVATE).getString("localSubtitleMime", "application/x-subrip"));
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
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(new DefaultDataSource.Factory(this, http)))
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(wantPlay);
        video.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) {
                if (pendingAudioSelection && desiredAudioKey != null) {
                    List<TrackChoice> choices = MainActivity.this.tracks(C.TRACK_TYPE_AUDIO);
                    for (int i = 0; i < choices.size(); i++) {
                        TrackChoice choice = choices.get(i);
                        if (desiredAudioKey.equals(formatKey(choice.group.getTrackFormat(choice.index)))) {
                            pendingAudioSelection = false;
                            audioSelection = i + 1;
                            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                    .setOverrideForType(new TrackSelectionOverride(choice.group.getMediaTrackGroup(), choice.index)).build());
                            break;
                        }
                    }
                }
                if (pendingExternalSelection) {
                    List<TrackChoice> choices = MainActivity.this.tracks(C.TRACK_TYPE_TEXT);
                    for (int i = 0; i < choices.size(); i++) {
                        TrackChoice choice = choices.get(i);
                        if ("external".equals(choice.group.getTrackFormat(choice.index).id)) {
                            pendingExternalSelection = false;
                            textSelection = i + 2;
                            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                                    .setOverrideForType(new TrackSelectionOverride(choice.group.getMediaTrackGroup(), choice.index)).build());
                            break;
                        }
                    }
                }
                if (menuOpen) renderMenu();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (!active) return;
                if (state == Player.STATE_BUFFERING) show("正在缓冲…\n" + HELP, false);
                if (state == Player.STATE_READY) {
                    failures = 0;
                    main.removeCallbacks(retry);
                    show((player.getPlayWhenReady() ? "正在播放" : "已暂停") + "\n" + HELP,
                            player.getPlayWhenReady());
                }
                if (state == Player.STATE_ENDED) show("播放结束\n↑ 设置菜单中可重新播放    ← 后退 10 秒", false);
            }
            @Override public void onPlayerError(PlaybackException error) {
                if (externalSubtitle != null) {
                    externalSubtitle = null;
                    subtitleStatus = "播放失败，已暂时移除外挂字幕并重试视频";
                    prepareMedia(player.getCurrentPosition());
                    if (menuOpen) renderMenu();
                    return;
                }
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
                    if (!url.equals(currentUrl)) desiredAudioKey = null;
                    currentUrl = url;
                    savedPosition = position;
                    prepareMedia(position);
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

    private void prepareMedia(long position) {
        if (player == null || currentUrl == null) return;
        MediaItem.Builder item = new MediaItem.Builder().setUri(currentUrl);
        if (externalSubtitle != null) {
            MediaItem.SubtitleConfiguration subtitle = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(externalSubtitle.uri))
                    .setId("external").setLabel(externalSubtitle.label).setMimeType(externalSubtitle.mime)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
            item.setSubtitleConfigurations(Collections.singletonList(subtitle));
        }
        textSelection = 1;
        audioSelection = 0;
        pendingExternalSelection = externalSubtitle != null;
        pendingAudioSelection = desiredAudioKey != null;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage("zh").setSelectUndeterminedTextLanguage(true).build());
        player.setMediaItem(item.build(), position);
        player.setPlayWhenReady(wantPlay);
        player.prepare();
    }

    private static final class TrackChoice {
        final Tracks.Group group;
        final int index;
        final String label;
        TrackChoice(Tracks.Group group, int index, String label) {
            this.group = group; this.index = index; this.label = label;
        }
    }

    private List<TrackChoice> tracks(int type) {
        List<TrackChoice> result = new ArrayList<>();
        if (player == null) return result;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != type) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                String label = format.label != null ? format.label : "轨道 " + (result.size() + 1);
                if (format.language != null) label += " / " + format.language;
                if (format.sampleMimeType != null) label += " / " + format.sampleMimeType.replace("application/", "").replace("audio/", "");
                result.add(new TrackChoice(group, i, shortLabel(label)));
            }
        }
        return result;
    }

    private static String shortLabel(String label) {
        return label.length() > 52 ? label.substring(0, 49) + "…" : label;
    }

    private static String formatKey(Format format) {
        return format.id != null ? format.id : format.language + ":" + format.label + ":" + format.sampleMimeType;
    }

    private void openMenu() {
        menuOpen = true;
        menuRow = 0;
        menu.setVisibility(View.VISIBLE);
        renderMenu();
        if (!subtitleListLoaded) loadSubtitleList();
    }

    private void closeMenu() { menuOpen = false; menu.setVisibility(View.GONE); }

    private void renderMenu() {
        List<TrackChoice> text = tracks(C.TRACK_TYPE_TEXT);
        List<TrackChoice> audio = tracks(C.TRACK_TYPE_AUDIO);
        textSelection = Math.min(textSelection, text.size() + 1);
        audioSelection = Math.min(audioSelection, audio.size());
        String textName = textSelection == 0 ? "关闭" : textSelection == 1 ? "自动（优先中文）"
                : text.get(textSelection - 2).label;
        String audioName = audioSelection == 0 ? "自动" : audio.get(audioSelection - 1).label;
        String[] rows = {
                "字幕轨道：‹ " + textName + " ›  （" + text.size() + " 条可用）",
                "音轨：‹ " + audioName + " ›  （" + audio.size() + " 条可用）",
                "外挂字幕：‹ " + (externalSubtitle == null ? "不加载" : shortLabel(externalSubtitle.label)) + " ›",
                "读取 / 刷新远程字幕列表  →",
                "选择电视或 U 盘中的字幕文件  →",
                "重新读取视频地址 / 从头播放  →",
                "返回播放  ← / →"
        };
        SpannableStringBuilder display = new SpannableStringBuilder("字幕与音轨\n↑ ↓ 选择项目，← → 切换或执行\n\n");
        for (int i = 0; i < rows.length; i++) {
            int start = display.length();
            display.append(i == menuRow ? "▶ " : "   ").append(rows[i]).append('\n');
            if (i == menuRow) {
                display.setSpan(new BackgroundColorSpan(0xFF155E75), start, display.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                display.setSpan(new ForegroundColorSpan(Color.WHITE), start, display.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        display.append('\n').append(shortLabel(subtitleStatus));
        menu.setText(display);
    }

    private void loadSubtitleList() {
        if (!active) return;
        if (subtitleRequest != null) subtitleRequest.cancel(true);
        final int ticket = ++subtitleGeneration;
        subtitleStatus = "正在读取 subtitle.txt…";
        renderMenu();
        subtitleRequest = subtitleWorker.submit(() -> {
            try {
                List<SubtitleSpec> entries = SubtitleSpec.parseList(RemoteSource.fetchText(SubtitleSpec.LIST_URL));
                main.post(() -> {
                    if (!active || ticket != subtitleGeneration) return;
                    remoteSubtitles.clear();
                    remoteSubtitles.addAll(entries);
                    subtitleListLoaded = true;
                    subtitleStatus = entries.isEmpty() ? "字幕列表为空；可选本地文件" : "已读取 " + entries.size() + " 条外挂字幕，在“外挂字幕”行切换";
                    if (menuOpen) renderMenu();
                });
            } catch (IOException error) {
                main.post(() -> {
                    if (!active || ticket != subtitleGeneration) return;
                    subtitleListLoaded = true;
                    subtitleStatus = "未读取到 subtitle.txt；内嵌字幕和本地字幕仍可用";
                    if (menuOpen) renderMenu();
                });
            }
        });
    }

    private List<SubtitleSpec> externalOptions() {
        List<SubtitleSpec> choices = new ArrayList<>(remoteSubtitles);
        if (localSubtitle != null) choices.add(localSubtitle);
        if (externalSubtitle != null) {
            boolean found = false;
            for (SubtitleSpec choice : choices) if (choice.uri.equals(externalSubtitle.uri)) found = true;
            if (!found) choices.add(externalSubtitle);
        }
        return choices;
    }

    private void adjustMenu(int direction) {
        if (player == null) return;
        if (menuRow == 0 || menuRow == 1) {
            int type = menuRow == 0 ? C.TRACK_TYPE_TEXT : C.TRACK_TYPE_AUDIO;
            List<TrackChoice> choices = tracks(type);
            int offset = menuRow == 0 ? 2 : 1;
            int selection = menuRow == 0 ? textSelection : audioSelection;
            selection = (selection + direction + choices.size() + offset) % (choices.size() + offset);
            TrackSelectionParameters.Builder parameters = player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(type);
            if (type == C.TRACK_TYPE_TEXT) parameters.setTrackTypeDisabled(type, selection == 0);
            if (selection >= offset) {
                TrackChoice choice = choices.get(selection - offset);
                parameters.setOverrideForType(new TrackSelectionOverride(choice.group.getMediaTrackGroup(), choice.index));
            }
            if (menuRow == 0) {
                textSelection = selection;
                pendingExternalSelection = false;
            } else {
                audioSelection = selection;
                pendingAudioSelection = false;
                desiredAudioKey = selection == 0 ? null : formatKey(choices.get(selection - offset).group
                        .getTrackFormat(choices.get(selection - offset).index));
            }
            player.setTrackSelectionParameters(parameters.build());
        } else if (menuRow == 2) {
            List<SubtitleSpec> choices = externalOptions();
            int index = 0;
            if (externalSubtitle != null) {
                for (int i = 0; i < choices.size(); i++) if (choices.get(i).uri.equals(externalSubtitle.uri)) index = i + 1;
            }
            index = (index + direction + choices.size() + 1) % (choices.size() + 1);
            SubtitleSpec selected = index == 0 ? null : choices.get(index - 1);
            if (selected != externalSubtitle) {
                externalSubtitle = selected;
                savedPosition = player.getCurrentPosition();
                wantPlay = player.getPlayWhenReady();
                prepareMedia(savedPosition);
            }
            if (choices.isEmpty()) subtitleStatus = "请先读取远程字幕列表或选择本地字幕文件";
        } else if (menuRow == 3) {
            loadSubtitleList();
        } else if (menuRow == 4) {
            chooseLocalSubtitle();
        } else if (menuRow == 5) {
            failures = 0;
            closeMenu();
            loadSource(true);
        } else {
            closeMenu();
        }
        if (menuOpen) renderMenu();
    }

    @SuppressWarnings("deprecation") private void chooseLocalSubtitle() {
        if (player != null) {
            savedPosition = player.getCurrentPosition();
            wantPlay = player.getPlayWhenReady();
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, 1001); }
        catch (ActivityNotFoundException error) {
            subtitleStatus = "电视没有系统文件选择器，请使用远程 subtitle.txt";
            renderMenu();
        }
    }

    @SuppressWarnings("deprecation") @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1001 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "本地字幕.srt";
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) { }
        if (name == null) name = "本地字幕.srt";
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass")
                || lower.endsWith(".ssa") || lower.endsWith(".ttml") || lower.endsWith(".xml"))) {
            subtitleStatus = "请选择 SRT、VTT、ASS、SSA 或 TTML 字幕文件";
            if (menuOpen) renderMenu();
            return;
        }
        try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException ignored) { }
        localSubtitle = new SubtitleSpec(uri.toString(), name, SubtitleSpec.mimeForName(name));
        externalSubtitle = localSubtitle;
        getPreferences(MODE_PRIVATE).edit().putString("localSubtitleUri", localSubtitle.uri)
                .putString("localSubtitleLabel", localSubtitle.label).putString("localSubtitleMime", localSubtitle.mime).apply();
        subtitleStatus = "已添加 " + shortLabel(name);
        prepareMedia(savedPosition);
        if (menuOpen) renderMenu();
    }

    private void scheduleRetry(String message) {
        int seconds = Math.min(30, 5 << Math.min(failures++, 3));
        show(message + "\n" + seconds + " 秒后重新读取，或按 ↑ 打开菜单重试", false);
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
        if (menuOpen && key == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) closeMenu();
            return true;
        }
        boolean handled = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN || player == null) return true;
        if (menuOpen) {
            if (event.getRepeatCount() != 0) return true;
            if (key == KeyEvent.KEYCODE_DPAD_UP) menuRow = (menuRow + 6) % 7;
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) menuRow = (menuRow + 1) % 7;
            else adjustMenu(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (menuOpen) renderMenu();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getRepeatCount() == 0) openMenu();
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
        if (externalSubtitle != null) {
            state.putString("subtitleUri", externalSubtitle.uri);
            state.putString("subtitleLabel", externalSubtitle.label);
            state.putString("subtitleMime", externalSubtitle.mime);
        }
    }

    @Override protected void onStop() {
        active = false;
        generation++;
        subtitleGeneration++;
        if (subtitleRequest != null) subtitleRequest.cancel(true);
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

    @Override protected void onDestroy() { worker.shutdownNow(); subtitleWorker.shutdownNow(); super.onDestroy(); }

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
