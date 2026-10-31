package com.wxl.remotetvplayer;

import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SubtitleSpecTest {
    @Test public void parsesNamedAndPlainSubtitlesWithoutChangingTokens() throws Exception {
        List<SubtitleSpec> entries = SubtitleSpec.parseList("\uFEFF# subtitles\r\n简体中文|https://example.com/a.srt?token=a+b%2Cc==\r\nhttps://example.com/b.vtt\n");
        assertEquals(2, entries.size());
        assertEquals("简体中文", entries.get(0).label);
        assertEquals("https://example.com/a.srt?token=a+b%2Cc==", entries.get(0).uri);
        assertEquals("application/x-subrip", entries.get(0).mime);
        assertEquals("text/vtt", entries.get(1).mime);
    }
    @Test public void identifiesAssAndTtmlIgnoringQueryAndCase() {
        assertEquals("text/x-ssa", SubtitleSpec.mimeForName("https://example.com/a.ASS?token=x"));
        assertEquals("application/ttml+xml", SubtitleSpec.mimeForName("movie.ttml"));
    }
    @Test public void emptyOptionalListIsAllowed() throws Exception { assertTrue(SubtitleSpec.parseList("# none\n").isEmpty()); }
    @Test(expected = IOException.class) public void rejectsHtmlList() throws Exception { SubtitleSpec.parseList("<html>404</html>"); }
}
