package org.qbittorrent.mobile;

import android.test.ActivityInstrumentationTestCase2;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("deprecation")
public final class AppLogTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public AppLogTest() { super(MainActivity.class); }

    public void testDirectoryTextRedactionAndRotation() throws Exception {
        MainActivity activity = getActivity();
        File root = Files.createTempDirectory(activity.getCacheDir().toPath(), "logging-qa-").toFile();
        try {
            configure(root);
            File logs = new File(root, "qBittorrent-Mobile-Logs").getCanonicalFile();
            assertTrue(AppLog.directorySummary().contains(logs.getAbsolutePath()));
            assertEquals(logs.getAbsolutePath(), activity.getSharedPreferences("app_logging", 0).getString("directory", ""));
            AppLog.info("qa_log_utf8 中文 https://example.invalid/announce?passkey=private-code");
            AppLog.error("qa_exception", new IllegalStateException("Authorization: Bearer private-value"));
            assertTrue(AppLog.awaitIdle(3000));
            File current = new File(logs, "qbm-current.txt");
            String text = new String(Files.readAllBytes(current.toPath()), StandardCharsets.UTF_8);
            assertTrue(text.contains("qa_log_utf8 中文"));
            assertTrue(text.contains("IllegalStateException"));
            assertFalse(text.contains("private-code"));
            assertFalse(text.contains("private-value"));
            assertFalse(text.contains("example.invalid"));
            File rotation = new File(root, "rotation");
            assertTrue(rotation.mkdir());
            File unrelated = new File(rotation, "keep.txt");
            assertTrue(unrelated.createNewFile());
            for (int i = 0; i < 7; i++) {
                try (RandomAccessFile file = new RandomAccessFile(new File(rotation, "qbm-current.txt"), "rw")) {
                    file.setLength(AppLog.MAX_FILE_BYTES);
                }
                AppLog.rotate(rotation, 128);
            }
            assertTrue(unrelated.isFile());
            assertTrue(new File(rotation, "qbm-4.txt").isFile());
            assertFalse(new File(rotation, "qbm-5.txt").exists());
        } finally {
            configure(null);
            deleteTestDirectory(root);
        }
    }

    private void configure(File root) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        String[] failure = {null};
        AppLog.configureDirectory(root, error -> { failure[0] = error; done.countDown(); });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNull(failure[0]);
    }

    private void deleteTestDirectory(File directory) throws Exception {
        File[] children = directory.listFiles();
        if (children != null) for (File file : children) {
            if (file.isDirectory()) deleteTestDirectory(file); else Files.delete(file.toPath());
        }
        Files.delete(directory.toPath());
    }
}
