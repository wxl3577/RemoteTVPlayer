package com.wxl.remotetvplayer;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.assertEquals;

public class RemoteSourceTest {
    @Test public void preservesSignedQueryExactly() throws Exception {
        String url = "https://example.com/download/?fid=A*B&spr=vip%2Ctrycli&token=a+b==&empty=";
        assertEquals(url, RemoteSource.parse("\uFEFF\r\n# source\r\n " + url + "\r\n"));
    }
    @Test public void usesFirstLink() throws Exception {
        assertEquals("https://example.com/first.mp4", RemoteSource.parse(
                "https://example.com/first.mp4\nhttps://example.com/second.mp4"));
    }
    @Test(expected = IOException.class) public void rejectsEmpty() throws Exception { RemoteSource.parse("\n# nothing"); }
    @Test(expected = IOException.class) public void rejectsHtml() throws Exception { RemoteSource.parse("<html>404</html>"); }
    @Test(expected = IOException.class) public void rejectsFileUri() throws Exception { RemoteSource.parse("file:///sdcard/a.mp4"); }
    @Test(expected = IOException.class) public void rejectsBrokenLink() throws Exception { RemoteSource.parse("https://"); }
    @Test(expected = IOException.class) public void rejectsEmbeddedCredentials() throws Exception { RemoteSource.parse("https://u:p@example.com/video"); }
}
