package org.qbittorrent.mobile;

import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;
import java.util.Locale;

public final class TorrentDetailActivity extends AppCompatActivity implements TorrentEngine.Listener {
    public static final String EXTRA_HASH = "torrent_hash";
    private final UiRefresh refresh = new UiRefresh(1000, this::render);
    private TorrentEngine engine;
    private String hash;
    private TextView name, stats, files, trackers;
    private ProgressBar progress;
    private MaterialButton pause;
    private TextInputEditText downloadLimit, uploadLimit;
    private boolean limitsLoaded;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        hash = getIntent().getStringExtra(EXTRA_HASH);
        if (hash == null) { finish(); return; }
        engine = TorrentEngine.get(this);
        name = findViewById(R.id.detail_name);
        stats = findViewById(R.id.detail_stats);
        files = findViewById(R.id.detail_files);
        trackers = findViewById(R.id.detail_trackers);
        progress = findViewById(R.id.detail_progress);
        pause = findViewById(R.id.action_pause);
        downloadLimit = findViewById(R.id.detail_download_limit);
        uploadLimit = findViewById(R.id.detail_upload_limit);
        pause.setOnClickListener(v -> {
            TorrentSnapshot item = engine.snapshot(hash);
            if (item != null && item.group == TorrentSnapshot.Group.PAUSED) engine.resume(hash); else engine.pause(hash);
        });
        findViewById(R.id.action_announce).setOnClickListener(v -> { engine.reannounce(hash); Toast.makeText(this, R.string.reannounce, Toast.LENGTH_SHORT).show(); });
        findViewById(R.id.action_recheck).setOnClickListener(v -> { engine.recheck(hash); Toast.makeText(this, R.string.recheck, Toast.LENGTH_SHORT).show(); });
        findViewById(R.id.action_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.action_save_limits).setOnClickListener(v -> saveLimits());
    }

    @Override protected void onStart() {
        super.onStart();
        if (engine == null) return;
        engine.watchDetails(hash);
        engine.addListener(this);
        refresh.start();
    }
    @Override protected void onStop() {
        refresh.stop();
        if (engine != null) { engine.removeListener(this); engine.unwatchDetails(hash); }
        super.onStop();
    }
    @Override public boolean onSupportNavigateUp() { finish(); return true; }
    @Override public void onEngineChanged() { refresh.request(); }

    private void render() {
        TorrentSnapshot item = engine.snapshot(hash);
        if (item == null) return;
        name.setText(item.name);
        progress.setProgress(Math.round(item.progress * 100000));
        stats.setText(String.format(Locale.getDefault(),
                "状态：%s\n进度：%.2f%%  (%s / %s)\n下载：%s    上传：%s\n累计上传：%s\nSeeds：%d    Peers：%d\n预计剩余：%s\n保存位置：%s\nInfo hash：%s",
                item.state, item.progress * 100, Formatters.downloadedBytes(item.completed), Formatters.bytes(item.total),
                Formatters.speed(item.downloadRate), Formatters.speed(item.uploadRate), Formatters.bytes(item.uploaded),
                item.seeds, item.peers, Formatters.duration(item.etaSeconds), item.savePath, item.hash));
        pause.setText(item.group == TorrentSnapshot.Group.PAUSED ? R.string.resume : R.string.pause);
        if (!limitsLoaded && engine.hasDetails(hash)) {
            downloadLimit.setText(String.valueOf(engine.torrentDownloadLimitKiB(hash)));
            uploadLimit.setText(String.valueOf(engine.torrentUploadLimitKiB(hash)));
            limitsLoaded = true;
        }
        List<String> fileItems = engine.files(hash);
        setTextIfChanged(files, fileItems.isEmpty() ? "等待元数据…" : join(fileItems));
        List<String> trackerItems = engine.trackers(hash);
        setTextIfChanged(trackers, trackerItems.isEmpty() ? "无 Tracker（可能为 DHT 任务）" : join(trackerItems));
    }

    private void setTextIfChanged(TextView view, String text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void saveLimits() {
        engine.setTorrentLimits(hash, parseLimit(downloadLimit), parseLimit(uploadLimit));
        Toast.makeText(this, R.string.task_limits_applied, Toast.LENGTH_SHORT).show();
    }

    private int parseLimit(TextInputEditText input) {
        try { return Math.max(0, Integer.parseInt(input.getText() == null ? "0" : input.getText().toString().trim())); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) text.append("\n\n");
            text.append(values.get(i));
        }
        return text.toString();
    }

    private void confirmDelete() {
        final boolean[] deleteFiles = {false};
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMultiChoiceItems(new String[]{getString(R.string.delete_files)}, null, (dialog, which, checked) -> deleteFiles[0] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    engine.remove(hash, deleteFiles[0]);
                    finish();
                }).show();
    }
}
