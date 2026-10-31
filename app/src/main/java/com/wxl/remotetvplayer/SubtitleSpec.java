package com.wxl.remotetvplayer;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SubtitleSpec {
    public static final String LIST_URL = "http://82.156.239.39/subtitle.txt";
    public final String uri;
    public final String label;
    public final String mime;

    public SubtitleSpec(String uri, String label, String mime) {
        this.uri = uri;
        this.label = label;
        this.mime = mime;
    }

    public static String mimeForName(String name) {
        String path = name.toLowerCase(Locale.ROOT).split("[?#]", 2)[0];
        if (path.endsWith(".vtt")) return "text/vtt";
        if (path.endsWith(".ass") || path.endsWith(".ssa")) return "text/x-ssa";
        if (path.endsWith(".ttml") || path.endsWith(".xml")) return "application/ttml+xml";
        return "application/x-subrip";
    }

    public static List<SubtitleSpec> parseList(String text) throws IOException {
        List<SubtitleSpec> result = new ArrayList<>();
        for (String line : text.replace("\uFEFF", "").split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            int separator = value.indexOf('|');
            String label = separator >= 0 ? value.substring(0, separator).trim() : "";
            String url = RemoteSource.parse(separator >= 0 ? value.substring(separator + 1).trim() : value);
            if (label.isEmpty()) {
                String path = URI.create(url).getPath();
                label = path.substring(path.lastIndexOf('/') + 1);
                if (label.isEmpty()) label = "外挂字幕 " + (result.size() + 1);
            }
            result.add(new SubtitleSpec(url, label, mimeForName(url)));
            if (result.size() == 32) break;
        }
        return result;
    }
}
