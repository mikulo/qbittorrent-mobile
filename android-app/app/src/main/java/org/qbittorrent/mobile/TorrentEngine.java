package org.qbittorrent.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.AnnounceEntry;
import org.libtorrent4j.FileStorage;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.Priority;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.StatsMetric;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.TrackerAnnounceAlert;
import org.libtorrent4j.alerts.TrackerErrorAlert;
import org.libtorrent4j.alerts.TrackerReplyAlert;
import org.libtorrent4j.alerts.TrackerWarningAlert;
import org.libtorrent4j.alerts.SaveResumeDataAlert;
import org.libtorrent4j.alerts.SaveResumeDataFailedAlert;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.SessionStatsAlert;
import org.libtorrent4j.swig.status_flags_t;
import org.libtorrent4j.swig.torrent_status_vector;
import org.libtorrent4j.swig.remove_flags_t;
import org.libtorrent4j.swig.add_torrent_params;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.session_handle;
import org.libtorrent4j.swig.settings_pack;
import org.libtorrent4j.swig.torrent_flags_t;
import org.libtorrent4j.swig.torrent_handle_vector;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Owns the single libtorrent session used by the app. */
public final class TorrentEngine {
    private static final String TAG = "TorrentEngine";
    public interface Listener {
        void onEngineChanged();
        default void onEngineError(String message) {}
    }

    public interface PrepareCallback {
        void onReady(TorrentDraft draft);
        void onError(String message);
    }

    // Use the current stable desktop identity. Private trackers commonly whitelist this pair.
    public static final String QBT_VERSION = "5.2.3";
    public static final String USER_AGENT = "qBittorrent/" + QBT_VERSION;
    public static final String PEER_FINGERPRINT = "-qB5230-";

    private static final String PREFS = "torrent_engine";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_DOWNLOAD_PATH = "download_path";
    private static final long MAX_TORRENT_FILE_SIZE = 16L * 1024 * 1024;
    private static final long RESUME_SAVE_INTERVAL_SECONDS = 15;
    private static final long SESSION_SAVE_INTERVAL_SECONDS = 60;
    private static volatile TorrentEngine instance;

