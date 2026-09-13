package org.qbittorrent.mobile;

import android.test.ActivityInstrumentationTestCase2;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class RefreshLifecycleTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public RefreshLifecycleTest() { super(MainActivity.class); }

    public void testEventBurstAndRepeatedStartStayBounded() throws Throwable {
        getActivity();
        AtomicInteger renders = new AtomicInteger();
        UiRefresh refresh = new UiRefresh(1000, renders::incrementAndGet);
        try {
            runTestOnUiThread(() -> {
                for (int i = 0; i < 10000; i++) { refresh.start(); refresh.request(); }
            });
            getInstrumentation().waitForIdleSync();
            assertEquals(1, renders.get());
            runTestOnUiThread(() -> {
                refresh.stop();
                for (int i = 0; i < 10000; i++) refresh.request();
            });
            Thread.sleep(1200);
            assertEquals(1, renders.get());
            runTestOnUiThread(() -> { refresh.start(); refresh.start(); });
            getInstrumentation().waitForIdleSync();
            assertEquals(2, renders.get());
        } finally { runTestOnUiThread(refresh::stop); }
    }

    public void testRenderingReadsReusePublishedSnapshot() throws Throwable {
        MainActivity activity = getActivity();
        TorrentEngine engine = TorrentEngine.get(activity);
        runTestOnUiThread(() -> {
            // Readers must reuse a published immutable frame, not query native state per call.
            java.util.List<TorrentSnapshot> frame = engine.snapshots();
            for (int i = 0; i < 100; i++) {
                assertSame(frame, engine.snapshots());
                engine.snapshot("missing");
                engine.files("missing");
                engine.downloadRate();
                engine.uploadRate();
            }
        });
    }

    public void testHalfSecondRefreshCadence() throws Throwable {
        getActivity();
        java.util.List<Long> times = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(5);
        UiRefresh refresh = new UiRefresh(500, () -> {
            times.add(android.os.SystemClock.elapsedRealtime());
            done.countDown();
        });
        try {
            runTestOnUiThread(refresh::start);
            assertTrue(done.await(4, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 1; i < 5; i++) {
                long interval = times.get(i) - times.get(i - 1);
                assertTrue("refresh interval=" + interval, interval >= 450 && interval < 800);
            }
        } finally { runTestOnUiThread(refresh::stop); }
    }
}
