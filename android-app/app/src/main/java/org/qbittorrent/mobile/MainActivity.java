package org.qbittorrent.mobile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends AppCompatActivity implements TorrentEngine.Listener, TorrentAdapter.Actions {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TorrentEngine engine;
    private TorrentAdapter adapter;
    private View emptyState;
    private TextView transferSummary;
    private TorrentSnapshot.Group filter;
    private AlertDialog addDialog;
    private TorrentOptionsDialog torrentOptionsDialog;
    private boolean storageStarted;
    private final StoragePermission storagePermission = new StoragePermission(this, this::startWithStorage);

    private final ActivityResultLauncher<String[]> torrentPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    prepareTorrent(callback -> engine.prepareTorrent(uri, callback));
                }
            });

    private final ActivityResultLauncher<String> notificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) Toast.makeText(this, R.string.notification_permission, Toast.LENGTH_LONG).show();
            });

    private final UiRefresh refresh = new UiRefresh(1000, this::render);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.toolbar));
        engine = TorrentEngine.get(this);
        adapter = new TorrentAdapter(this);
        RecyclerView list = findViewById(R.id.torrent_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        // Transfer counters change continuously; avoid repeated whole-card cross-fade layers.
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        emptyState = findViewById(R.id.empty_state);
        transferSummary = findViewById(R.id.transfer_summary);
        findViewById(R.id.add_fab).setOnClickListener(v -> showAddDialog(null));
        findViewById(R.id.filter_all).setOnClickListener(v -> { filter = null; render(); });
        findViewById(R.id.filter_downloading).setOnClickListener(v -> { filter = TorrentSnapshot.Group.DOWNLOADING; render(); });
        findViewById(R.id.filter_seeding).setOnClickListener(v -> { filter = TorrentSnapshot.Group.SEEDING; render(); });
        findViewById(R.id.filter_paused).setOnClickListener(v -> { filter = TorrentSnapshot.Group.PAUSED; render(); });

        if (StoragePermission.hasAccess(this)) startWithStorage();
        else storagePermission.request();
    }

    private void startWithStorage() {
        if (engine == null || storageStarted || !StoragePermission.hasAccess(this)) return;
        storageStarted = true;
        ContextCompat.startForegroundService(this, new Intent(this, TorrentService.class));
        requestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (!StoragePermission.hasAccess(this)) storageStarted = false;
        else startWithStorage();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!StoragePermission.hasAccess(this)) { storageStarted = false; storagePermission.request(); }
        else if (!storageStarted) startWithStorage();
        else handleIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        engine.addListener(this);
        refresh.start();
    }

    @Override protected void onStop() {
        refresh.stop();
        engine.removeListener(this);
        super.onStop();
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        if ("magnet".equalsIgnoreCase(data.getScheme())) showAddDialog(data.toString());
        else if ("http".equalsIgnoreCase(data.getScheme()) || "https".equalsIgnoreCase(data.getScheme())) {
            prepareTorrent(callback -> engine.prepareTorrentUrl(data.toString(), callback));
        } else prepareTorrent(callback -> engine.prepareTorrent(data, callback));
    }

    private void showAddDialog(String initialValue) {
        if (!StoragePermission.hasAccess(this)) { storagePermission.request(); return; }
        View content = getLayoutInflater().inflate(R.layout.dialog_add, null);
        TextInputEditText input = content.findViewById(R.id.magnet_input);
        if (initialValue != null) input.setText(initialValue);
        content.findViewById(R.id.choose_file).setOnClickListener(v -> {
            if (addDialog != null) addDialog.dismiss();
            torrentPicker.launch(new String[]{"application/x-bittorrent", "application/octet-stream"});
        });
        addDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.add_torrent)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.add, null)
                .create();
        addDialog.setOnShowListener(ignored -> addDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String source = input.getText() == null ? "" : input.getText().toString().trim();
            if (!TorrentEngine.isSupportedMagnet(source) && !TorrentEngine.isSupportedTorrentUrl(source)) {
                input.setError(getString(R.string.invalid_torrent_source));
                return;
            }
            addDialog.dismiss();
            if (TorrentEngine.isSupportedMagnet(source)) prepareTorrent(callback -> engine.prepareMagnet(source, callback));
            else prepareTorrent(callback -> engine.prepareTorrentUrl(source, callback));
        }));
        addDialog.show();
    }

    private void prepareTorrent(PrepareAction action) {
        if (!StoragePermission.hasAccess(this)) { storagePermission.request(); return; }
        final boolean[] cancelled = {false};
        final String[] requestId = {null};
        AlertDialog loading = new AlertDialog.Builder(this)
                .setTitle(R.string.reading_torrent_info)
                .setMessage(R.string.waiting_torrent_metadata)
                .setNegativeButton(R.string.cancel, (dialog, which) -> {
                    cancelled[0] = true;
                    if (requestId[0] != null) engine.discardDraft(requestId[0]);
                })
                .create();
        loading.setCanceledOnTouchOutside(false);
        loading.setOnCancelListener(dialog -> {
            cancelled[0] = true;
            if (requestId[0] != null) engine.discardDraft(requestId[0]);
        });
        loading.show();
        requestId[0] = action.start(new TorrentEngine.PrepareCallback() {
            @Override public void onReady(TorrentDraft draft) {
                handler.post(() -> {
                    loading.dismiss();
                    if (cancelled[0] || isFinishing()) engine.discardDraft(draft.id);
                    else showTorrentOptions(draft);
                });
            }

            @Override public void onError(String message) {
                handler.post(() -> {
                    loading.dismiss();
                    if (!cancelled[0]) Toast.makeText(MainActivity.this,
                            getString(R.string.torrent_add_failed) + ": " + message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showTorrentOptions(TorrentDraft draft) {
        if (torrentOptionsDialog != null) torrentOptionsDialog.dismiss();
        TorrentOptionsDialog options = new TorrentOptionsDialog(this, draft,
                engine.configuredDownloadDirectory().getAbsolutePath(),
                (selected, down, up) -> {
                    engine.commitDraft(draft.id, selected, down, up);
                    Toast.makeText(this, R.string.torrent_added, Toast.LENGTH_SHORT).show();
                }, () -> engine.discardDraft(draft.id));
        torrentOptionsDialog = options;
        options.show();
    }

    @Override protected void onDestroy() {
        if (torrentOptionsDialog != null) torrentOptionsDialog.dismiss();
        super.onDestroy();
    }

    private void render() {
        List<TorrentSnapshot> all = engine.snapshots();
        ArrayList<TorrentSnapshot> visible = new ArrayList<>();
        for (TorrentSnapshot item : all) if (filter == null || item.group == filter) visible.add(item);
        adapter.submit(visible);
        emptyState.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        transferSummary.setText(getString(R.string.transfer_summary,
                Formatters.speed(engine.downloadRate()), Formatters.speed(engine.uploadRate()), all.size()));
    }

    @Override public void onEngineChanged() { refresh.request(); }

    @Override public void onEngineError(String message) {
        handler.post(() -> Toast.makeText(this, getString(R.string.torrent_add_failed) + ": " + message, Toast.LENGTH_LONG).show());
    }

    @Override public void onOpen(TorrentSnapshot item) {
        startActivity(new Intent(this, TorrentDetailActivity.class).putExtra(TorrentDetailActivity.EXTRA_HASH, item.hash));
    }

    @Override public void onToggle(TorrentSnapshot item) {
        if (item.group == TorrentSnapshot.Group.PAUSED) engine.resume(item.hash); else engine.pause(item.hash);
    }

    @Override public void onDelete(TorrentSnapshot item) { confirmDelete(item); }

    private void confirmDelete(TorrentSnapshot item) {
        final boolean[] deleteFiles = {false};
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMultiChoiceItems(new String[]{getString(R.string.delete_files)}, null,
                        (dialog, which, checked) -> deleteFiles[0] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> engine.remove(item.hash, deleteFiles[0]))
                .show();
    }

    private interface PrepareAction { String start(TorrentEngine.PrepareCallback callback); }
}
