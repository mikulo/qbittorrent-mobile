package org.qbittorrent.mobile;

import android.os.Handler;
import android.os.Looper;

/** One pending refresh per visible consumer, including during an alert burst. */
final class UiRefresh {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable render;
    private final long intervalMillis;
    private boolean active;
    private boolean queued;
    private final Runnable tick = this::run;

    UiRefresh(long intervalMillis, Runnable render) {
        this.intervalMillis = intervalMillis;
        this.render = render;
    }

    synchronized void start() {
        if (active) return;
        active = true;
        request();
    }

    synchronized void stop() {
        active = false;
        queued = false;
        handler.removeCallbacks(tick);
    }

    synchronized void request() {
        if (!active || queued) return;
        queued = true;
        handler.post(tick);
    }

    private synchronized void run() {
        if (!active) return;
        // Keep queued true during render: reentrant events must not enqueue another loop.
        try { render.run(); }
        finally {
            queued = active;
            if (active) handler.postDelayed(tick, intervalMillis);
        }
    }
}
