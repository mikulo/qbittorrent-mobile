package org.qbittorrent.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded asynchronous UTF-8 logs. No disk access on the render or libtorrent alert thread. */
final class AppLog {
    static final long MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static volatile AppLog instance;
    private final Context context;
    private final File defaultDirectory;
    private volatile File directory;
    private volatile String warning = "";
    private final AtomicInteger dropped = new AtomicInteger();
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> new Thread(task, "app-log"), new ThreadPoolExecutor.AbortPolicy());
    private final SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT);

    private AppLog(Context context) {
        this.context = context.getApplicationContext();
        File external = context.getExternalFilesDir(null);
        defaultDirectory = new File(external == null ? context.getFilesDir() : external, "logs");
        String configured = context.getSharedPreferences("app_logging", 0).getString("directory", "");
        directory = configured.isEmpty() ? defaultDirectory : new File(configured);
    }

    static void initialize(Context context) {
        if (instance != null) return;
        instance = new AppLog(context);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            error("uncaught thread=" + thread.getName(), error);
            awaitIdle(750);
            if (previous != null) previous.uncaughtException(thread, error);
            else { Process.killProcess(Process.myPid()); System.exit(10); }
        });
        info("app_start version=0.3.8 " + android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL + " Android=" + android.os.Build.VERSION.RELEASE);
    }

    static void info(String message) { enqueue("INFO", message); }
    static void warn(String message) { enqueue("WARN", message); }
    static void error(String message, Throwable error) {
        enqueue("ERROR", message + (error == null ? "" : "\n" + Log.getStackTraceString(error)));
    }

    static String redact(String message) {
        if (message == null) return "";
        if (message.length() > 32768) message = message.substring(0, 32768) + " [truncated]";
        String safe = message.replaceAll("(?i)(?:https?|udp|wss?|magnet|content)\\s*:[^\\s<>\"']+", "[link redacted]")
                .replaceAll("(?im)((?:authorization|cookie)\\s*[:=])[^\\r\\n]*", "$1[redacted]")
                .replaceAll("(?i)((?:passkey|token|password|authorization|cookie|secret)\\s*[:=]\\s*)[^\\s,;]+", "$1[redacted]")
                .replaceAll("(?i)\\b[0-9a-f]{32,}\\b", "[id redacted]")
                .replaceAll("/(?:storage|data|sdcard)/[^\\s]+", "[path redacted]");
        return safe.length() > 8192 ? safe.substring(0, 8192) + " [truncated]" : safe;
    }

    private static void enqueue(String level, String message) {
        AppLog log = instance;
        if (log == null) return;
        // Bound both queue length and individual message size before enqueueing.
        String safe = redact(message);
        long time = System.currentTimeMillis();
        String source = Thread.currentThread().getName();
        try { log.writer.execute(() -> log.write(level, time, source, safe)); }
        catch (java.util.concurrent.RejectedExecutionException full) { log.dropped.incrementAndGet(); }
    }

    private void write(String level, long time, String source, String message) {
        int lost = dropped.getAndSet(0);
        String line = timestamp.format(new Date(time)) + " " + level + " pid=" + Process.myPid()
                + " [" + source + "] " + message + (lost > 0 ? " [dropped=" + lost + "]" : "") + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        try { append(directory, bytes); }
        catch (Exception failure) {
            warning = "所选日志目录不可写，已回退到默认目录";
            directory = defaultDirectory;
            try { append(defaultDirectory, (timestamp.format(new Date()) + " WARN log_directory_fallback\n").getBytes(StandardCharsets.UTF_8)); append(defaultDirectory, bytes); }
            catch (Exception ignored) { warning = "日志写入失败，请选择可写目录"; }
        }
    }

    private static void append(File directory, byte[] bytes) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create log directory");
        rotate(directory, bytes.length);
        try (FileOutputStream output = new FileOutputStream(new File(directory, "qbm-current.txt"), true)) {
            output.write(bytes);
        }
    }

    static void rotate(File directory, int incomingBytes) throws Exception {
        File current = new File(directory, "qbm-current.txt");
        if (!current.exists() || current.length() + incomingBytes <= MAX_FILE_BYTES) return;
        // Only these five application-owned file names are managed. Other files stay untouched.
        Files.deleteIfExists(new File(directory, "qbm-4.txt").toPath());
        for (int i = 3; i >= 1; i--) {
            File from = new File(directory, "qbm-" + i + ".txt");
            if (from.exists()) Files.move(from.toPath(), new File(directory, "qbm-" + (i + 1) + ".txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(current.toPath(), new File(directory, "qbm-1.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static String directorySummary() {
        AppLog log = instance;
        return log == null ? "日志尚未初始化" : log.directory.getAbsolutePath()
                + (log.warning.isEmpty() ? "" : "\n" + log.warning);
    }

    static void configureDirectory(File selectedRoot, Consumer<String> completion) {
        AppLog log = instance;
        Handler main = new Handler(Looper.getMainLooper());
        try {
            log.writer.execute(() -> {
                String failure = null;
                try {
                    File target = (selectedRoot == null ? log.defaultDirectory
                            : new File(selectedRoot, "qBittorrent-Mobile-Logs")).getCanonicalFile();
                    if (!target.isDirectory() && !target.mkdirs()) throw new java.io.IOException("无法创建日志目录");
                    File probe = File.createTempFile(".qbm-log-probe-", ".tmp", target);
                    try (FileOutputStream output = new FileOutputStream(probe)) { output.write(1); }
                    finally { Files.deleteIfExists(probe.toPath()); }
                    append(target, "INFO log_directory_selected\n".getBytes(StandardCharsets.UTF_8));
                    log.context.getSharedPreferences("app_logging", 0).edit().putString("directory", target.getAbsolutePath()).apply();
                    log.directory = target;
                    log.warning = "";
                } catch (Exception error) { failure = "无法写入所选日志目录，请检查存储权限"; }
                String result = failure;
                main.post(() -> completion.accept(result));
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            main.post(() -> completion.accept("日志队列繁忙，请稍后重试"));
        }
    }

    static boolean awaitIdle(long timeoutMillis) {
        AppLog log = instance;
        if (log == null || Thread.currentThread().getName().equals("app-log")) return false;
        CountDownLatch done = new CountDownLatch(1);
        try { log.writer.execute(done::countDown); return done.await(timeoutMillis, TimeUnit.MILLISECONDS); }
        catch (Exception ignored) { return false; }
    }
}
