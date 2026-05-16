package com.micklab.llamachat;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class DebugLogger {
    private static final String NAME = "llamachat-float-debug.txt";
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    static void log(Context ctx, String message) {
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            File file = new File(dir, NAME);
            String line = FORMAT.format(new Date()) + " | " + message + "\n";
            try (FileOutputStream fos = new FileOutputStream(file, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
