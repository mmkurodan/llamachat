package com.micklab.llamachat.calendar;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class CalendarDebugLogger {
    private static final String NAME = "llamachat-calendar-debug.txt";
    private static final SimpleDateFormat FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private CalendarDebugLogger() {
    }

    public static void log(Context context, String message) {
        if (context == null || message == null) {
            return;
        }
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File file = new File(dir, NAME);
            String line = FORMAT.format(new Date()) + " | " + message + "\n";
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    public static void logError(Context context, String message, Throwable throwable) {
        log(context, message + (throwable == null ? "" : " | " + throwable));
        if (throwable != null) {
            log(context, Log.getStackTraceString(throwable));
        }
    }

    public static void clear(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File file = new File(dir, NAME);
            if (file.exists() && !file.delete()) {
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file, false)) {
                fos.write(new byte[0]);
            }
        } catch (Exception ignored) {
        }
    }
}
