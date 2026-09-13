package org.qbittorrent.mobile;

/** Counter deltas on the native sample clock, not a second sliding average of rates. */
final class TransferRate {
    private long previousBytes;
    private long previousMillis = -1;
    private long rate;

    long sample(long bytes, long millis) {
        bytes = Math.max(0, bytes);
        if (previousMillis < 0 || bytes < previousBytes || millis < previousMillis) {
            previousBytes = bytes;
            previousMillis = millis;
            return rate = 0;
        }
        if (millis == previousMillis) return rate;
        rate = Math.max(0, Math.round((bytes - previousBytes) * 1000.0 / (millis - previousMillis)));
        previousBytes = bytes;
        previousMillis = millis;
        return rate;
    }
}
