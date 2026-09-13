package org.qbittorrent.mobile;

/** Bounds native asynchronous requests even while the native session is busy. */
final class StateRequestGate {
    private long lastSent = -1;
    private boolean pending;

    synchronized boolean request(long now) {
        if (pending || (lastSent >= 0 && now - lastSent < 1000)) return false;
        pending = true;
        lastSent = now;
        return true;
    }

    synchronized long complete(long now) {
        long latency = pending ? Math.max(0, now - lastSent) : 0;
        pending = false;
        return latency;
    }

    synchronized long pendingAge(long now) {
        return pending ? Math.max(0, now - lastSent) : 0;
    }
}
