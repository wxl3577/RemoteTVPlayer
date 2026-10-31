package com.wxl.remotetvplayer;

import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RemoteControlTest {
    @Test public void launchesAndHandlesEveryDirectionWithoutTouch() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                TextView menu = activity.getWindow().getDecorView().findViewWithTag("settings-menu");
                org.junit.Assert.assertEquals(View.VISIBLE, menu.getVisibility());
                org.junit.Assert.assertTrue(menu.getText().toString().contains("字幕轨道"));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                org.junit.Assert.assertTrue(menu.getText().toString().contains("关闭"));
                // Up wraps from the subtitle row to Return; right closes, without an OK key.
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                org.junit.Assert.assertEquals(View.GONE, menu.getVisibility());
                for (int key : new int[] {KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
                    org.junit.Assert.assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
                    org.junit.Assert.assertTrue(activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)));
                }
            });
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED);
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED);
        }
    }
}
