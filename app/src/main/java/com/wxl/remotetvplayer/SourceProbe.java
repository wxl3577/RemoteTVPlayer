package com.wxl.remotetvplayer;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/** A bounded diagnostic request; never downloads a full movie or exposes signed URLs. */
final class SourceProbe {
    static String check(String address) {
        try {
            URL url = new URL(address);
            for (int redirects = 0; redirects <= 5; redirects++) {
                if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) return "仅检测 HTTP/HTTPS 片源";
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Range", "bytes=0-65535");
                connection.setRequestProperty("User-Agent", "VLC/3.0.21 LibVLC/3.0.21");
                connection.setRequestProperty("Accept-Encoding", "identity");
                try {
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String target = connection.getHeaderField("Location");
                        if (target == null) return "HTTP 重定向缺少地址";
                        url = new URL(url, target);
                        continue;
                    }
                    if (code == 401 || code == 403) return "HTTP " + code + "：源站拒绝访问，请更新直链或检查访问条件";
                    if (code < 200 || code >= 300) return "HTTP " + code + "：未获得视频数据";
                    try (InputStream input = connection.getInputStream()) {
                        // Reading one byte proves a response body is available, even if
                        // a server ignores Range and responds with a 27 GB Content-Length.
                        if (input.read() < 0) return "HTTP " + code + "：视频响应为空";
                    }
                    return "HTTP " + code + "：已读到片源数据" + (code == 206 ? "，支持分段读取" : "，本次未返回分段响应") + "（不代表解码成功）";
                } finally { connection.disconnect(); }
            }
            return "片源重定向次数过多";
        } catch (UnknownHostException error) { return "DNS 解析失败：检查电视网络 / DNS";
        } catch (SocketTimeoutException error) { return "连接或读取超时：检查电视到片源的网络";
        } catch (SSLException error) { return "TLS 连接失败：检查电视日期、系统证书或 HTTPS 兼容性";
        } catch (IOException | IllegalArgumentException error) { return "连接失败：检查网络与直链有效性"; }
    }
    private SourceProbe() { }
}
