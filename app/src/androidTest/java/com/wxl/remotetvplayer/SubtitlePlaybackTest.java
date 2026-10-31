package com.wxl.remotetvplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.videolan.libvlc.interfaces.IMedia;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

/** Tests decoded HEVC Main10 frames on a real surface and E-AC-3 PCM audio buffers. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 19)
public class SubtitlePlaybackTest {
    @Test public void softwareSurfaceProducesPictureAudioAndSubtitle() throws Exception { verifyPlayback(1); }
    @Test public void softwareTextureProducesPictureAudioAndSubtitle() throws Exception { verifyPlayback(2); }

    private void verifyPlayback(int mode) throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File video = new File(target.getCacheDir(), "compat-hevc10-eac3.mkv");
        File subtitle = new File(target.getCacheDir(), "compat.srt");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("compat-hevc10-eac3.mkv");
                FileOutputStream output = new FileOutputStream(video)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        try (FileOutputStream output = new FileOutputStream(subtitle)) {
            output.write("1\n00:00:00,100 --> 00:00:03,900\nHEVC 10-bit + E-AC-3 字幕测试\n\n".getBytes(Charset.forName("UTF-8")));
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.setRendererMode(mode == 2 ? 2 : 0);
                activity.setDecoderMode(1);
                activity.setExternalSubtitle(new SubtitleSpec(Uri.fromFile(subtitle).toString(), "外挂测试", "application/x-subrip"));
                activity.playResolved(Uri.fromFile(video).toString(), 0);
            });
            AtomicBoolean decoded = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (SystemClock.elapsedRealtime() < deadline && !decoded.get()) {
                scenario.onActivity(activity -> {
                    IMedia.Stats stats = activity.playbackStats();
                    decoded.set(stats != null && stats.decodedVideo > 0 && stats.displayedPictures > 0
                            && stats.decodedAudio > 0 && stats.playedAbuffers > 0 && activity.selectedSubtitle() >= 0);
                });
                if (!decoded.get()) SystemClock.sleep(200);
            }
            assertTrue("HEVC Main10 video / E-AC-3 PCM / external subtitles did not all become active, mode=" + mode, decoded.get());
            // VLC's displayed counter can update before Android's compositor
            // presents a Texture frame. Require a visible frame, polling through
            // that asynchronous hand-off instead of taking one immediate shot.
            int coloredPixels = 0;
            long visibleDeadline = SystemClock.elapsedRealtime() + 4000;
            while (coloredPixels <= 10 && SystemClock.elapsedRealtime() < visibleDeadline) {
                Bitmap screen = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screen);
                coloredPixels = 0;
                for (int y = screen.getHeight()/3; y < screen.getHeight()*2/3; y += 12) {
                    for (int x = screen.getWidth()/3; x < screen.getWidth()*2/3; x += 12) {
                        int color = screen.getPixel(x, y);
                        int max = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
                        int min = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
                        if (max - min > 80 && max > 100) coloredPixels++;
                    }
                }
                screen.recycle();
                if (coloredPixels <= 10) SystemClock.sleep(200);
            }
            assertTrue("No colored test-pattern frame was visible on the screen", coloredPixels > 10);
        }
    }
}
