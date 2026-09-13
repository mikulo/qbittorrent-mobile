package org.qbittorrent.mobile;

import java.util.Collections;
import java.util.List;

public final class TorrentSnapshot {
    public enum Group { DOWNLOADING, SEEDING, PAUSED, CHECKING, ERROR }

    public final String hash;
    public final String name;
    public final float progress;
    public final long total;
    public final long completed;
    public final long downloadRate;
    public final long uploadRate;
    public final long uploaded;
    public final int seeds;
    public final int peers;
    public final long etaSeconds;
    public final String state;
    public final String savePath;
    public final Group group;
    public final List<String> trackers;

    TorrentSnapshot(String hash, String name, float progress, long total, long completed,
                    long downloadRate, long uploadRate, long uploaded, int seeds, int peers,
                    long etaSeconds, String state, String savePath, Group group, List<String> trackers) {
        this.hash = hash;
        this.name = name;
        this.progress = progress;
        this.total = total;
        this.completed = completed;
        this.downloadRate = downloadRate;
        this.uploadRate = uploadRate;
        this.uploaded = uploaded;
        this.seeds = seeds;
        this.peers = peers;
        this.etaSeconds = etaSeconds;
        this.state = state;
        this.savePath = savePath;
        this.group = group;
        this.trackers = trackers == null ? Collections.emptyList() : trackers;
    }
}
