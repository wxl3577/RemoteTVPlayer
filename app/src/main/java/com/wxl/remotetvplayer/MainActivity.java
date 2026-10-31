package com.wxl.remotetvplayer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** TV-only directional controls, with bundled HEVC and E-AC-3 software decoders. */
public final class MainActivity extends Activity {
    private static final String HELP = "← / → 快退 / 快进 10 秒    ↓ 暂停 / 继续    ↑ 字幕与解码设置";
    private static final int ROWS = 10;
    private static final int[] CACHE_SECONDS = {3, 10, 20};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService subtitles = Executors.newSingleThreadExecutor();
    private FrameLayout root;
    private VLCVideoLayout video;
    private TextView overlay, menu;
    private ScrollView menuScroll;
    private LibVLC engine;
    private MediaPlayer player;
    private Media media;
    private AudioManager audioManager;
    private Future<?> sourceRequest, subtitleRequest, probeRequest;
    private boolean active, menuOpen, wantPlay = true;
    private boolean listLoaded, externalAdded, appliedPause;
    private int generation, listGeneration, menuRow, mode, cacheIndex, textSelection = 1, failures;
    private int desiredAudio = Integer.MIN_VALUE;
    private int desiredSubtitle = Integer.MIN_VALUE;
    private long savedPosition, pendingSeek, sampleAt, previousRead, previousFrames;
    private float buffering;
    private boolean probeStarted;
    private long openedAt;
    private String connectionStatus = "连接检测：尚未执行";
    private double downloadMbps, displayedFps;
    private String currentUrl, status = "等待远程播放地址", subtitleStatus = "可读取 subtitle.txt 或选择本地字幕";
    private SubtitleSpec externalSubtitle, localSubtitle;
    private final List<SubtitleSpec> remoteSubtitles = new ArrayList<>();
    private final Runnable hideOverlay = () -> overlay.setVisibility(View.GONE);
    private final Runnable retry = () -> loadSource(false);
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (player == null) { main.postDelayed(this, 1000); return; }
            IMedia.Stats stats = playbackStats();
            // Buffering large remote MKVs can take longer than decoder startup. Never
            // restart the stream or switch to CPU decoding based only on elapsed time.
            if (stats != null) {
                long now = SystemClock.elapsedRealtime();
                long bytes = (stats.readBytes & 0xffffffffL);
                if (sampleAt > 0 && now > sampleAt) {
                    long delta = (bytes - previousRead) & 0xffffffffL;
                    downloadMbps = delta * 8.0 / ((now - sampleAt) * 1000.0);
                    displayedFps = Math.max(0, stats.displayedPictures - previousFrames) * 1000.0 / (now - sampleAt);
                }
                sampleAt = now; previousRead = bytes; previousFrames = stats.displayedPictures;
            }
            if (!probeStarted && wantPlay && stats != null && stats.readBytes == 0
                    && SystemClock.elapsedRealtime() - openedAt > 20000) probeConnection();
            if (menuOpen) renderMenu();
            main.postDelayed(this, 1000);
        }
    };
    private final AudioManager.OnAudioFocusChangeListener focusListener = focus -> {
        if (focus < 0 && active && player != null && player.isPlaying()) {
            wantPlay = false;
            player.pause();
            show("其他应用占用了声音，已暂停；按 ↓ 继续", false);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mode = getPreferences(MODE_PRIVATE).getInt("decoderMode", 0);
        cacheIndex = Math.max(0, Math.min(CACHE_SECONDS.length - 1, getPreferences(MODE_PRIVATE).getInt("cacheIndex", 1)));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        overlay = new TextView(this);
        overlay.setTextColor(Color.WHITE);
        overlay.setTextSize(18);
        overlay.setGravity(Gravity.CENTER);
        overlay.setPadding(16, 12, 16, 12);
        overlay.setBackgroundColor(0xDD101827);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottom.setMargins(20, 0, 20, 18);
        root.addView(overlay, bottom);
        menu = new TextView(this);
        menu.setTag("settings-menu");
        menu.setTextColor(Color.WHITE);
        menu.setTextSize(17);
        menu.setPadding(20, 16, 20, 16);
        menu.setBackgroundColor(0xF5101827);
        menuScroll = new ScrollView(this);
        menuScroll.setFillViewport(false);
        menuScroll.setFocusable(false);
        menuScroll.addView(menu);
        menuScroll.setVisibility(View.GONE);
        menu.setVisibility(View.GONE);
        FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(
                (int)(getResources().getDisplayMetrics().widthPixels * .90f),
                (int)(getResources().getDisplayMetrics().heightPixels * .82f), Gravity.CENTER);
        root.addView(menuScroll, panel);
        setContentView(root);
        if (state != null) {
            currentUrl = state.getString("url");
            savedPosition = state.getLong("position", 0);
            wantPlay = state.getBoolean("playing", true);
            String uri = state.getString("externalUri");
            if (uri != null) externalSubtitle = new SubtitleSpec(uri, state.getString("externalLabel", "外挂字幕"),
                    state.getString("externalMime", "application/x-subrip"));
        }
        String path = getPreferences(MODE_PRIVATE).getString("localSubtitlePath", null);
        if (path != null && new File(path).exists()) localSubtitle = new SubtitleSpec(Uri.fromFile(new File(path)).toString(),
                getPreferences(MODE_PRIVATE).getString("localSubtitleLabel", "本地字幕"), SubtitleSpec.mimeForName(path));
        fullscreen();
    }

    @SuppressWarnings("deprecation") @Override protected void onStart() {
        super.onStart();
        active = true;
        audioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        try {
            engine = new LibVLC(this, new ArrayList<>(Arrays.asList(
                    "--aout=opensles", "--stats", "--no-video-title-show",
                    "--subsdec-encoding=UTF-8", "--sub-language=zh,chi,zho")));
            loadSource(false);
        } catch (RuntimeException | UnsatisfiedLinkError error) {
            show("兼容内核启动失败，请尝试对应 CPU 的 ARM32 / ARM64 安装包", false);
        }
        main.postDelayed(ticker, 1000);
    }

    private void loadSource(boolean restart) {
        if (!active || engine == null) return;
        main.removeCallbacks(retry);
        if (sourceRequest != null) sourceRequest.cancel(true);
        final int ticket = ++generation;
        show("正在读取远程播放地址…\n" + HELP, false);
        sourceRequest = network.submit(() -> {
            try {
                String url = RemoteSource.fetch();
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    long position = !restart && url.equals(currentUrl) ? position() : 0;
                    if (!url.equals(currentUrl)) desiredAudio = Integer.MIN_VALUE;
                    if (restart) wantPlay = true;
                    playResolved(url, position);
                });
            } catch (IOException error) {
                main.post(() -> {
                    if (!active || ticket != generation) return;
                    String message = error.getMessage();
                    if (message == null || !(message.startsWith("读取 movie.txt") || message.startsWith("movie.txt")
                            || message.startsWith("文本"))) message = "无法连接文本服务器，请检查网络";
                    scheduleRetry(message);
                });
            }
        });
    }

    // The same path is exercised with local HEVC/E-AC-3 fixtures in instrumentation tests.
    void playResolved(String uri, long position) {
        generation++;
        if (sourceRequest != null) sourceRequest.cancel(true);
        main.removeCallbacks(retry);
        currentUrl = uri;
        startPlayback(position);
    }

    private void startPlayback(long position) {
        if (!active || engine == null || currentUrl == null) return;
        if (probeRequest != null) probeRequest.cancel(true);
        probeStarted = false; connectionStatus = "连接检测：尚未执行";
        openedAt = SystemClock.elapsedRealtime();
        stopPlayer();
        if (video != null) root.removeView(video);
        video = new VLCVideoLayout(this);
        root.addView(video, 0, new FrameLayout.LayoutParams(-1, -1));
        player = new MediaPlayer(engine);
        player.setAudioOutput("opensles"); // Decoded PCM; no E-AC-3 HDMI passthrough dependency.
        player.attachViews(video, null, true, mode == 2);
        player.setEventListener(this::onPlayerEvent);
        media = new Media(engine, Uri.parse(currentUrl));
        media.addOption(":network-caching=" + CACHE_SECONDS[cacheIndex] * 1000);
        if (mode == 3) {
            // No ",all": make unsupported hardware visible instead of silently
            // decoding a 4K Main10 stream on an old TV's CPU.
            media.addOption(":codec=" + (android.os.Build.VERSION.SDK_INT >= 21 ? "mediacodec_ndk" : "mediacodec_jni"));
        } else media.setHWDecoderEnabled(mode == 0, false);
        media.addOption(":sub-language=zh,chi,zho");
        if (desiredAudio != Integer.MIN_VALUE) media.addOption(":audio-track-id=" + desiredAudio);
        player.setMedia(media);
        pendingSeek = Math.max(0, position);
        savedPosition = pendingSeek;
        sampleAt = previousRead = previousFrames = 0;
        buffering = 0; downloadMbps = displayedFps = 0;
        externalAdded = false;
        appliedPause = false;
        player.setVolume(100);
        player.play();
        show("正在准备视频（" + decoderName() + "）…\n" + HELP, false);
    }

    @SuppressWarnings("deprecation") private void onPlayerEvent(MediaPlayer.Event event) {
        if (!active || player == null) return;
        if (event.type == MediaPlayer.Event.Playing) {
            failures = 0;
            main.removeCallbacks(retry);
            if (audioManager != null) audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            applySeek();
            if (!externalAdded && externalSubtitle != null) {
                externalAdded = true;
                boolean added = player.addSlave(IMedia.Slave.Type.Subtitle, Uri.parse(externalSubtitle.uri), true);
                if (!added) subtitleStatus = "外挂字幕添加失败，请检查文件地址";
            }
            if (!wantPlay && !appliedPause) { appliedPause = true; player.pause(); }
            status = wantPlay ? "正在播放" : "已暂停";
            show(status + "（" + decoderName() + "）\n" + HELP, wantPlay);
        } else if (event.type == MediaPlayer.Event.SeekableChanged) {
            applySeek();
        } else if (event.type == MediaPlayer.Event.Buffering) {
            buffering = event.getBuffering();
            status = buffering < 100 ? "正在缓冲 " + (int)buffering + "%" : wantPlay ? "正在播放" : "已暂停";
            show(status + "（" + decoderName() + "，缓存 " + CACHE_SECONDS[cacheIndex] + " 秒）\n" + HELP, buffering >= 100 && wantPlay);
        } else if (event.type == MediaPlayer.Event.EncounteredError) {
            scheduleRetry(mode == 3 ? "硬解播放失败：请检查片源访问，或改用自动 / 软件解码" : "播放失败；请检查直链访问或切换解码方式");
        } else if (event.type == MediaPlayer.Event.EndReached) {
            status = "播放结束";
            show("播放结束；按 ↑ 可重新读取视频\n" + HELP, false);
        } else if (event.type == MediaPlayer.Event.ESAdded || event.type == MediaPlayer.Event.ESSelected) {
            if (desiredSubtitle != Integer.MIN_VALUE && player.getSpuTrack() != desiredSubtitle) player.setSpuTrack(desiredSubtitle);
            if (menuOpen) renderMenu();
        }
    }

    private void applySeek() {
        if (pendingSeek > 0 && player != null && player.isSeekable()) {
            player.setTime(pendingSeek);
            pendingSeek = 0;
        }
    }

    private long position() {
        return player != null && player.getTime() >= 0 ? player.getTime() : savedPosition;
    }

    IMedia.Stats playbackStats() { return media == null ? null : media.getStats(); }
    int selectedSubtitle() { return player == null ? -1 : player.getSpuTrack(); }

    void setExternalSubtitle(SubtitleSpec value) {
        externalSubtitle = value; textSelection = 1; desiredSubtitle = Integer.MIN_VALUE;
        if (active && currentUrl != null) startPlayback(position());
    }

    void setDecoderMode(int value) {
        mode = value;
        getPreferences(MODE_PRIVATE).edit().putInt("decoderMode", mode).apply();
        if (currentUrl != null) startPlayback(position());
    }

    private String decoderName() {
        if (mode == 2) return "软解 + Texture 画面输出";
        if (mode == 3) return "仅硬件解码（4K 优先尝试）";
        if (mode == 1) return "软件解码（4K 可能卡顿）";
        return "自动（硬解优先）";
    }

    private List<MediaPlayer.TrackDescription> tracks(boolean text) {
        List<MediaPlayer.TrackDescription> result = new ArrayList<>();
        if (player != null) {
            MediaPlayer.TrackDescription[] available = text ? player.getSpuTracks() : player.getAudioTracks();
            if (available != null) for (MediaPlayer.TrackDescription track : available) if (track.id >= 0) result.add(track);
        }
        return result;
    }

    private String trackName(boolean text) {
        if (text && textSelection == 0) return "关闭";
        int id = player == null ? -1 : text ? player.getSpuTrack() : player.getAudioTrack();
        for (MediaPlayer.TrackDescription track : tracks(text)) if (track.id == id) return
                ((text && textSelection == 1) || (!text && desiredAudio == Integer.MIN_VALUE) ? "自动 / " : "") + compact(track.name);
        return "自动";
    }

    private static String compact(String value) {
        if (value == null) return "未命名";
        return value.length() > 42 ? value.substring(0, 39) + "…" : value;
    }

    private void openMenu() {
        menuOpen = true; menuRow = 0;
        menu.setVisibility(View.VISIBLE); menuScroll.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
        renderMenu(); menuScroll.scrollTo(0, 0);
        if (!listLoaded) loadSubtitleList();
    }

    private void closeMenu() {
        menuOpen = false;
        menuScroll.setVisibility(View.GONE); menu.setVisibility(View.GONE);
        show(status + "\n" + HELP, wantPlay);
    }

    private void renderMenu() {
        String[] rows = {
            "字幕轨道：‹ " + trackName(true) + " ›（" + tracks(true).size() + " 条）",
            "音轨：‹ " + trackName(false) + " ›（" + tracks(false).size() + " 条）",
            "外挂字幕：‹ " + (externalSubtitle == null ? "不加载" : compact(externalSubtitle.label)) + " ›",
            "解码方式：‹ " + decoderName() + " ›",
            "网络缓存：‹ " + CACHE_SECONDS[cacheIndex] + " 秒 ›（切换后重新缓冲）",
            "读取 / 刷新远程字幕列表 →",
            "选择电视或 U 盘字幕文件 →",
            "重新读取视频 / 从头播放 →",
            "检测片源连接（电视 0% 时使用） →",
            "返回播放 ← / →"
        };
        SpannableStringBuilder text = new SpannableStringBuilder("字幕、音轨与解码\n↑ ↓ 选项目，← → 调整或执行\n\n");
        for (int i = 0; i < ROWS; i++) {
            int start = text.length();
            text.append(i == menuRow ? "▶ " : "   ").append(rows[i]).append('\n');
            if (i == menuRow) text.setSpan(new BackgroundColorSpan(0xFF155E75), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        IMedia.Stats stats = playbackStats();
        text.append('\n').append(compact(subtitleStatus));
        text.append("\n1.2.1 · LibVLC 3.7.7 · Android ").append(android.os.Build.VERSION.RELEASE);
        if (stats != null) text.append("\n视频解码 / 显示：").append(String.valueOf(stats.decodedVideo)).append(" / ")
                .append(String.valueOf(stats.displayedPictures)).append("  音频解码 / 输出：")
                .append(String.valueOf(stats.decodedAudio)).append(" / ").append(String.valueOf(stats.playedAbuffers));
        text.append(String.format(Locale.ROOT, "\n缓冲 %.0f%% · 接收 %.1f Mbps · 显示 %.1f 帧/秒", buffering, downloadMbps, displayedFps));
        if (stats != null) text.append(" · 丢帧 ").append(String.valueOf(stats.lostPictures));
        text.append("\n系统 HTTP 检测：").append(connectionStatus);
        text.append("\n4K 卡顿先试仅硬解；缓存只能缓解网络波动，不能提高解码能力。");
        menu.setText(text);
    }

    private void adjustMenu(int direction) {
        if (menuRow == 0 || menuRow == 1) {
            boolean text = menuRow == 0;
            List<MediaPlayer.TrackDescription> choices = tracks(text);
            int offset = text ? 2 : 1;
            int selection = text ? Math.min(textSelection, choices.size() + 1) : 0;
            if (!text && desiredAudio != Integer.MIN_VALUE) for (int i = 0; i < choices.size(); i++) if (choices.get(i).id == desiredAudio) selection = i + offset;
            selection = (selection + direction + choices.size() + offset) % (choices.size() + offset);
            if (text) {
                textSelection = selection;
                if (player != null) {
                    if (selection == 0) { desiredSubtitle = -1; player.setSpuTrack(-1); }
                    else if (selection >= 2) { desiredSubtitle = choices.get(selection - 2).id; player.setSpuTrack(desiredSubtitle); }
                    else if (!choices.isEmpty()) {
                        desiredSubtitle = Integer.MIN_VALUE;
                        MediaPlayer.TrackDescription preferred = choices.get(0);
                        for (MediaPlayer.TrackDescription track : choices) {
                            String name = track.name == null ? "" : track.name.toLowerCase(Locale.ROOT);
                            if (name.contains("simplified") || name.contains("简体") || name.contains("chinese")) { preferred = track; break; }
                        }
                        player.setSpuTrack(preferred.id);
                    }
                }
            } else {
                desiredAudio = selection == 0 ? Integer.MIN_VALUE : choices.get(selection - 1).id;
                if (player != null && !choices.isEmpty()) player.setAudioTrack(selection == 0 ? choices.get(0).id : desiredAudio);
            }
        } else if (menuRow == 2) {
            List<SubtitleSpec> choices = new ArrayList<>(remoteSubtitles);
            if (localSubtitle != null) choices.add(localSubtitle);
            int index = 0;
            for (int i = 0; i < choices.size(); i++) if (externalSubtitle != null && choices.get(i).uri.equals(externalSubtitle.uri)) index = i + 1;
            index = (index + direction + choices.size() + 1) % (choices.size() + 1);
            SubtitleSpec selected = index == 0 ? null : choices.get(index - 1);
            if (choices.isEmpty()) subtitleStatus = "请先读取字幕列表或选择本地文件";
            else setExternalSubtitle(selected);
        } else if (menuRow == 3) {
            setDecoderMode((mode + direction + 4) % 4);
        } else if (menuRow == 4) {
            cacheIndex = (cacheIndex + direction + CACHE_SECONDS.length) % CACHE_SECONDS.length;
            getPreferences(MODE_PRIVATE).edit().putInt("cacheIndex", cacheIndex).apply();
            if (currentUrl != null) startPlayback(position());
        } else if (menuRow == 5) {
            loadSubtitleList();
        } else if (menuRow == 6) {
            chooseLocalSubtitle();
        } else if (menuRow == 7) {
            failures = 0; closeMenu(); loadSource(true);
        } else if (menuRow == 8) {
            probeConnection();
        } else closeMenu();
        if (menuOpen) renderMenu();
    }

    private void probeConnection() {
        if (!active || currentUrl == null) { connectionStatus = "请先成功读取 movie.txt"; return; }
        if (probeRequest != null && !probeRequest.isDone()) return;
        probeStarted = true;
        connectionStatus = "检测中…（使用系统网络栈，与播放内核可能不同）";
        final String address = currentUrl;
        final Media inspected = media;
        probeRequest = network.submit(() -> {
            String result = SourceProbe.check(address);
            main.post(() -> {
                if (!active || inspected != media) return;
                connectionStatus = result;
                if (menuOpen) renderMenu();
                else if (buffering < 1) show(result + "\n按 ↑ 打开设置查看检测结果", false);
            });
        });
    }

    private void loadSubtitleList() {
        if (!active) return;
        if (subtitleRequest != null) subtitleRequest.cancel(true);
        final int ticket = ++listGeneration;
        subtitleStatus = "正在读取 subtitle.txt…";
        renderMenu();
        subtitleRequest = subtitles.submit(() -> {
            try {
                List<SubtitleSpec> found = SubtitleSpec.parseList(RemoteSource.fetchText(SubtitleSpec.LIST_URL));
                main.post(() -> {
                    if (!active || ticket != listGeneration) return;
                    remoteSubtitles.clear(); remoteSubtitles.addAll(found); listLoaded = true;
                    subtitleStatus = "已读取 " + found.size() + " 条外挂字幕，在外挂字幕行切换";
                    if (menuOpen) renderMenu();
                });
            } catch (IOException error) {
                main.post(() -> {
                    if (!active || ticket != listGeneration) return;
                    listLoaded = true; subtitleStatus = "没有远程字幕列表；仍可选内嵌或本地字幕";
                    if (menuOpen) renderMenu();
                });
            }
        });
    }

    @SuppressWarnings("deprecation") private void chooseLocalSubtitle() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(intent, 1001); }
        catch (ActivityNotFoundException error) { subtitleStatus = "电视没有文件选择器，请使用 subtitle.txt"; renderMenu(); }
    }

    @SuppressWarnings("deprecation") @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 1001 || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        String name = "local.srt";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null) name = cursor.getString(0);
        } catch (RuntimeException ignored) { }
        final String label = name;
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.matches(".*\\.(srt|vtt|ass|ssa|ttml|xml)$")) { subtitleStatus = "请选择 SRT、VTT、ASS/SSA 或 TTML 字幕"; return; }
        final String extension = lower.substring(lower.lastIndexOf('.'));
        subtitles.submit(() -> {
            File destination = new File(getFilesDir(), "local-subtitle" + extension);
            try (InputStream input = getContentResolver().openInputStream(uri); FileOutputStream output = new FileOutputStream(destination)) {
                if (input == null) throw new IOException("empty input");
                byte[] buffer = new byte[8192]; int count, total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count; if (total > 8 * 1024 * 1024) throw new IOException("too large");
                    output.write(buffer, 0, count);
                }
                main.post(() -> {
                    if (isFinishing()) return;
                    localSubtitle = new SubtitleSpec(Uri.fromFile(destination).toString(), label, SubtitleSpec.mimeForName(label));
                    getPreferences(MODE_PRIVATE).edit().putString("localSubtitlePath", destination.getAbsolutePath()).putString("localSubtitleLabel", label).apply();
                    subtitleStatus = "已添加 " + compact(label);
                    setExternalSubtitle(localSubtitle);
                    if (menuOpen) renderMenu();
                });
            } catch (IOException | SecurityException error) {
                main.post(() -> { subtitleStatus = "无法读取字幕文件（最大 8 MB），请重新选择"; if (menuOpen) renderMenu(); });
            }
        });
    }

    private void scheduleRetry(String message) {
        status = message;
        int seconds = Math.min(30, 5 << Math.min(failures++, 3));
        show(message + "\n" + seconds + " 秒后重试；↑ 可切换解码方式", false);
        main.removeCallbacks(retry); main.postDelayed(retry, seconds * 1000L);
    }

    private void show(String message, boolean temporary) {
        main.removeCallbacks(hideOverlay);
        overlay.setText(message);
        overlay.setVisibility(menuOpen ? View.GONE : View.VISIBLE);
        if (temporary) main.postDelayed(hideOverlay, 3500);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (menuOpen && key == KeyEvent.KEYCODE_BACK) { if (event.getAction() == KeyEvent.ACTION_DOWN) closeMenu(); return true; }
        boolean handled = key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_LEFT
                || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if (!handled) return super.dispatchKeyEvent(event);
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (menuOpen) {
            if (event.getRepeatCount() > 0) return true;
            if (key == KeyEvent.KEYCODE_DPAD_UP) menuRow = (menuRow + ROWS - 1) % ROWS;
            else if (key == KeyEvent.KEYCODE_DPAD_DOWN) menuRow = (menuRow + 1) % ROWS;
            else adjustMenu(key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            if (menuOpen) {
                renderMenu();
                menu.post(() -> { if (menuOpen && menu.getLayout() != null) menuScroll.smoothScrollTo(0, Math.max(0, menu.getLayout().getLineTop(menuRow + 3) - menuScroll.getHeight()/2)); });
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_UP && event.getRepeatCount() == 0) {
            openMenu();
        } else if ((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && player != null) {
            if (player.isSeekable()) {
                long target = Math.max(0, position() + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -10000 : 10000));
                if (player.getLength() > 0) target = Math.min(target, player.getLength());
                player.setTime(target);
                show("播放进度：" + target / 1000 + " 秒\n" + HELP, true);
            } else show("当前视频尚未准备好或不支持快进快退", true);
        } else if (event.getRepeatCount() == 0 && key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (player == null) loadSource(true);
            else {
                wantPlay = !wantPlay;
                if (wantPlay) player.play(); else player.pause();
                status = wantPlay ? "继续播放" : "已暂停";
                show(status + "\n" + HELP, wantPlay);
            }
        }
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("url", currentUrl); state.putLong("position", position()); state.putBoolean("playing", wantPlay);
        if (externalSubtitle != null) {
            state.putString("externalUri", externalSubtitle.uri); state.putString("externalLabel", externalSubtitle.label); state.putString("externalMime", externalSubtitle.mime);
        }
    }

    private void stopPlayer() {
        if (player != null) {
            player.setEventListener(null); player.stop(); player.detachViews(); player.release(); player = null;
        }
        if (media != null) { media.release(); media = null; }
    }

    @SuppressWarnings("deprecation") @Override protected void onStop() {
        savedPosition = position(); active = false; generation++; listGeneration++;
        main.removeCallbacksAndMessages(null);
        if (sourceRequest != null) sourceRequest.cancel(true);
        if (subtitleRequest != null) subtitleRequest.cancel(true);
        if (probeRequest != null) probeRequest.cancel(true);
        stopPlayer();
        if (engine != null) { engine.release(); engine = null; }
        if (audioManager != null) audioManager.abandonAudioFocus(focusListener);
        super.onStop();
    }

    @Override protected void onDestroy() { network.shutdownNow(); subtitles.shutdownNow(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (focused) fullscreen(); }
    @SuppressWarnings("deprecation") private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
