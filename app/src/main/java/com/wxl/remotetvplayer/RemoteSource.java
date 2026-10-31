package com.wxl.remotetvplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;

/** Reads only a small UTF-8 text file; never decodes or rewrites signed URL query strings. */
public final class RemoteSource {
    public static final String TEXT_URL = "http://82.156.239.39/movie.txt";
    private static final int MAX_BYTES = 64 * 1024;

    private RemoteSource() { }

    public static String parse(String text) throws IOException {
        for (String line : text.replace("\uFEFF", "").split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            try {
                URI uri = URI.create(value);
                if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null) return value;
            } catch (IllegalArgumentException ignored) { }
            throw new IOException("文本格式错误：请在 movie.txt 中填写完整的 HTTP/HTTPS 视频直链");
        }
        throw new IOException("movie.txt 为空，请填写视频直链");
    }

    public static String fetch() throws IOException {
        return parse(fetchText(TEXT_URL));
    }

    public static String fetchText(String textUrl) throws IOException {
        URL url = new URL(textUrl);
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Accept", "text/plain, */*");
            connection.setRequestProperty("User-Agent", "RemoteTVPlayer/1.0");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("文本服务器重定向缺少地址");
                    url = new URL(url, location);
                    if (!"http".equals(url.getProtocol()) && !"https".equals(url.getProtocol())) {
                        throw new IOException("文本服务器重定向协议不支持");
                    }
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("读取 movie.txt 失败：HTTP " + status);
                try (InputStream input = connection.getInputStream();
                        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("读取已取消");
                        if (output.size() + count > MAX_BYTES) throw new IOException("movie.txt 超过 64 KB，请只保留视频直链");
                        output.write(buffer, 0, count);
                    }
                    return new String(output.toByteArray(), Charset.forName("UTF-8"));
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("文本服务器重定向次数过多");
    }
}
