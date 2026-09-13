package org.qbittorrent.mobile;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

public final class SettingsActivity extends AppCompatActivity implements TorrentEngine.Listener {
    private TorrentEngine engine;
    private SharedPreferences preferences;
    private TextInputEditText downloadLimit, uploadLimit, listenPort, proxyHost, proxyPort;
    private SwitchMaterial dht, lsd, upnp, natpmp, proxyEnabled, proxyHostnames, proxyPeers;
    private TextView downloadPath;
    private TextView logPath;
    private boolean selectingLogDirectory;
    private final UiRefresh refresh = new UiRefresh(2500, this::refreshDownloadPath);
    private final ActivityResultLauncher<Uri> directoryPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::onDirectorySelected);
    private final ActivityResultLauncher<String> legacyStoragePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) directoryPicker.launch(null);
                else Toast.makeText(this, R.string.storage_permission_required, Toast.LENGTH_LONG).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selectingLogDirectory = savedInstanceState != null && savedInstanceState.getBoolean("selectingLogDirectory");
        setContentView(R.layout.activity_settings);
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        engine = TorrentEngine.get(this);
        preferences = engine.preferences();
        downloadLimit = findViewById(R.id.download_limit);
        uploadLimit = findViewById(R.id.upload_limit);
        listenPort = findViewById(R.id.listen_port);
        dht = findViewById(R.id.enable_dht);
        lsd = findViewById(R.id.enable_lsd);
        upnp = findViewById(R.id.enable_upnp);
        natpmp = findViewById(R.id.enable_natpmp);
        proxyEnabled = findViewById(R.id.proxy_enabled);
        proxyHost = findViewById(R.id.proxy_host);
        proxyPort = findViewById(R.id.proxy_port);
        proxyHostnames = findViewById(R.id.proxy_hostnames);
        proxyPeers = findViewById(R.id.proxy_peer_connections);
        downloadLimit.setText(String.valueOf(preferences.getInt("download_limit", 0)));
        uploadLimit.setText(String.valueOf(preferences.getInt("upload_limit", 0)));
        listenPort.setText(String.valueOf(preferences.getInt("listen_port", 6881)));
        dht.setChecked(preferences.getBoolean("dht", true));
        lsd.setChecked(preferences.getBoolean("lsd", true));
        upnp.setChecked(preferences.getBoolean("upnp", true));
        natpmp.setChecked(preferences.getBoolean("natpmp", true));
        proxyEnabled.setChecked(preferences.getBoolean("proxy_enabled", false));
        proxyHost.setText(preferences.getString("proxy_host", ""));
        proxyPort.setText(String.valueOf(preferences.getInt("proxy_port", 1080)));
        proxyHostnames.setChecked(preferences.getBoolean("proxy_hostnames", true));
        proxyPeers.setChecked(preferences.getBoolean("proxy_peer_connections", true));
        TextView protocol = findViewById(R.id.protocol_summary);
        protocol.setText(getString(R.string.protocol_summary_runtime, engine.activePeerFingerprint(), engine.activeUserAgent(), engine.backendVersion()));
        downloadPath = findViewById(R.id.download_path);
        logPath = findViewById(R.id.log_path);
        refreshDownloadPath();
        findViewById(R.id.choose_download_path).setOnClickListener(v -> { selectingLogDirectory = false; chooseDownloadDirectory(); });
        findViewById(R.id.choose_log_path).setOnClickListener(v -> { selectingLogDirectory = true; chooseDownloadDirectory(); });
        findViewById(R.id.reset_log_path).setOnClickListener(v -> setLogDirectory(null));
        findViewById(R.id.reset_download_path).setOnClickListener(v -> engine.resetDownloadDirectory());
        findViewById(R.id.save).setOnClickListener(v -> save());
        ((TextView) findViewById(R.id.process_diagnostics)).setText(ProcessDiagnostics.summary());
    }

    @Override protected void onStart() {
        super.onStart();
        engine.addListener(this);
        refresh.start();
        refreshDownloadPath();
    }

    @Override protected void onStop() {
        engine.removeListener(this);
        refresh.stop();
        super.onStop();
    }

    @Override public void onEngineChanged() { refresh.request(); }

    @Override public void onEngineError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("selectingLogDirectory", selectingLogDirectory);
        super.onSaveInstanceState(state);
    }

    private void setLogDirectory(File directory) {
        AppLog.configureDirectory(directory, error -> {
            if (isDestroyed()) return;
            refreshDownloadPath();
            Toast.makeText(this, error == null ? getString(R.string.log_path_saved) : error, Toast.LENGTH_LONG).show();
        });
    }

    private void chooseDownloadDirectory() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Intent permission = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permission);
            Toast.makeText(this, R.string.enable_all_files_access, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            legacyStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        directoryPicker.launch(null);
    }

    private void onDirectorySelected(Uri treeUri) {
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            File directory = resolveExternalStorageTree(treeUri);
            if (directory == null) {
                Toast.makeText(this, R.string.unsupported_download_provider, Toast.LENGTH_LONG).show();
                return;
            }
            if (selectingLogDirectory) setLogDirectory(directory);
            else engine.changeDownloadDirectory(directory);
        } catch (Exception error) {
            Toast.makeText(this, getString(R.string.download_path_failed, error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private File resolveExternalStorageTree(Uri treeUri) {
        if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) return null;
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] parts = documentId.split(":", 2);
        String volume = parts[0];
        String relative = parts.length == 2 ? parts[1] : "";
        File root;
        if ("primary".equalsIgnoreCase(volume)) root = Environment.getExternalStorageDirectory();
        else {
            root = null;
            File[] appDirs = getExternalFilesDirs(null);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    if (appDir == null) continue;
                    String path = appDir.getAbsolutePath();
                    int marker = path.indexOf("/Android/data/");
                    if (marker > 0 && path.substring(0, marker).endsWith("/" + volume)) {
                        root = new File(path.substring(0, marker));
                        break;
                    }
                }
            }
            if (root == null) root = new File("/storage", volume);
        }
        return relative.isEmpty() ? root : new File(root, relative);
    }

    private void refreshDownloadPath() {
        if (logPath != null) logPath.setText(getString(R.string.log_path_value, AppLog.directorySummary()));
        try {
            downloadPath.setText(getString(R.string.download_path_value, engine.downloadDirectory().getAbsolutePath()));
        } catch (Exception error) {
            downloadPath.setText(getString(R.string.download_path_failed, error.getMessage()));
        }
    }

    private void save() {
        int port = parse(listenPort, 6881);
        if (port < 1024 || port > 65535) {
            listenPort.setError("请输入 1024–65535 之间的端口");
            return;
        }
        String host = proxyHost.getText() == null ? "" : proxyHost.getText().toString().trim();
        int socksPort = parse(proxyPort, 1080);
        if (proxyEnabled.isChecked() && host.isEmpty()) {
            proxyHost.setError(getString(R.string.proxy_host_required));
            return;
        }
        if (proxyEnabled.isChecked() && (socksPort < 1 || socksPort > 65535)) {
            proxyPort.setError(getString(R.string.proxy_port_invalid));
            return;
        }
        preferences.edit()
                .putInt("download_limit", Math.max(0, parse(downloadLimit, 0)))
                .putInt("upload_limit", Math.max(0, parse(uploadLimit, 0)))
                .putInt("listen_port", port)
                .putBoolean("dht", dht.isChecked())
                .putBoolean("lsd", lsd.isChecked())
                .putBoolean("upnp", upnp.isChecked())
                .putBoolean("natpmp", natpmp.isChecked())
                .putBoolean("proxy_enabled", proxyEnabled.isChecked())
                .putString("proxy_host", host)
                .putInt("proxy_port", socksPort)
                .putBoolean("proxy_hostnames", proxyHostnames.isChecked())
                .putBoolean("proxy_peer_connections", proxyPeers.isChecked())
                .apply();
        engine.applyPreferences();
        Toast.makeText(this, R.string.save, Toast.LENGTH_SHORT).show();
        finish();
    }

    private int parse(TextInputEditText input, int fallback) {
        try { return Integer.parseInt(input.getText() == null ? "" : input.getText().toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