    private final Context context;
    private final SharedPreferences preferences;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService metadataPoller = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService statePoller = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "torrent-state"));
    private final ScheduledExecutorService detailPoller = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "torrent-details"));
    private final ExecutorService checkpointWriter = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "torrent-checkpoints"));
    private final StateRequestGate stateRequests = new StateRequestGate();
    private final StateRequestGate statsRequests = new StateRequestGate();
    private final Object stateLock = new Object();
    private final Set<String> activeHashes = ConcurrentHashMap.newKeySet();
    private final Map<String, TorrentSnapshot> states = new LinkedHashMap<>();
    private final Map<String, TransferRate[]> torrentRates = new HashMap<>();
    private final TransferRate sessionDownload = new TransferRate(), sessionUpload = new TransferRate();
    private volatile List<TorrentSnapshot> cachedSnapshots = Collections.emptyList();
    private volatile Map<String, DetailState> cachedDetails = Collections.emptyMap();
    private final Set<String> detailWatches = ConcurrentHashMap.newKeySet();
    private volatile long cachedDownloadRate, cachedUploadRate;
    private volatile long lastStateAt, lastStatsAt, stateLatencyMs, statsLatencyMs, stateConvertMs, detailsQueryMs;
    private volatile long stateFrameCount, statsFrameCount;
    private volatile long lastDetailWarning;
    private long lastHealthLog;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, PendingTorrent> pendingTorrents = new ConcurrentHashMap<>();
    private final Set<String> pendingHashes = ConcurrentHashMap.newKeySet();
    private final Set<String> cancelledDrafts = ConcurrentHashMap.newKeySet();
    private final Set<String> resumeRequests = ConcurrentHashMap.newKeySet();
    private final Object resumeSaveMonitor = new Object();
    private final ConcurrentHashMap<String, String> trackerMessages = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private volatile SessionManager manager;
    private volatile boolean starting;
    private volatile boolean resumeTimerStarted;

    private TorrentEngine(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void initialize(Context context) {
        get(context);
    }

    public static TorrentEngine get(Context context) {
        if (instance == null) {
            synchronized (TorrentEngine.class) {
                if (instance == null) instance = new TorrentEngine(context);
            }
        }
        return instance;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isRunning() {
        SessionManager value = manager;
        return value != null && value.isRunning();
    }

    public String backendVersion() {
        try {
            return LibTorrent.version();
        } catch (Throwable ignored) {
            return "2.1";
        }
    }

    public static boolean isSupportedMagnet(String value) {
        if (value == null) return false;
        String magnet = value.trim().toLowerCase(Locale.ROOT);
        return magnet.startsWith("magnet:?")
                && (magnet.contains("xt=urn:btih:") || magnet.contains("xt=urn:btmh:"));
    }

    public static boolean isSupportedTorrentUrl(String value) {
        if (value == null) return false;
        String source = value.trim().toLowerCase(Locale.ROOT);
        return source.startsWith("https://") || source.startsWith("http://");
    }

    public String activeUserAgent() {
        try {
            SettingsPack pack = manager == null ? null : manager.settings();
            return pack == null ? USER_AGENT : pack.getString(settings_pack.string_types.user_agent.swigValue());
        } catch (Throwable ignored) {
            return USER_AGENT;
        }
    }

    public String activePeerFingerprint() {
        try {
            SettingsPack pack = manager == null ? null : manager.settings();
            return pack == null ? PEER_FINGERPRINT : new String(pack.getPeerFingerprint(), StandardCharsets.ISO_8859_1);
        } catch (Throwable ignored) {
            return PEER_FINGERPRINT;
        }
    }

    public File downloadDirectory() {
        String customPath = preferences.getString(KEY_DOWNLOAD_PATH, "").trim();
        File dir;
        if (!customPath.isEmpty()) dir = new File(customPath);
        else {
            dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(context.getFilesDir(), "downloads");
        }
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create download directory");
        if (!dir.isDirectory() || !dir.canWrite()) throw new IllegalStateException("Download directory is not writable: " + dir);
        return dir;
    }

    public void changeDownloadDirectory(File directory) {
        io.execute(() -> {
            try {
                File target = directory.getCanonicalFile();
                if (!target.exists() && !target.mkdirs()) throw new IllegalArgumentException("Cannot create selected directory");
                if (!target.isDirectory() || !target.canWrite()) throw new IllegalArgumentException("Selected directory is not writable");
                File probe = File.createTempFile(".qb-write-test-", null, target);
                if (!probe.delete()) probe.deleteOnExit();
                preferences.edit().putString(KEY_DOWNLOAD_PATH, target.getAbsolutePath()).apply();
                moveTorrentsTo(target);
                notifyChanged();
            } catch (Throwable error) {
                notifyError(error.getMessage() == null ? "Cannot use selected directory" : error.getMessage());
            }
        });
    }

    public void resetDownloadDirectory() {
        preferences.edit().remove(KEY_DOWNLOAD_PATH).apply();
        File target = downloadDirectory();
        io.execute(() -> {
            moveTorrentsTo(target);
            notifyChanged();
        });
    }

    private void moveTorrentsTo(File target) {
        SessionManager value = manager;
        if (value == null || !value.isRunning()) return;
        torrent_handle_vector handles = value.swig().get_torrents();
        for (int i = 0; i < handles.size(); i++) {
            TorrentHandle handle = new TorrentHandle(handles.get(i));
            if (handle.isValid() && !target.getAbsolutePath().equals(handle.savePath())) handle.moveStorage(target.getAbsolutePath());
        }
    }

    public void startAsync() {
        synchronized (lock) {
            if (isRunning() || starting) return;
            starting = true;
        }
        io.execute(() -> {
            try {
                startInternal();
                restoreSources();
                notifyChanged();
            } catch (Throwable error) {
                notifyError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            } finally {
                starting = false;
            }
        });
    }

    private void startInternal() {
        synchronized (lock) {
            if (isRunning()) return;
            SettingsPack pack = SettingsPack.defaultSettings();
            pack.setPeerFingerprint(PEER_FINGERPRINT.getBytes(StandardCharsets.ISO_8859_1));
            pack.setString(settings_pack.string_types.user_agent.swigValue(), USER_AGENT);
            pack.setBoolean(settings_pack.bool_types.always_send_user_agent.swigValue(), true);
            pack.setBoolean(settings_pack.bool_types.listen_system_port_fallback.swigValue(), false);
            pack.setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), false);
            pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), prefBoolean("dht", true));
            pack.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                    "dht.libtorrent.org:25401,router.bittorrent.com:6881,"
                            + "router.utorrent.com:6881,dht.transmissionbt.com:6881");
            pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), prefBoolean("lsd", true));
            pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), prefBoolean("upnp", true));
            pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), prefBoolean("natpmp", true));
            pack.setBoolean(settings_pack.bool_types.anonymous_mode.swigValue(), false);
            // The prebuilt Android OpenSSL used by libtorrent4j has no usable system CA store.
            // With validation enabled every HTTPS tracker fails before announce (BIO init fail).
            // TLS encryption is still used; this only disables certificate authentication.
            pack.setBoolean(settings_pack.bool_types.validate_https_trackers.swigValue(), false);
            pack.setInteger(settings_pack.int_types.out_enc_policy.swigValue(), settings_pack.enc_policy.pe_enabled.swigValue());
            pack.setInteger(settings_pack.int_types.in_enc_policy.swigValue(), settings_pack.enc_policy.pe_enabled.swigValue());
            pack.setInteger(settings_pack.int_types.allowed_enc_level.swigValue(), settings_pack.enc_level.pe_rc4.swigValue());
            pack.setBoolean(settings_pack.bool_types.prefer_rc4.swigValue(), true);
            pack.setInteger(settings_pack.int_types.active_downloads.swigValue(), -1);
            pack.setInteger(settings_pack.int_types.active_seeds.swigValue(), -1);
            pack.setInteger(settings_pack.int_types.active_limit.swigValue(), -1);
            pack.setInteger(settings_pack.int_types.active_tracker_limit.swigValue(), -1);
            pack.setInteger(settings_pack.int_types.active_dht_limit.swigValue(), -1);
            pack.setInteger(settings_pack.int_types.active_lsd_limit.swigValue(), -1);
            configureProxy(pack);
            pack.downloadRateLimit(prefInt("download_limit", 0) * 1024);
            pack.uploadRateLimit(prefInt("upload_limit", 0) * 1024);
            int port = Math.max(1024, Math.min(65535, prefInt("listen_port", 6881)));
            pack.listenInterfaces("0.0.0.0:" + port + ",[::]:" + port);

            SessionParams params = loadSessionParams();
            params.setSettings(pack);
            // Retain Android's working POSIX backend: the bundled mmap backend triggered
            // MediaProvider/FUSE process aborts on API 34 and 37 emulator shared storage.
            // Remove serial status round-trips instead of trading freshness for data-path crashes.
            params.setPosixDiskIO();
            manager = new SessionManager(false) {
                // SessionManager also requests stats itself. Route BOTH callers through gates,
                // so a busy native session cannot accumulate an unbounded request backlog.
                @Override public void postTorrentUpdates() {
                    if (!isRunning() || !stateRequests.request(android.os.SystemClock.elapsedRealtime())) return;
                    status_flags_t flags = TorrentHandle.QUERY_ACCURATE_DOWNLOAD_COUNTERS
                            .or_(TorrentHandle.QUERY_NAME).or_(TorrentHandle.QUERY_SAVE_PATH);
                    try { swig().post_torrent_updates(flags); }
                    catch (RuntimeException error) { stateRequests.complete(android.os.SystemClock.elapsedRealtime()); throw error; }
                    finally { flags.delete(); }
                }
                @Override public void postSessionStats() {
                    if (!isRunning() || !statsRequests.request(android.os.SystemClock.elapsedRealtime())) return;
                    try { super.postSessionStats(); }
                    catch (RuntimeException error) { statsRequests.complete(android.os.SystemClock.elapsedRealtime()); throw error; }
                }
            };
            manager.addListener(new AlertListener() {
                @Override public int[] types() {
                    return new int[] {
                            AlertType.STATE_UPDATE.swig(),
                            AlertType.SESSION_STATS.swig(),
                            AlertType.TRACKER_ANNOUNCE.swig(),
                            AlertType.TRACKER_REPLY.swig(),
                            AlertType.TRACKER_WARNING.swig(),
                            AlertType.TRACKER_ERROR.swig(),
                            AlertType.SAVE_RESUME_DATA.swig(),
                            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
                            AlertType.TORRENT_FINISHED.swig()
                    };
                }

                @Override public void alert(Alert<?> alert) {
                    if (alert instanceof StateUpdateAlert) {
                        acceptStateUpdate((StateUpdateAlert) alert);
                        return;
                    }
                    if (alert instanceof SessionStatsAlert) {
                        acceptSessionStats((SessionStatsAlert) alert);
                        return;
                    }
                    // Copy resume bytes while the alert is alive; never fsync on this thread.
                    if (alert instanceof SaveResumeDataAlert) {
                        SaveResumeDataAlert saved = (SaveResumeDataAlert) alert;
                        saveResumeAlert(saved.params().getInfoHashes().getBest().toHex(), saved);
                        return;
                    }
                    if (!(alert instanceof org.libtorrent4j.alerts.TorrentAlert)) return;
                    TorrentHandle handle = ((org.libtorrent4j.alerts.TorrentAlert<?>) alert).handle();
                    if (handle == null || !handle.isValid()) return;
                    String hash = handle.infoHash().toHex();
                    if (alert instanceof SaveResumeDataFailedAlert) {
                        finishResumeRequest(hash);
                        return;
                    } else if (alert.type() == AlertType.TORRENT_FINISHED) {
                        requestResumeData(handle, true);
                    } else if (alert instanceof TrackerReplyAlert) {
                        AppLog.info("tracker_reply peers=" + ((TrackerReplyAlert) alert).numPeers());
                        trackerMessages.put(hash, "Tracker 已响应："
                                + ((TrackerReplyAlert) alert).numPeers() + " 个 Peer");
                    } else if (alert instanceof TrackerErrorAlert) {
                        TrackerErrorAlert error = (TrackerErrorAlert) alert;
                        AppLog.warn("tracker_error " + error.errorMessage() + " " + error.error().getMessage());
                        trackerMessages.put(hash, "Tracker 错误：" + error.errorMessage()
                                + " (" + error.error().getMessage() + ")");
                    } else if (alert instanceof TrackerWarningAlert) {
                        AppLog.warn("tracker_warning " + ((TrackerWarningAlert) alert).warningMessage());
                        trackerMessages.put(hash, "Tracker 警告："
                                + ((TrackerWarningAlert) alert).warningMessage());
                    } else if (alert instanceof TrackerAnnounceAlert) {
                        trackerMessages.put(hash, "正在向 Tracker 汇报");
                    }
                    notifyChanged();
                }
            });
            manager.start(params);
            AppLog.info("engine_started libtorrent=" + backendVersion());
            AppLog.info("disk_backend=posix_compatible process64=" + android.os.Process.is64Bit());
            if (prefBoolean("dht", true) && !manager.isDhtRunning()) manager.startDht();
            startResumeTimer();
        }
    }

    private void startResumeTimer() {
        if (resumeTimerStarted) return;
        resumeTimerStarted = true;
        metadataPoller.scheduleAtFixedRate(() -> requestAllResumeData(false),
                RESUME_SAVE_INTERVAL_SECONDS, RESUME_SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        metadataPoller.scheduleAtFixedRate(this::saveSessionState,
                SESSION_SAVE_INTERVAL_SECONDS, SESSION_SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        // This timer only posts asynchronous requests; no get_torrents/status/files here.
        statePoller.scheduleWithFixedDelay(this::refreshState, 0, 1000, TimeUnit.MILLISECONDS);
        detailPoller.scheduleWithFixedDelay(this::refreshDetails, 0, 1000, TimeUnit.MILLISECONDS);
    }

    private File sessionStateFile() {
        return new File(context.getFilesDir(), "session.state");
    }

    private SessionParams loadSessionParams() {
        File file = sessionStateFile();
        if (!file.isFile() || file.length() == 0) return new SessionParams();
        try {
            return new SessionParams(java.nio.file.Files.readAllBytes(file.toPath()));
        } catch (Throwable error) {
            // Never prevent the engine from starting because an interrupted write left stale state.
            file.delete();
            return new SessionParams();
        }
    }

    private synchronized void saveSessionState() {
        SessionManager value = manager;
        if (value == null || !value.isRunning()) return;
        try {
            byte[] bytes = value.saveState();
            if (bytes == null || bytes.length == 0) return;
            File destination = sessionStateFile();
            File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable error) {
            Log.w(TAG, "Cannot save libtorrent session state", error);
            AppLog.error("session_checkpoint_failed", error);
        }
    }

    private File resumeDataDirectory() {
        File directory = new File(context.getFilesDir(), "resume_data");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create resume data directory");
        return directory;
    }

    private File resumeFile(String hash) {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40,64}")) throw new IllegalArgumentException("Invalid torrent hash");
        return new File(resumeDataDirectory(), hash.toLowerCase(Locale.ROOT) + ".fastresume");
    }

    private void requestAllResumeData(boolean includeUnmodified) {
        SessionManager value = manager;
        if (value == null || !value.isRunning()) return;
        try {
            torrent_handle_vector handles = value.swig().get_torrents();
            try {
                for (int i = 0; i < handles.size(); i++) {
                    TorrentHandle handle = new TorrentHandle(handles.get(i));
                    if (handle.isValid() && !pendingHashes.contains(handle.infoHash().toHex())) {
                        requestResumeData(handle, includeUnmodified);
                    }
                }
            } finally { handles.delete(); }
        } catch (Throwable ignored) {}
    }

    private void requestResumeData(TorrentHandle handle, boolean includeUnmodified) {
        String hash = "";
        try {
            hash = handle.infoHash().toHex();
            if (!resumeRequests.add(hash)) return;
            if (includeUnmodified) handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
            else handle.saveResumeData(TorrentHandle.SAVE_INFO_DICT.or_(TorrentHandle.ONLY_IF_MODIFIED));
        } catch (Throwable ignored) {
            if (!hash.isEmpty()) finishResumeRequest(hash);
        }
    }

    private void saveResumeAlert(String hash, SaveResumeDataAlert alert) {
        try {
            byte[] bytes = AddTorrentParams.writeResumeDataBuf(alert.params());
            checkpointWriter.execute(() -> writeResumeCheckpoint(hash, bytes));
        } catch (Throwable error) {
            finishResumeRequest(hash);
            AppLog.error("resume_serialize_failed", error);
        }
    }

    private void writeResumeCheckpoint(String hash, byte[] bytes) {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            if (!activeHashes.contains(hash)) return;
            File destination = resumeFile(hash);
            File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Log.i(TAG, "Saved torrent fastresume checkpoint");
            AppLog.info("fastresume_saved writeMs=" + (android.os.SystemClock.elapsedRealtime() - started));
        } catch (Throwable error) {
            notifyError(message(error, "Cannot save resume data"));
        } finally {
            finishResumeRequest(hash);
        }
    }

    private void finishResumeRequest(String hash) {
        resumeRequests.remove(hash);
        synchronized (resumeSaveMonitor) { resumeSaveMonitor.notifyAll(); }
    }

    /** Best-effort synchronous checkpoint for Android task/service teardown. */
    public void saveResumeDataAsync() {
        metadataPoller.execute(() -> saveResumeDataNow(1500));
    }

    public void saveResumeDataNow(long timeoutMillis) {
        requestAllResumeData(true);
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        synchronized (resumeSaveMonitor) {
            while (!resumeRequests.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                try { resumeSaveMonitor.wait(remaining); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        saveSessionState();
    }

    private AddTorrentParams loadResumeData(String hash) {
        File file;
        try { file = resumeFile(hash); }
        catch (Throwable ignored) { return null; }
        if (!file.isFile() || file.length() == 0) return null;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            error_code error = new error_code();
            add_torrent_params nativeParams = libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(bytes), error);
            if (error.failed()) throw new IllegalArgumentException(error.message());
            return new AddTorrentParams(nativeParams);
        } catch (Throwable error) {
            file.delete();
            return null;
        }
    }

    public void applyPreferences() {
        io.execute(() -> {
            SessionManager value = manager;
            if (value == null || !value.isRunning()) return;
            SettingsPack pack = new SettingsPack();
            pack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), prefBoolean("dht", true));
            pack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), prefBoolean("lsd", true));
            pack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), prefBoolean("upnp", true));
            pack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), prefBoolean("natpmp", true));
            pack.downloadRateLimit(prefInt("download_limit", 0) * 1024);
            pack.uploadRateLimit(prefInt("upload_limit", 0) * 1024);
            int port = Math.max(1024, Math.min(65535, prefInt("listen_port", 6881)));
            pack.listenInterfaces("0.0.0.0:" + port + ",[::]:" + port);
            configureProxy(pack);
            value.applySettings(pack);
            value.reopenNetworkSockets();
            try {
                torrent_handle_vector handles = value.swig().get_torrents();
                for (int i = 0; i < handles.size(); i++) new TorrentHandle(handles.get(i)).forceReannounce();
            } catch (Throwable ignored) {}
            notifyChanged();
        });
    }

    private void configureProxy(SettingsPack pack) {
        boolean enabled = prefBoolean("proxy_enabled", false);
        int type = enabled
                ? settings_pack.proxy_type_t.socks5.swigValue()
                : settings_pack.proxy_type_t.none.swigValue();
        pack.setInteger(settings_pack.int_types.proxy_type.swigValue(), type);
        pack.setString(settings_pack.string_types.proxy_hostname.swigValue(),
                preferences.getString("proxy_host", ""));
        pack.setInteger(settings_pack.int_types.proxy_port.swigValue(),
                Math.max(1, Math.min(65535, prefInt("proxy_port", 1080))));
        pack.setBoolean(settings_pack.bool_types.proxy_hostnames.swigValue(),
                prefBoolean("proxy_hostnames", true));
        pack.setBoolean(settings_pack.bool_types.proxy_peer_connections.swigValue(),
                prefBoolean("proxy_peer_connections", true));
        pack.setBoolean(settings_pack.bool_types.proxy_tracker_connections.swigValue(), true);
    }

    public void addMagnet(String magnet) {
        String source = magnet == null ? "" : magnet.trim();
        io.execute(() -> {
            try {
                if (!isSupportedMagnet(source)) throw new IllegalArgumentException("Magnet link has no valid xt info-hash");
                ensureStarted();
                AddTorrentParams params = AddTorrentParams.parseMagnetUri(source);
                String hash = addTorrentParams(params, false);
                rememberSource(hash, "magnet", source, false);
                TorrentHandle handle = find(hash);
                if (handle != null) requestResumeData(handle, true);
                notifyChanged();
            } catch (Throwable error) {
                notifyError(error.getMessage() == null ? "Invalid magnet link" : error.getMessage());
            }
        });
    }

    public String prepareMagnet(String magnet, PrepareCallback callback) {
        AppLog.info("prepare_magnet");
        String source = magnet == null ? "" : magnet.trim();
        String id = UUID.randomUUID().toString();
        io.execute(() -> {
            TorrentHandle metadataHandle = null;
            String hash = "";
            try {
                if (cancelledDrafts.remove(id)) return;
                if (!isSupportedMagnet(source)) throw new IllegalArgumentException("Magnet link has no valid xt info-hash");
                ensureStarted();
                AddTorrentParams params = AddTorrentParams.parseMagnetUri(source);
                Sha1Hash hashValue = params.getInfoHashes().getBest();
                if (hashValue == null || hashValue.isAllZeros()) throw new IllegalArgumentException("Torrent has an invalid info-hash");
                hash = hashValue.toHex();
                if (find(hash) != null || pendingHashes.contains(hash)) throw new IllegalArgumentException("Torrent is already added or retrieving metadata");
                if (cancelledDrafts.remove(id)) return;
                pendingHashes.add(hash);
                metadataHandle = addMetadataTorrent(params);
                PendingTorrent pending = new PendingTorrent(id, "magnet", source, params, metadataHandle, null);
                pendingTorrents.put(id, pending);
                if (cancelledDrafts.remove(id)) {
                    discardDraft(id);
                    return;
                }
                pollMetadata(pending, callback, 0);
            } catch (Throwable error) {
                pendingHashes.remove(hash);
                if (metadataHandle != null && metadataHandle.isValid()) manager.remove(metadataHandle, new remove_flags_t());
                if (!cancelledDrafts.remove(id)) callback.onError(message(error, "Cannot prepare magnet"));
            }
        });
        return id;
    }

    public String prepareTorrent(Uri uri, PrepareCallback callback) {
        String id = UUID.randomUUID().toString();
        io.execute(() -> {
            File target = null;
            try {
                if (cancelledDrafts.remove(id)) return;
                ensureStarted();
                File defs = torrentDefinitionDirectory();
                target = File.createTempFile("import-", ".torrent", defs);
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IllegalArgumentException("Cannot open selected file");
                    copyTorrentBytes(input, output);
                }
                prepareTorrentFile(target, id, callback);
            } catch (Throwable error) {
                if (target != null && target.exists()) target.delete();
                if (!cancelledDrafts.remove(id)) callback.onError(message(error, "Cannot prepare torrent"));
            }
        });
        return id;
    }

    public String prepareTorrentUrl(String url, PrepareCallback callback) {
        String source = url == null ? "" : url.trim();
        String id = UUID.randomUUID().toString();
        io.execute(() -> {
            try {
                if (cancelledDrafts.remove(id)) return;
                if (!isSupportedTorrentUrl(source)) throw new IllegalArgumentException("Only HTTP/HTTPS torrent links are supported");
                ensureStarted();
                prepareTorrentFile(downloadTorrentDefinition(source), id, callback);
            } catch (Throwable error) {
                if (!cancelledDrafts.remove(id)) callback.onError(message(error, "Cannot download torrent file"));
            }
        });
        return id;
    }

    private void prepareTorrentFile(File file, String id, PrepareCallback callback) {
        try {
            if (cancelledDrafts.remove(id)) { file.delete(); return; }
            AddTorrentParams params = loadTorrentParams(file);
            Sha1Hash bestHash = params.getInfoHashes().getBest();
            if (bestHash == null || bestHash.isAllZeros()) throw new IllegalArgumentException("Torrent has an invalid info-hash");
            if (find(bestHash.toHex()) != null || pendingHashes.contains(bestHash.toHex())) throw new IllegalArgumentException("Torrent already exists");
            TorrentInfo info = new TorrentInfo(file);
            if (!info.isValid()) throw new IllegalArgumentException("Invalid torrent file");
            PendingTorrent pending = new PendingTorrent(id, "torrent", file.getAbsolutePath(), params, null, file);
            pendingTorrents.put(id, pending);
            if (cancelledDrafts.remove(id)) { discardDraft(id); return; }
            callback.onReady(toDraft(pending, info));
        } catch (Throwable error) {
            file.delete();
            if (!cancelledDrafts.remove(id)) callback.onError(message(error, "Cannot prepare torrent"));
        }
    }

    private TorrentHandle addMetadataTorrent(AddTorrentParams params) {
        File metadataDirectory = new File(context.getCacheDir(), "magnet_metadata");
        if (!metadataDirectory.exists() && !metadataDirectory.mkdirs()) throw new IllegalStateException("Cannot create metadata directory");
        params.setSavePath(metadataDirectory.getAbsolutePath());
        torrent_flags_t flags = params.getFlags()
                .and_(TorrentFlags.PAUSED.inv())
                .and_(TorrentFlags.AUTO_MANAGED.inv())
                .or_(TorrentFlags.UPLOAD_MODE)
                .or_(TorrentFlags.STOP_WHEN_READY);
        params.setFlags(flags);
        error_code addError = new error_code();
        TorrentHandle handle = new TorrentHandle(manager.swig().add_torrent(params.swig(), addError));
        if (addError.failed() || !handle.isValid()) throw new IllegalStateException("Cannot start metadata retrieval: " + addError.message());
        // Match qBittorrent's SessionImpl::downloadMetadata and libtorrent4j's fetchMagnet:
        // resuming the handle explicitly is required even after clearing the paused add flag.
        handle.resume();
        return handle;
    }

    private void pollMetadata(PendingTorrent pending, PrepareCallback callback, int attempt) {
        metadataPoller.schedule(() -> {
            try {
                if (!pendingTorrents.containsKey(pending.id) || cancelledDrafts.contains(pending.id)) return;
                TorrentInfo info = pending.handle == null ? null : pending.handle.torrentFile();
                if ((attempt % 10) == 0 && pending.handle != null && pending.handle.isValid()) {
                    TorrentStatus status = pending.handle.status();
                    Log.i(TAG, "Magnet metadata: state=" + status.state()
                            + ", peers=" + status.numPeers()
                            + ", connections=" + status.numConnections()
                            + ", dhtNodes=" + (manager == null ? 0 : manager.dhtNodes()));
                }
                if (info != null && info.isValid() && info.isLoaded()) {
                    pending.params.setTorrentInfo(info);
                    if (pending.handle != null && pending.handle.isValid()) manager.remove(pending.handle, new remove_flags_t());
                    pending.handle = null;
                    pending.params.setFlags(pending.params.getFlags()
                            .and_(TorrentFlags.UPLOAD_MODE.inv())
                            .and_(TorrentFlags.STOP_WHEN_READY.inv()));
                    callback.onReady(toDraft(pending, info));
                } else if (attempt >= 119) {
                    discardDraft(pending.id);
                    callback.onError("Timed out while fetching magnet metadata");
                } else pollMetadata(pending, callback, attempt + 1);
            } catch (Throwable error) {
                discardDraft(pending.id);
                callback.onError(message(error, "Cannot read torrent metadata"));
            }
        }, attempt == 0 ? 0 : 1, TimeUnit.SECONDS);
    }

    private TorrentDraft toDraft(PendingTorrent pending, TorrentInfo info) {
        ArrayList<TorrentDraft.FileItem> files = new ArrayList<>();
        FileStorage storage = info.files();
        for (int i = 0; i < storage.numFiles(); i++) {
            files.add(new TorrentDraft.FileItem(storage.filePath(i), storage.fileSize(i)));
        }
        return new TorrentDraft(pending.id, info.name(), info.infoHash().toHex(),
                info.totalSize(), info.isPrivate(), files);
    }

    public void commitDraft(String id, boolean[] selectedFiles, int downloadKiB, int uploadKiB) {
        AppLog.info("commit_task files=" + selectedFiles.length + " downKiB=" + downloadKiB + " upKiB=" + uploadKiB);
        io.execute(() -> {
            cancelledDrafts.remove(id);
            PendingTorrent pending = pendingTorrents.remove(id);
            if (pending == null) return;
            try {
                Priority[] priorities = new Priority[selectedFiles.length];
                for (int i = 0; i < selectedFiles.length; i++) priorities[i] = selectedFiles[i] ? Priority.DEFAULT : Priority.IGNORE;
                int downloadLimit = kibToBytes(downloadKiB);
                int uploadLimit = kibToBytes(uploadKiB);
                String hash;
                TorrentHandle handle = pending.handle;
                if (handle != null && handle.isValid()) {
                    handle.prioritizeFiles(priorities);
                    handle.setDownloadLimit(downloadLimit);
                    handle.setUploadLimit(uploadLimit);
                    setPaused(handle, false);
                    hash = handle.infoHash().toHex();
                } else {
                    pending.params.filePriorities(priorities);
                    pending.params.setDownloadLimit(downloadLimit);
                    pending.params.setUploadLimit(uploadLimit);
                    hash = addTorrentParams(pending.params, false);
                    handle = find(hash);
                }
                activeHashes.add(hash);
                if (handle != null) handle.setFlags(TorrentFlags.UPDATE_SUBSCRIBE);
                pendingHashes.remove(hash);
                rememberSource(hash, pending.type, pending.source, false, priorities, downloadLimit, uploadLimit);
                if (handle != null) requestResumeData(handle, true);
                notifyChanged();
            } catch (Throwable error) {
                pendingHashes.remove(bestHash(pending.params));
                notifyError(message(error, "Cannot add torrent"));
            }
        });
    }

    public void discardDraft(String id) {
        cancelledDrafts.add(id);
        io.execute(() -> {
            PendingTorrent pending = pendingTorrents.remove(id);
            try {
                if (pending == null) return;
                String hash = bestHash(pending.params);
                pendingHashes.remove(hash);
                if (pending.handle != null && pending.handle.isValid()) manager.remove(pending.handle, new remove_flags_t());
                if (pending.ownedFile != null) pending.ownedFile.delete();
                notifyChanged();
            } finally {
                cancelledDrafts.remove(id);
            }
        });
    }

    private int kibToBytes(int kib) {
        if (kib <= 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, (long) kib * 1024L);
    }

    private String bestHash(AddTorrentParams params) {
        try { return params.getInfoHashes().getBest().toHex(); }
        catch (Throwable ignored) { return ""; }
    }

    private String message(Throwable error, String fallback) {
        AppLog.error(fallback, error);
        return error.getMessage() == null ? fallback : error.getMessage();
    }

    public void addTorrent(Uri uri) {
        io.execute(() -> {
            try {
                ensureStarted();
                File defs = new File(context.getFilesDir(), "torrent_defs");
                if (!defs.exists() && !defs.mkdirs()) throw new IllegalStateException("Cannot create torrent store");
                File target = File.createTempFile("import-", ".torrent", defs);
                try (InputStream input = context.getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IllegalArgumentException("Cannot open selected file");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                String hash = addTorrentFile(target, false);
                rememberSource(hash, "torrent", target.getAbsolutePath(), false);
                TorrentHandle handle = find(hash);
                if (handle != null) requestResumeData(handle, true);
                notifyChanged();
            } catch (Throwable error) {
                notifyError(error.getMessage() == null ? "Cannot add torrent" : error.getMessage());
            }
        });
    }

    public void addTorrentUrl(String url) {
        String source = url == null ? "" : url.trim();
        io.execute(() -> {
            try {
                if (!isSupportedTorrentUrl(source)) throw new IllegalArgumentException("Only HTTP/HTTPS torrent links are supported");
                ensureStarted();
                File target = downloadTorrentDefinition(source);
                String hash = addTorrentFile(target, false);
                rememberSource(hash, "torrent", target.getAbsolutePath(), false);
                TorrentHandle handle = find(hash);
                if (handle != null) requestResumeData(handle, true);
                notifyChanged();
            } catch (Throwable error) {
                notifyError(error.getMessage() == null ? "Cannot download torrent file" : error.getMessage());
            }
        });
    }

    private File downloadTorrentDefinition(String source) throws Exception {
        File defs = new File(context.getFilesDir(), "torrent_defs");
        if (!defs.exists() && !defs.mkdirs()) throw new IllegalStateException("Cannot create torrent store");
        File target = File.createTempFile("url-", ".torrent", defs);
        String current = source;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                URL url = new URL(current);
                Proxy proxy = Proxy.NO_PROXY;
                if (prefBoolean("proxy_enabled", false)) {
                    String host = preferences.getString("proxy_host", "").trim();
                    int port = Math.max(1, Math.min(65535, prefInt("proxy_port", 1080)));
                    if (!host.isEmpty()) proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
                }
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(45_000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/x-bittorrent, application/octet-stream;q=0.9, */*;q=0.1");
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.trim().isEmpty()) throw new IllegalArgumentException("Torrent URL redirect has no destination");
                    current = new URL(url, location).toString();
                    continue;
                }
                if (status < 200 || status >= 300) {
                    connection.disconnect();
                    throw new IllegalArgumentException("Torrent URL returned HTTP " + status);
                }
                long declaredSize = connection.getContentLengthLong();
                if (declaredSize > MAX_TORRENT_FILE_SIZE) {
                    connection.disconnect();
                    throw new IllegalArgumentException("Torrent file is too large");
                }
                long total = 0;
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(target, false)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_TORRENT_FILE_SIZE) throw new IllegalArgumentException("Torrent file is too large");
                        output.write(buffer, 0, count);
                    }
                } finally {
                    connection.disconnect();
                }
                if (total == 0) throw new IllegalArgumentException("Torrent URL returned an empty file");
                return target;
            }
            throw new IllegalArgumentException("Too many redirects while downloading torrent file");
        } catch (Exception error) {
            if (!target.delete()) target.deleteOnExit();
            throw error;
        }
    }

    public List<TorrentSnapshot> snapshots() {
        return cachedSnapshots;
    }

    private void refreshState() {
        try {
            SessionManager value = manager;
            if (value != null) { value.postTorrentUpdates(); value.postSessionStats(); }
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastHealthLog >= 10000) {
                lastHealthLog = now;
                List<TorrentSnapshot> next = cachedSnapshots;
                int peers = 0, seeds = 0;
                for (TorrentSnapshot item : next) { peers += item.peers; seeds += item.seeds; }
                Runtime runtime = Runtime.getRuntime();
                AppLog.info("health tasks=" + next.size() + " peers=" + peers + " seeds=" + seeds
                        + " downBps=" + cachedDownloadRate + " upBps=" + cachedUploadRate
                        + " javaBytes=" + (runtime.totalMemory() - runtime.freeMemory())
                        + " nativeBytes=" + android.os.Debug.getNativeHeapAllocatedSize()
                        + " stateAgeMs=" + (lastStateAt == 0 ? -1 : now - lastStateAt)
                        + " statsAgeMs=" + (lastStatsAt == 0 ? -1 : now - lastStatsAt)
                        + " stateReplyMs=" + stateLatencyMs + " statsReplyMs=" + statsLatencyMs
                        + " statePendingMs=" + stateRequests.pendingAge(now)
                        + " statsPendingMs=" + statsRequests.pendingAge(now)
                        + " stateConvertMs=" + stateConvertMs + " detailsQueryMs=" + detailsQueryMs
                        + " stateFrames=" + stateFrameCount + " statsFrames=" + statsFrameCount);
            }
        } catch (Exception error) {
            AppLog.error("state_request_failed", error);
        }
    }

    private void acceptStateUpdate(StateUpdateAlert alert) {
        long start = android.os.SystemClock.elapsedRealtime();
        // This vector and its elements belong to the alert/copy wrappers. Read and release
        // them inside the callback; only immutable Java values survive the callback.
        torrent_status_vector updates = alert.swig().getStatus();
        try {
            synchronized (stateLock) {
                for (int i = 0; i < updates.size(); i++) {
                    TorrentStatus status = new TorrentStatus(updates.get(i));
                    try {
                        String hash = status.getInfoHashes().getBest().toHex();
                        if (activeHashes.contains(hash)) states.put(hash, snapshot(hash, status, alert.timestamp()));
                    } finally { status.swig().delete(); }
                }
                // StateUpdateAlert is a DELTA, including empty deltas. Keep unchanged tasks.
                publishSnapshots();
            }
            lastStateAt = android.os.SystemClock.elapsedRealtime();
            stateFrameCount++;
        } catch (Exception error) {
            AppLog.error("state_conversion_failed", error);
        } finally {
            updates.delete();
            long now = android.os.SystemClock.elapsedRealtime();
            stateLatencyMs = stateRequests.complete(now);
            stateConvertMs = now - start;
        }
    }

    private void acceptSessionStats(SessionStatsAlert alert) {
        try {
            long received = alert.value(StatsMetric.NET_RECV_BYTES_COUNTER_INDEX)
                    + alert.value(StatsMetric.NET_RECV_IP_OVERHEAD_BYTES_COUNTER_INDEX);
            long sent = alert.value(StatsMetric.NET_SENT_BYTES_COUNTER_INDEX)
                    + alert.value(StatsMetric.NET_SENT_IP_OVERHEAD_BYTES_COUNTER_INDEX);
            cachedDownloadRate = sessionDownload.sample(received, alert.timestamp());
            cachedUploadRate = sessionUpload.sample(sent, alert.timestamp());
            lastStatsAt = android.os.SystemClock.elapsedRealtime();
            statsFrameCount++;
        } catch (Exception error) {
            AppLog.error("stats_conversion_failed", error);
        } finally { statsLatencyMs = statsRequests.complete(android.os.SystemClock.elapsedRealtime()); }
    }

    // Call with stateLock held. No native calls, IO or listener dispatch under this lock.
    private void publishSnapshots() {
        ArrayList<TorrentSnapshot> next = new ArrayList<>();
        for (TorrentSnapshot item : states.values()) {
            if (activeHashes.contains(item.hash) && !pendingHashes.contains(item.hash)) next.add(item);
        }
        cachedSnapshots = Collections.unmodifiableList(next);
    }

    public TorrentSnapshot snapshot(String hash) {
        for (TorrentSnapshot item : cachedSnapshots) if (item.hash.equals(hash)) return item;
        return null;
    }

    private TorrentSnapshot snapshot(String hash, TorrentStatus status, long sampleMillis) {
        String displayName = status.name();
        if (displayName == null || displayName.trim().isEmpty()) displayName = hash;
        boolean paused = status.flags().and_(TorrentFlags.PAUSED).non_zero();
        TorrentSnapshot.Group group;
        String state = status.state().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        if (status.errorCode().isError()) group = TorrentSnapshot.Group.ERROR;
        else if (paused) group = TorrentSnapshot.Group.PAUSED;
        else if (status.isSeeding() || status.isFinished()) group = TorrentSnapshot.Group.SEEDING;
        else if (state.contains("check")) group = TorrentSnapshot.Group.CHECKING;
        else group = TorrentSnapshot.Group.DOWNLOADING;
        // Native accurate counters include partial blocks; payload totals include retransmits
        // and must NOT be added to completion as the previous LiveProgress estimator did.
        long completed = Math.max(0, Math.min(status.totalWanted(), status.totalWantedDone()));
        long remaining = Math.max(0, status.totalWanted() - completed);
        TransferRate[] rates = torrentRates.computeIfAbsent(hash,
                ignored -> new TransferRate[]{new TransferRate(), new TransferRate()});
        long download = rates[0].sample(status.totalPayloadDownload(), sampleMillis);
        long upload = rates[1].sample(status.totalPayloadUpload(), sampleMillis);
        if (paused || group == TorrentSnapshot.Group.CHECKING || group == TorrentSnapshot.Group.ERROR) {
            download = 0; upload = 0;
        }
        long eta = download > 0 ? remaining / download : -1;
        float preciseProgress = status.totalWanted() > 0
                ? (float) ((double) completed / status.totalWanted()) : status.progress();
        return new TorrentSnapshot(hash, displayName, preciseProgress,
                status.totalWanted(), completed, download, upload,
                status.allTimeUpload(), status.numSeeds(), status.numPeers(), eta, state,
                status.swig().getSave_path(), group, trackers(hash));
    }

    private void refreshDetails() {
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            Map<String, DetailState> details = new HashMap<>();
            for (String hash : detailWatches) {
                if (!activeHashes.contains(hash)) continue;
                TorrentHandle handle = find(hash);
                if (handle == null) continue;
                ArrayList<String> trackers = new ArrayList<>();
                for (AnnounceEntry entry : handle.trackers()) trackers.add(entry.url());
                DetailState state = new DetailState(readFiles(handle),
                        Math.max(0, handle.getDownloadLimit() / 1024),
                        Math.max(0, handle.getUploadLimit() / 1024), trackers);
                if (detailWatches.contains(hash) && activeHashes.contains(hash)) details.put(hash, state);
            }
            cachedDetails = Collections.unmodifiableMap(details);
        } catch (Exception error) {
            AppLog.error("details_refresh_failed", error);
        } finally {
            long now = android.os.SystemClock.elapsedRealtime();
            detailsQueryMs = now - started;
            if (detailsQueryMs > 1000 && now - lastDetailWarning > 10000) {
                lastDetailWarning = now;
                AppLog.warn("slow_details queryMs=" + detailsQueryMs + " watches=" + detailWatches.size());
            }
        }
    }

    public List<String> trackers(String hash) {
        ArrayList<String> lines = new ArrayList<>();
        DetailState details = cachedDetails.get(hash);
        if (details != null) lines.addAll(details.trackers);
        String message = trackerMessages.get(hash);
        if (message != null && !message.isEmpty()) lines.add("状态：" + message);
        return lines;
    }

    public List<String> files(String hash) {
        DetailState state = cachedDetails.get(hash);
        return state == null ? Collections.emptyList() : state.files;
    }

    public void watchDetails(String hash) { detailWatches.add(hash); }
    public void unwatchDetails(String hash) { detailWatches.remove(hash); }
    public boolean hasDetails(String hash) { return cachedDetails.containsKey(hash); }

    private List<String> readFiles(TorrentHandle handle) {
        ArrayList<String> files = new ArrayList<>();
        TorrentInfo info = null;
        FileStorage storage = null;
        org.libtorrent4j.swig.int64_vector progress = new org.libtorrent4j.swig.int64_vector();
        try {
            info = handle.torrentFile();
            if (info == null || !info.isValid()) return files;
            storage = info.files();
            handle.swig().file_progress(progress);
            for (int i = 0; i < storage.numFiles(); i++) {
                long done = i < progress.size() ? progress.get(i) : 0;
                files.add(storage.filePath(i) + "\n" + Formatters.downloadedBytes(done) + " / " + Formatters.bytes(storage.fileSize(i)));
            }
        } catch (Exception error) {
            AppLog.error("file_progress_failed", error);
        } finally {
            progress.delete();
            if (storage != null) storage.swig().delete();
            if (info != null) info.swig().delete();
        }
        return files;
    }

    public void pause(String hash) {
        AppLog.info("task_pause");
        io.execute(() -> {
            setSourcePaused(hash, true);
            TorrentHandle handle = find(hash);
            if (handle != null) setPaused(handle, true);
            if (handle != null) requestResumeData(handle, true);
            notifyChanged();
        });
    }

    public void resume(String hash) {
        AppLog.info("task_resume");
        io.execute(() -> {
            setSourcePaused(hash, false);
            TorrentHandle handle = find(hash);
            if (handle != null) setPaused(handle, false);
            if (handle != null) requestResumeData(handle, true);
            notifyChanged();
        });
    }
    public int torrentDownloadLimitKiB(String hash) {
        DetailState state = cachedDetails.get(hash);
        return state == null ? 0 : state.downloadKiB;
    }

    public int torrentUploadLimitKiB(String hash) {
        DetailState state = cachedDetails.get(hash);
        return state == null ? 0 : state.uploadKiB;
    }

    public void setTorrentLimits(String hash, int downloadKiB, int uploadKiB) {
        io.execute(() -> {
            TorrentHandle handle = find(hash);
            if (handle == null) return;
            int downloadLimit = kibToBytes(downloadKiB);
            int uploadLimit = kibToBytes(uploadKiB);
            handle.setDownloadLimit(downloadLimit);
            handle.setUploadLimit(uploadLimit);
            updateSourceLimits(hash, downloadLimit, uploadLimit);
            requestResumeData(handle, true);
            notifyChanged();
        });
    }
    public void reannounce(String hash) { runOnHandle(hash, TorrentHandle::forceReannounce); }
    public void recheck(String hash) { runOnHandle(hash, TorrentHandle::forceRecheck); }

    public void remove(String hash, boolean deleteFiles) {
        AppLog.info("task_remove deleteFiles=" + deleteFiles);
        io.execute(() -> {
            TorrentHandle handle = find(hash);
            if (handle != null) {
                if (deleteFiles) manager.remove(handle, session_handle.delete_files);
                else manager.remove(handle, new remove_flags_t());
            }
            forgetSource(hash);
            synchronized (stateLock) {
                activeHashes.remove(hash);
                states.remove(hash);
                torrentRates.remove(hash);
                publishSnapshots();
            }
            trackerMessages.remove(hash);
            checkpointWriter.execute(() -> {
                if (!activeHashes.contains(hash)) try { resumeFile(hash).delete(); } catch (Throwable ignored) {}
            });
            notifyChanged();
        });
    }

    private void runOnHandle(String hash, HandleAction action) {
        io.execute(() -> {
            TorrentHandle handle = find(hash);
            if (handle != null) action.run(handle);
            notifyChanged();
        });
    }

    private void setPaused(TorrentHandle handle, boolean paused) {
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED);
        if (paused) handle.setFlags(TorrentFlags.PAUSED);
        else handle.unsetFlags(TorrentFlags.PAUSED);
    }

    private TorrentHandle find(String hash) {
        SessionManager value = manager;
        if (value == null || !value.isRunning() || hash == null) return null;
        try {
            TorrentHandle handle = value.find(Sha1Hash.parseHex(hash));
            return handle != null && handle.isValid() ? handle : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void ensureStarted() {
        if (!isRunning()) startInternal();
    }

    private void restoreSources() {
        String json = preferences.getString(KEY_SOURCES, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String type = item.optString("type");
                String source = item.optString("source");
                boolean paused = item.optBoolean("paused", false);
                int downloadLimit = item.optInt("downloadLimit", 0);
                int uploadLimit = item.optInt("uploadLimit", 0);
                Priority[] priorities = jsonPriorities(item.optJSONArray("filePriorities"));
                try {
                    AddTorrentParams resumeParams = loadResumeData(item.optString("hash"));
                    if (resumeParams != null) {
                        Log.i(TAG, "Restoring torrent from fastresume checkpoint");
                        TorrentHandle handle = find(addTorrentParams(resumeParams, paused));
                        if (handle != null) applyTorrentOptions(handle, priorities, downloadLimit, uploadLimit);
                    } else if ("magnet".equals(type)) {
                        TorrentHandle handle = find(addTorrentParams(AddTorrentParams.parseMagnetUri(source), paused));
                        if (handle != null) applyTorrentOptions(handle, priorities, downloadLimit, uploadLimit);
                    } else if ("torrent".equals(type)) {
                        File file = new File(source);
                        if (file.isFile()) {
                            AddTorrentParams params = loadTorrentParams(file);
                            if (priorities.length > 0) params.filePriorities(priorities);
                            params.setDownloadLimit(downloadLimit);
                            params.setUploadLimit(uploadLimit);
                            TorrentHandle handle = find(addTorrentParams(params, paused));
                            if (handle != null) applyTorrentOptions(handle, priorities, downloadLimit, uploadLimit);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Loads the complete metainfo file, not just its info dictionary. Trackers,
     * tracker tiers, web seeds, name and other top-level fields live outside
     * the info dictionary and are required by private torrents.
     */
    private String addTorrentFile(File file, boolean paused) {
        return addTorrentParams(loadTorrentParams(file), paused);
    }

    private AddTorrentParams loadTorrentParams(File file) {
        error_code loadError = new error_code();
        add_torrent_params nativeParams = add_torrent_params.load_torrent_file(file.getAbsolutePath(), loadError);
        if (loadError.failed()) {
            throw new IllegalArgumentException("Invalid torrent file: " + loadError.message());
        }
        return new AddTorrentParams(nativeParams);
    }

    private String addTorrentParams(AddTorrentParams params, boolean paused) {
        Sha1Hash bestHash = params.getInfoHashes().getBest();
        if (bestHash == null || bestHash.isAllZeros()) {
            throw new IllegalArgumentException("Torrent has an invalid info-hash");
        }
        String hash = bestHash.toHex();

        TorrentHandle existing = manager.find(bestHash);
        if (existing != null && existing.isValid()) {
            activeHashes.add(hash);
            existing.setFlags(TorrentFlags.UPDATE_SUBSCRIBE);
            setPaused(existing, paused);
            return hash;
        }

        params.setSavePath(downloadDirectory().getAbsolutePath());
        torrent_flags_t flags = params.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv());
        flags = paused ? flags.or_(TorrentFlags.PAUSED) : flags.and_(TorrentFlags.PAUSED.inv());
        params.setFlags(flags.or_(TorrentFlags.UPDATE_SUBSCRIBE));
        error_code addError = new error_code();
        activeHashes.add(hash);
        TorrentHandle handle = new TorrentHandle(manager.swig().add_torrent(params.swig(), addError));
        if (addError.failed()) {
            activeHashes.remove(hash);
            throw new IllegalStateException("Cannot add torrent: " + addError.message());
        }
        if (handle.isValid()) setPaused(handle, paused);
        return hash;
    }

    private synchronized void rememberSource(String hash, String type, String source, boolean paused) {
        rememberSource(hash, type, source, paused, new Priority[0], 0, 0);
    }

    private synchronized void rememberSource(String hash, String type, String source, boolean paused,
                                               Priority[] priorities, int downloadLimit, int uploadLimit) {
        try {
            JSONArray old = new JSONArray(preferences.getString(KEY_SOURCES, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!hash.equalsIgnoreCase(item.optString("hash"))) next.put(item);
            }
            JSONArray priorityValues = new JSONArray();
            for (Priority priority : priorities) priorityValues.put(priority.swig());
            next.put(new JSONObject().put("hash", hash).put("type", type).put("source", source)
                    .put("paused", paused).put("filePriorities", priorityValues)
                    .put("downloadLimit", downloadLimit).put("uploadLimit", uploadLimit));
            preferences.edit().putString(KEY_SOURCES, next.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private synchronized void setSourcePaused(String hash, boolean paused) {
        try {
            JSONArray old = new JSONArray(preferences.getString(KEY_SOURCES, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (hash.equalsIgnoreCase(item.optString("hash"))) item.put("paused", paused);
                next.put(item);
            }
            preferences.edit().putString(KEY_SOURCES, next.toString()).commit();
        } catch (Throwable ignored) {}
    }

    private synchronized void updateSourceLimits(String hash, int downloadLimit, int uploadLimit) {
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_SOURCES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (hash.equalsIgnoreCase(item.optString("hash"))) {
                    item.put("downloadLimit", downloadLimit);
                    item.put("uploadLimit", uploadLimit);
                }
            }
            preferences.edit().putString(KEY_SOURCES, array.toString()).commit();
        } catch (Throwable ignored) {}
    }

    private Priority[] jsonPriorities(JSONArray values) {
        if (values == null) return new Priority[0];
        Priority[] result = new Priority[values.length()];
        for (int i = 0; i < values.length(); i++) result[i] = Priority.fromSwig(values.optInt(i, Priority.DEFAULT.swig()));
        return result;
    }

    private void applyTorrentOptions(TorrentHandle handle, Priority[] priorities, int downloadLimit, int uploadLimit) {
        if (priorities.length > 0) {
            try {
                TorrentInfo info = handle.torrentFile();
                if (info != null && info.isValid() && info.isLoaded()) handle.prioritizeFiles(priorities);
                else applyPrioritiesWhenReady(handle, priorities, 0);
            } catch (Throwable ignored) { applyPrioritiesWhenReady(handle, priorities, 0); }
        }
        handle.setDownloadLimit(Math.max(0, downloadLimit));
        handle.setUploadLimit(Math.max(0, uploadLimit));
    }

    private void applyPrioritiesWhenReady(TorrentHandle handle, Priority[] priorities, int attempt) {
        metadataPoller.schedule(() -> {
            try {
                if (!handle.isValid() || attempt >= 120) return;
                TorrentInfo info = handle.torrentFile();
                if (info != null && info.isValid() && info.isLoaded()) handle.prioritizeFiles(priorities);
                else applyPrioritiesWhenReady(handle, priorities, attempt + 1);
            } catch (Throwable ignored) {
                if (attempt < 120) applyPrioritiesWhenReady(handle, priorities, attempt + 1);
            }
        }, 1, TimeUnit.SECONDS);
    }

    private File torrentDefinitionDirectory() {
        File defs = new File(context.getFilesDir(), "torrent_defs");
        if (!defs.exists() && !defs.mkdirs()) throw new IllegalStateException("Cannot create torrent store");
        return defs;
    }

    private void copyTorrentBytes(InputStream input, FileOutputStream output) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_TORRENT_FILE_SIZE) throw new IllegalArgumentException("Torrent file is too large");
            output.write(buffer, 0, count);
        }
        if (total == 0) throw new IllegalArgumentException("Torrent file is empty");
    }

    private synchronized void forgetSource(String hash) {
        try {
            JSONArray old = new JSONArray(preferences.getString(KEY_SOURCES, "[]"));
            JSONArray next = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!hash.equalsIgnoreCase(item.optString("hash"))) next.put(item);
            }
            preferences.edit().putString(KEY_SOURCES, next.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private boolean prefBoolean(String key, boolean fallback) { return preferences.getBoolean(key, fallback); }
    private int prefInt(String key, int fallback) { return preferences.getInt(key, fallback); }

    public SharedPreferences preferences() { return preferences; }
    public long downloadRate() { return cachedDownloadRate; }
    public long uploadRate() { return cachedUploadRate; }

    private static final class DetailState {
        final List<String> files, trackers;
        final int downloadKiB, uploadKiB;
        DetailState(List<String> files, int downloadKiB, int uploadKiB, List<String> trackers) {
            this.files = Collections.unmodifiableList(files);
            this.trackers = Collections.unmodifiableList(trackers);
            this.downloadKiB = downloadKiB;
            this.uploadKiB = uploadKiB;
        }
    }

    private void notifyChanged() { for (Listener listener : listeners) listener.onEngineChanged(); }
    private void notifyError(String message) {
        AppLog.error("engine_error " + message, null);
        for (Listener listener : listeners) listener.onEngineError(message == null ? "Unknown error" : message);
    }

    private interface HandleAction { void run(TorrentHandle handle); }

    private static final class PendingTorrent {
        final String id, type, source;
        final AddTorrentParams params;
        volatile TorrentHandle handle;
        final File ownedFile;

        PendingTorrent(String id, String type, String source, AddTorrentParams params,
                       TorrentHandle handle, File ownedFile) {
            this.id = id;
            this.type = type;
            this.source = source;
            this.params = params;
            this.handle = handle;
            this.ownedFile = ownedFile;
        }
    }
}
