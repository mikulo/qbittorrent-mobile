package org.qbittorrent.mobile;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Local OS exit records only; never includes magnet URLs, tracker credentials or file names. */
final class ProcessDiagnostics {
    private static volatile String lastExit = "正在读取系统退出记录…";

    static void initialize(Context context) {
        new Thread(() -> {
            if (Build.VERSION.SDK_INT < 30) { lastExit = "系统不支持读取退出原因"; return; }
            try {
                ActivityManager manager = context.getSystemService(ActivityManager.class);
                List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 1);
                if (exits.isEmpty()) { lastExit = "没有历史退出记录"; return; }
                ApplicationExitInfo exit = exits.get(0);
                lastExit = "上次退出：" + DateFormat.getDateTimeInstance().format(new Date(exit.getTimestamp()))
                        + "\n原因：" + reason(exit.getReason()) + "（" + exit.getReason() + "）"
                        + "\n退出状态：" + exit.getStatus()
                        + " · 系统记录 RSS：" + exit.getRss() / 1024 + " MiB";
                Log.i("ProcessDiagnostics", lastExit);
                AppLog.info("previous_process_exit " + lastExit);
            } catch (Exception error) { lastExit = "系统未提供退出记录"; }
        }, "process-diagnostics").start();
    }

    static String summary() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + "\n当前进程 PID：" + Process.myPid() + "\n" + lastExit;
    }

    private static String reason(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR: return "应用无响应（ANR）";
            case ApplicationExitInfo.REASON_CRASH: return "Java 崩溃";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "原生引擎崩溃";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "系统内存不足回收";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "用户或系统请求结束";
            case ApplicationExitInfo.REASON_SIGNALED: return "进程收到终止信号";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "系统判定资源占用过高";
            default: return "其他系统原因";
        }
    }
}
