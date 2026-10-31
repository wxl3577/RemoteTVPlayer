package com.wxl.remotetvplayer;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.net.Uri;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.videolan.libvlc.interfaces.IMedia;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 19)
public class StabilityTest {
    @Test public void plays4kBeyondTenSecondsAndReleasesOnMemoryWarningWithoutRetry() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File video = new File(target.getCacheDir(), "stability-4k-main10.mkv");
        try (InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("stability-4k-main10.mkv");
             FileOutputStream output = new FileOutputStream(video)) {
            byte[] bytes = new byte[8192]; int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.setDecoderMode(1);
                activity.playResolved(Uri.fromFile(video).toString(), 0);
            });
            AtomicBoolean decoded = new AtomicBoolean();
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (!decoded.get() && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity(activity -> {
                    IMedia.Stats stats = activity.playbackStats();
                    decoded.set(stats != null && stats.displayedPictures > 0 && stats.playedAbuffers > 0);
                });
                if (!decoded.get()) SystemClock.sleep(200);
            }
            assertTrue("4K Main10 / E-AC-3 output did not start", decoded.get());
            AtomicInteger starts = new AtomicInteger();
            AtomicInteger frames = new AtomicInteger();
            scenario.onActivity(activity -> {
                starts.set(activity.playbackStarts());
                frames.set(activity.playbackStats().displayedPictures);
                activity.setDecoderMode(1); // Selecting the same mode must keep the decoder.
                activity.setExternalSubtitle(null); // Removing subtitles must not reopen video.
            });
            long began = SystemClock.elapsedRealtime();
            long maxNative = 0;
            while (SystemClock.elapsedRealtime() - began < 18000) {
                SystemClock.sleep(1000);
                maxNative = Math.max(maxNative, Debug.getNativeHeapAllocatedSize());
                scenario.onActivity(activity -> {
                    assertEquals("A running 4K stream was reopened", starts.get(), activity.playbackStarts());
                    assertNotNull("Player was released during sustained playback", activity.playbackStats());
                });
            }
            scenario.onActivity(activity -> {
                assertTrue("Frames stopped advancing", activity.playbackStats().displayedPictures > frames.get() + 24);
                activity.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL);
                assertTrue("Decoder was retained after critical memory warning", activity.isSafetyStopped());
                assertNull(activity.playbackStats());
            });
            SystemClock.sleep(7000); // Longer than the previous automatic retry interval.
            scenario.onActivity(activity -> {
                assertEquals("Low-memory stop automatically restarted playback", starts.get(), activity.playbackStarts());
                assertTrue(activity.isSafetyStopped());
            });
            Log.i("StabilityTest", "4K Main10 playback >18s; no reopen; critical-memory release; max observed native heap MiB=" + maxNative / 1048576);
        }
    }

    @Test public void interruptedSessionDoesNotAutoplayOnNextLaunch() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putBoolean("sessionActive", true).commit();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            SystemClock.sleep(2500);
            scenario.onActivity(activity -> {
                assertEquals(0, activity.playbackStarts());
                assertTrue(activity.isSafetyStopped());
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            });
        } finally {
            target.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().putBoolean("sessionActive", false).commit();
        }
    }
}
