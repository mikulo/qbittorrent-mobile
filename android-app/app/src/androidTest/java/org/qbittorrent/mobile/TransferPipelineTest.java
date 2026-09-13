package org.qbittorrent.mobile;

import android.test.ActivityInstrumentationTestCase2;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TcpEndpoint;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.settings_pack;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;

/** Real loopback BT transfer. No private torrent, public tracker or external seed is used. */
@SuppressWarnings("deprecation")
public final class TransferPipelineTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public TransferPipelineTest() { super(MainActivity.class); }

    public void testNativeTransferPublishesFreshProgressWhileDetailsWorkerIsBlocked() throws Exception {
        TorrentEngine engine = TorrentEngine.get(getActivity());
        waitUntil(() -> engine.isRunning(), 15000, "engine start");
        File root = new File(getActivity().getCacheDir(), "pipeline-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        String name = "qbm-loopback-" + UUID.randomUUID() + ".bin";
        File downloaded = new File(engine.downloadDirectory(), name);
        assertFalse(downloaded.exists());
        File metainfo = createFixture(root, name);
        SessionManager seeder = new SessionManager(false);
        SessionManager receiver = new SessionManager(false);
        String hash = null;
        java.util.concurrent.CountDownLatch releaseDetails = new java.util.concurrent.CountDownLatch(1);
        try {
            SettingsPack pack = SettingsPack.defaultSettings();
            pack.listenInterfaces("127.0.0.1:0");
            pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), false);
            pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false);
            pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false);
            pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false);
            SessionParams config = new SessionParams(pack);
            config.setPosixDiskIO();
            seeder.start(config);
            AddTorrentParams seed = load(metainfo);
            seed.setSavePath(root.getAbsolutePath());
            seed.setFlags(seed.getFlags().and_(TorrentFlags.PAUSED.inv())
                    .and_(TorrentFlags.AUTO_MANAGED.inv()).or_(TorrentFlags.SEED_MODE)
                    .or_(TorrentFlags.DISABLE_DHT).or_(TorrentFlags.DISABLE_LSD).or_(TorrentFlags.DISABLE_PEX));
            error_code error = new error_code();
            seeder.swig().add_torrent(seed.swig(), error);
            assertFalse("seed add", error.failed());
            waitUntil(() -> seeder.swig().listen_port() > 0, 10000, "seed listener");

            AddTorrentParams download = load(metainfo);
            download.setFlags(download.getFlags().or_(TorrentFlags.DISABLE_DHT)
                    .or_(TorrentFlags.DISABLE_LSD).or_(TorrentFlags.DISABLE_PEX));
            download.setDownloadLimit(1024 * 1024);
            hash = onEngineIO(engine, () -> (String) method("addTorrentParams", AddTorrentParams.class, boolean.class)
                    .invoke(engine, download, false));
            final String taskHash = hash;
            TorrentHandle handle = onEngineIO(engine, () -> (TorrentHandle) method("find", String.class).invoke(engine, taskHash));
            // Match commitDraft/applyTorrentOptions, not only the add-params field.
            handle.setDownloadLimit(1024 * 1024);
            assertEquals(1024 * 1024, handle.getDownloadLimit());
            handle.swig().connect_peer(new TcpEndpoint("127.0.0.1", seeder.swig().listen_port()).swig());
            waitUntil(() -> engine.snapshot(taskHash) != null && engine.snapshot(taskHash).completed > 0,
                    25000, "first received payload");

            // A blocked slow-data executor must not freeze state/stat callbacks or UI caches.
            ExecutorService details = (ExecutorService) field("detailPoller").get(engine);
            java.util.concurrent.CountDownLatch detailsBlocked = new java.util.concurrent.CountDownLatch(1);
            details.execute(() -> {
                detailsBlocked.countDown();
                try { releaseDetails.await(15, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(detailsBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            long framesBefore = field("statsFrameCount").getLong(engine);
            long start = android.os.SystemClock.elapsedRealtime(), changedAt = start;
            long previous = engine.snapshot(taskHash).completed;
            long maxGap = 0;
            long maxStateAge = 0, maxStatsAge = 0;
            int changes = 0, positiveRates = 0, visibleChanges = 0;
            String previousText = "";
            while (android.os.SystemClock.elapsedRealtime() - start < 10000) {
                TorrentSnapshot snapshot = engine.snapshot(taskHash);
                assertNotNull("unchanged/empty deltas must retain the task", snapshot);
                assertTrue("fixture must still be downloading throughout freshness observation", snapshot.completed < snapshot.total);
                long observedAt = android.os.SystemClock.elapsedRealtime();
                maxStateAge = Math.max(maxStateAge, observedAt - field("lastStateAt").getLong(engine));
                maxStatsAge = Math.max(maxStatsAge, observedAt - field("lastStatsAt").getLong(engine));
                if (snapshot.completed != previous) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    maxGap = Math.max(maxGap, now - changedAt);
                    changedAt = now;
                    previous = snapshot.completed;
                    changes++;
                }
                if (snapshot.downloadRate > 0) positiveRates++;
                java.util.concurrent.atomic.AtomicReference<String> visibleText = new java.util.concurrent.atomic.AtomicReference<>("");
                getInstrumentation().runOnMainSync(() -> {
                    android.widget.TextView counter = getActivity().findViewById(R.id.percent);
                    if (counter != null) visibleText.set(counter.getText().toString());
                });
                if (!visibleText.get().isEmpty() && !previousText.equals(visibleText.get())) {
                    visibleChanges++;
                    previousText = visibleText.get();
                }
                Thread.sleep(100);
            }
            maxGap = Math.max(maxGap, android.os.SystemClock.elapsedRealtime() - changedAt);
            long newFrames = field("statsFrameCount").getLong(engine) - framesBefore;
            AppLog.info("qa_loopback changes=" + changes + " maxProgressGapMs=" + maxGap + " statsFrames=" + newFrames
                    + " visibleChanges=" + visibleChanges + " completed=" + engine.snapshot(taskHash).completed
                    + " total=" + engine.snapshot(taskHash).total + " maxStateAgeMs=" + maxStateAge
                    + " maxStatsAgeMs=" + maxStatsAge);
            assertTrue("progress changes=" + changes, changes >= 8);
            // A rate-limited peer can legitimately deliver in bursts. Bound SAMPLE age,
            // not the time between different byte totals; keep maxGap as a diagnostic.
            assertTrue("max state age=" + maxStateAge, maxStateAge < 1500);
            assertTrue("max stats age=" + maxStatsAge, maxStatsAge < 1500);
            assertTrue("fresh stats frames=" + newFrames, newFrames >= 8);
            assertTrue("actual transfer rates", positiveRates > 0);
            assertTrue("visible task card counter changes=" + visibleChanges, visibleChanges >= 5);
            releaseDetails.countDown();
            engine.watchDetails(taskHash);
            try { waitUntil(() -> !engine.files(taskHash).isEmpty(), 10000, "file details"); }
            finally { engine.unwatchDetails(taskHash); }

            // A second local peer downloads from the APP, verifying actual payload upload.
            // Disconnect the seed first: libtorrent normally rejects a second peer with
            // the same IP, and both synthetic peers deliberately use 127.0.0.1.
            seeder.stop();
            waitUntil(() -> engine.snapshot(taskHash).peers == 0, 10000, "seed disconnected before upload phase");
            File received = new File(root, "receiver");
            assertTrue(received.mkdir());
            SessionParams receiverConfig = new SessionParams(pack);
            receiverConfig.setPosixDiskIO();
            receiver.start(receiverConfig);
            AddTorrentParams receive = load(metainfo);
            receive.setSavePath(received.getAbsolutePath());
            receive.setFlags(receive.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv())
                    .or_(TorrentFlags.DISABLE_DHT).or_(TorrentFlags.DISABLE_LSD).or_(TorrentFlags.DISABLE_PEX));
            error_code receiveError = new error_code();
            TorrentHandle receiverHandle = new TorrentHandle(receiver.swig().add_torrent(receive.swig(), receiveError));
            assertFalse("receiver add", receiveError.failed());
            waitUntil(() -> receiver.swig().listen_port() > 0, 10000, "receiver listener");
            long uploadedBefore = engine.snapshot(taskHash).uploaded;
            handle.swig().connect_peer(new TcpEndpoint("127.0.0.1", receiver.swig().listen_port()).swig());
            waitUntil(() -> engine.snapshot(taskHash).uploaded > uploadedBefore
                            && engine.snapshot(taskHash).uploadRate > 0 && engine.uploadRate() > 0,
                    25000, "actual payload upload reflected in task and aggregate rates");
            AppLog.info("qa_loopback_upload uploadedDelta=" + (engine.snapshot(taskHash).uploaded - uploadedBefore)
                    + " taskBps=" + engine.snapshot(taskHash).uploadRate + " aggregateBps=" + engine.uploadRate());
            engine.pause(taskHash);
            waitUntil(() -> engine.snapshot(taskHash).group == TorrentSnapshot.Group.PAUSED, 10000, "pause state");
            Thread.sleep(1500);
            assertEquals(0, engine.snapshot(taskHash).downloadRate);
            assertEquals(0, engine.snapshot(taskHash).uploadRate);
        } finally {
            releaseDetails.countDown();
            receiver.stop();
            if (hash != null) {
                engine.remove(hash, true);
                final String removed = hash;
                waitUntil(() -> engine.snapshot(removed) == null, 10000, "removed task cache");
                waitUntil(() -> !downloaded.exists(), 10000, "removed test payload");
            }
            seeder.stop();
            deleteFixture(root);
        }
    }

    private static File createFixture(File root, String name) throws Exception {
        int pieceSize = 256 * 1024, pieces = 512;
        byte[] piece = new byte[pieceSize];
        new Random(5137).nextBytes(piece);
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(piece);
        try (FileOutputStream data = new FileOutputStream(new File(root, name))) {
            for (int i = 0; i < pieces; i++) data.write(piece);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        String prefix = "d4:infod6:lengthi" + ((long) pieceSize * pieces) + "e4:name" + name.length() + ":" + name
                + "12:piece lengthi" + pieceSize + "e6:pieces" + (20 * pieces) + ":";
        bytes.write(prefix.getBytes(StandardCharsets.US_ASCII));
        for (int i = 0; i < pieces; i++) bytes.write(digest);
        bytes.write("ee".getBytes(StandardCharsets.US_ASCII));
        File torrent = new File(root, "loopback.torrent");
        try (FileOutputStream output = new FileOutputStream(torrent)) { output.write(bytes.toByteArray()); }
        return torrent;
    }

    private static AddTorrentParams load(File file) {
        error_code error = new error_code();
        AddTorrentParams params = new AddTorrentParams(add_torrent_params.load_torrent_file(file.getAbsolutePath(), error));
        assertFalse("fixture metainfo", error.failed());
        return params;
    }

    private static Field field(String name) throws Exception {
        Field field = TorrentEngine.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = TorrentEngine.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }
    private static <T> T onEngineIO(TorrentEngine engine, java.util.concurrent.Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        ((ExecutorService) field("io").get(engine)).execute(task);
        return task.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    private interface Condition { boolean ready() throws Exception; }
    private static void waitUntil(Condition condition, long timeout, String description) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        while (!condition.ready() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        assertTrue(description, condition.ready());
    }
    private static void deleteFixture(File root) {
        // Only this test's UUID-named cache directory; never a download/workspace root.
        File[] files = root.listFiles();
        if (files != null) for (File file : files) {
            if (file.isDirectory()) deleteFixture(file); else file.delete();
        }
        root.delete();
    }
}
