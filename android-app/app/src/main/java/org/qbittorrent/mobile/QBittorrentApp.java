package org.qbittorrent.mobile;

import android.app.Application;

public final class QBittorrentApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.initialize(this);
        TorrentEngine.initialize(this);
        ProcessDiagnostics.initialize(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            public void onActivityCreated(android.app.Activity a, android.os.Bundle state) { AppLog.info("activity_create " + a.getClass().getSimpleName()); }
            public void onActivityStarted(android.app.Activity a) { AppLog.info("activity_start " + a.getClass().getSimpleName()); }
            public void onActivityResumed(android.app.Activity a) { AppLog.info("activity_resume " + a.getClass().getSimpleName()); }
            public void onActivityPaused(android.app.Activity a) { AppLog.info("activity_pause " + a.getClass().getSimpleName()); }
            public void onActivityStopped(android.app.Activity a) { AppLog.info("activity_stop " + a.getClass().getSimpleName()); }
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle state) {}
            public void onActivityDestroyed(android.app.Activity a) { AppLog.info("activity_destroy " + a.getClass().getSimpleName()); }
        });
    }

    @Override public void onTrimMemory(int level) {
        AppLog.warn("memory_trim level=" + level);
        super.onTrimMemory(level);
    }
}
