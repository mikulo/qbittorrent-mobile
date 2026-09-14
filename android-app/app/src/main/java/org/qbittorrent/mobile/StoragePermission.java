package org.qbittorrent.mobile;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/** Native libtorrent needs a real writable path, not just a document-tree URI grant. */
final class StoragePermission {
    private final AppCompatActivity activity;
    private final Runnable granted;
    private final ActivityResultLauncher<Intent> settings;
    private final ActivityResultLauncher<String> legacy;

    StoragePermission(AppCompatActivity activity, Runnable granted) {
        this.activity = activity;
        this.granted = granted;
        settings = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                result -> finishRequest());
        legacy = activity.registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                result -> finishRequest());
    }

    static boolean hasAccess(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    void request() {
        if (hasAccess(activity)) { granted.run(); return; }
        new AlertDialog.Builder(activity)
                .setTitle("允许访问公共下载目录")
                .setMessage("默认下载到 Download/qbittorrent，便于通过文件管理器或其他应用打开。"
                        + (Build.VERSION.SDK_INT >= 30
                        ? "原生下载引擎需要直接读写文件，请在系统设置中开启本应用的“所有文件访问权限”。此权限范围不只限于下载目录。"
                        : "请授予存储读写权限。")
                        + "拒绝后不会开始下载，也不会改用私有目录。")
                .setNegativeButton("暂不授权", null)
                .setPositiveButton("去授权", (dialog, which) -> openPermission())
                .show();
    }

    private void openPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    settings.launch(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + activity.getPackageName())));
                } catch (android.content.ActivityNotFoundException missing) {
                    settings.launch(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            } else {
                boolean asked = activity.getSharedPreferences("storage_permission", 0).getBoolean("asked", false);
                if (asked && !activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    settings.launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())));
                } else {
                    activity.getSharedPreferences("storage_permission", 0).edit().putBoolean("asked", true).apply();
                    legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
            }
        } catch (RuntimeException error) {
            Toast.makeText(activity, "无法打开权限页面，请在系统设置中为本应用授予存储权限。", Toast.LENGTH_LONG).show();
        }
    }

    private void finishRequest() {
        if (hasAccess(activity)) granted.run();
        else Toast.makeText(activity, "尚未取得存储权限，下载不会开始；可点击添加按钮重新授权。", Toast.LENGTH_LONG).show();
    }
}
