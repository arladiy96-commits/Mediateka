package com.serialtransfer.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** Viewer-only settings and formatting; there is no source-media scanning here. */
final class MediaLibrary {
    static final String DEST_TREE = "destination_tree";
    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("mediateka_settings", Context.MODE_PRIVATE);
    }
    static String mime(String file) {
        String name = file.toLowerCase(Locale.ROOT);
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".avi")) return "video/x-msvideo";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".ts")) return "video/mp2t";
        return "video/mp4";
    }
    static String readableSize(long bytes) {
        if (bytes < 0) return "Размер неизвестен";
        if (bytes < 1048576L) return String.format(Locale.US,"%.0f КБ",bytes / 1024.0);
        if (bytes < 1073741824L) return String.format(Locale.US,"%.1f МБ",bytes / 1048576.0);
        return String.format(Locale.US,"%.2f ГБ",bytes / 1073741824.0);
    }
}
