package com.wxl.remotetvplayer;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exercises real Media3 subtitle parsing and cue output using tiny, fully local media. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class SubtitlePlaybackTest {
    @Test public void sideloadedSrtProducesVisibleText() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File wav = new File(context.getCacheDir(), "subtitle-test.wav");
        File srt = new File(context.getCacheDir(), "subtitle-test.srt");
        int dataLength = 8000 * 2 * 3;
        ByteBuffer audio = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        audio.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLength);
        audio.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        audio.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000);
        audio.putShort((short) 2).putShort((short) 16);
        audio.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLength);
        try (FileOutputStream output = new FileOutputStream(wav)) { output.write(audio.array()); }
        try (FileOutputStream output = new FileOutputStream(srt)) {
            output.write("1\n00:00:00,200 --> 00:00:02,800\n外挂字幕测试 Subtitle OK\n\n".getBytes(StandardCharsets.UTF_8));
        }
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>("");
        AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                ExoPlayer player = new ExoPlayer.Builder(context).build();
                playerRef.set(player);
                player.setVolume(0);
                player.addListener(new Player.Listener() {
                    @Override public void onCues(CueGroup group) {
                        for (Cue cue : group.cues) {
                            if (cue.text != null && cue.text.toString().contains("Subtitle OK")) {
                                result.set(cue.text.toString());
                                received.countDown();
                            }
                        }
                    }
                    @Override public void onPlayerError(PlaybackException error) {
                        result.set(error.getErrorCodeName());
                        received.countDown();
                    }
                });
                MediaItem.SubtitleConfiguration subtitle = new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(srt))
                        .setId("external").setMimeType("application/x-subrip")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build();
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .setSelectUndeterminedTextLanguage(true).build());
                player.setMediaItem(new MediaItem.Builder().setUri(Uri.fromFile(wav))
                        .setSubtitleConfigurations(Collections.singletonList(subtitle)).build());
                player.prepare();
                player.play();
            });
            assertTrue("Timed out waiting for subtitle cues", received.await(20, TimeUnit.SECONDS));
            assertEquals("外挂字幕测试 Subtitle OK", result.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (playerRef.get() != null) playerRef.get().release();
            });
            wav.delete();
            srt.delete();
        }
    }
}
