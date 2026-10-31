package com.wxl.remotetvplayer;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

public class SourceProbeTest {
    @Test public void reportsRejectionWithoutLeakingSignedUrl() throws Exception {
        String result = serve("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
        assertTrue(result.contains("403"));
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("http://"));
    }

    @Test public void boundsReadWhenServerIgnoresRangeAndAdvertisesHugeFile() throws Exception {
        // Only one byte is sent, although the declared body is 27 GB. An accidental
        // read-all would wait for the rest or throw instead of reporting success.
        assertTrue(serve("HTTP/1.1 200 OK\r\nContent-Length: 27117028268\r\nConnection: close\r\n\r\nx").contains("已读到片源数据"));
    }

    @Test public void recognizesPartialContent() throws Exception {
        assertTrue(serve("HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\nContent-Range: bytes 0-0/100\r\nConnection: close\r\n\r\nx").contains("支持分段读取"));
    }

    private String serve(String response) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5000);
            FutureTask<String> request = new FutureTask<>(() -> {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = input.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    return headers.toString();
                }
            });
            Thread thread = new Thread(request); thread.setDaemon(true); thread.start();
            String result = SourceProbe.check("http://127.0.0.1:" + server.getLocalPort() + "/video?token=secret");
            assertTrue(request.get().toLowerCase().contains("range: bytes=0-65535"));
            return result;
        }
    }
}
