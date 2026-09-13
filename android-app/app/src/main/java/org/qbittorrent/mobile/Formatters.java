package org.qbittorrent.mobile;

import java.util.Locale;

final class Formatters {
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB"};

    private Formatters() {}

    static String bytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), value >= 100 || unit == 0 ? "%.0f %s" : "%.1f %s", value, UNITS[unit]);
    }

    /** Precision used for the live downloaded counter on torrent cards/details. */
    static String downloadedBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return String.format(Locale.getDefault(), "%d B", bytes);
        if (bytes < 1024L * 1024) {
            return String.format(Locale.getDefault(), "%.0f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f MiB", bytes / (1024.0 * 1024));
        }
        if (bytes < 1024L * 1024 * 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024.0 * 1024 * 1024));
        }
        return String.format(Locale.getDefault(), "%.2f TiB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    static String speed(long bytesPerSecond) {
        return bytes(bytesPerSecond) + "/s";
    }

    static String duration(long seconds) {
        if (seconds < 0 || seconds > 365L * 24 * 3600) return "∞";
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) return String.format(Locale.getDefault(), "%dh %02dm", hours, minutes);
        return String.format(Locale.getDefault(), "%dm", minutes);
    }
}
