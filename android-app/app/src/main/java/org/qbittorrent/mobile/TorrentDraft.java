package org.qbittorrent.mobile;

import java.util.Collections;
import java.util.List;

/** Immutable metadata shown before a torrent is committed to the session. */
public final class TorrentDraft {
    public final String id;
    public final String name;
    public final String hash;
    public final long totalSize;
    public final boolean privateTorrent;
    public final List<FileItem> files;

    TorrentDraft(String id, String name, String hash, long totalSize,
                 boolean privateTorrent, List<FileItem> files) {
        this.id = id;
        this.name = name;
        this.hash = hash;
        this.totalSize = totalSize;
        this.privateTorrent = privateTorrent;
        this.files = Collections.unmodifiableList(files);
    }

    public static final class FileItem {
        public final String path;
        public final long size;

        FileItem(String path, long size) {
            this.path = path;
            this.size = size;
        }
    }
}
