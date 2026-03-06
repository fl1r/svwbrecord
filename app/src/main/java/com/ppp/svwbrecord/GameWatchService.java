package com.ppp.svwbrecord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class GameWatchService extends Service {

    private static final String TAG = "GameWatchService";

    public static final String ACTION_START_MONITORING = "com.ppp.svwbrecord.ACTION_START_MONITORING";
    public static final String ACTION_STOP_MONITORING = "com.ppp.svwbrecord.ACTION_STOP_MONITORING";
    public static final String ACTION_REFRESH_UI = "com.ppp.svwbrecord.ACTION_REFRESH_UI";
    public static boolean isRunning = false;

    private static final String TARGET_PACKAGE_NAME = "jp.co.cygames.ShadowverseWorldsBeyond";
    private static final String MY_APP_PACKAGE_NAME = "com.ppp.svwbrecord";
    private static final int NOTIFICATION_ID = 1;
    private static final int GAME_NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "GameWatchChannel";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable runnable;
    private boolean isUiVisible = false;
    private boolean isMonitoringEnabled = false;

    // バッテリー最適化: 画面ON/OFFを検知
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.d(TAG, "Screen OFF: Pausing monitoring");
                pauseMonitoringLoop();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                Log.d(TAG, "Screen ON: Resuming monitoring if enabled");
                if (isMonitoringEnabled) {
                    resumeMonitoringLoop();
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        createNotificationChannel();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createServiceNotification());

        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_START_MONITORING.equals(action)) {
                isMonitoringEnabled = true;
                resumeMonitoringLoop();
            } else if (ACTION_STOP_MONITORING.equals(action)) {
                isMonitoringEnabled = false;
                stopSelf();
            } else if (ACTION_REFRESH_UI.equals(action)) {
                isUiVisible = false;
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        unregisterReceiver(screenReceiver);
        pauseMonitoringLoop();
        hideGameNotification();
        stopService(new Intent(this, FloatingButtonService.class));
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void resumeMonitoringLoop() {
        if (runnable != null) {
            handler.removeCallbacks(runnable);
        }

        // 画面が非アクティブなら開始しない
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return;

        runnable = new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                handler.postDelayed(this, 2000);
            }
        };
        handler.post(runnable);
        Log.d(TAG, "Monitoring loop started");
    }

    private void pauseMonitoringLoop() {
        if (runnable != null) {
            handler.removeCallbacks(runnable);
            Log.d(TAG, "Monitoring loop paused");
        }
    }

    private void checkForegroundApp() {
        UsageStatsManager usm = (UsageStatsManager) this.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);

        if (appList != null && !appList.isEmpty()) {
            SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                sortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!sortedMap.isEmpty()) {
                UsageStats lastUsedAppStats = sortedMap.get(sortedMap.lastKey());
                if (lastUsedAppStats != null && lastUsedAppStats.getPackageName() != null) {
                    String currentApp = lastUsedAppStats.getPackageName();
                    
                    // シンプルな判定: ターゲットアプリか否か
                    boolean shouldBeVisible = TARGET_PACKAGE_NAME.equals(currentApp) || MY_APP_PACKAGE_NAME.equals(currentApp);

                    if (shouldBeVisible && !isUiVisible) {
                        Log.i(TAG, "App detected (" + currentApp + "). SHOWING UI...");
                        isUiVisible = true;
                        handleGameStart();
                    } else if (!shouldBeVisible && isUiVisible) {
                        Log.i(TAG, "App lost focus (current: " + currentApp + "). HIDING UI...");
                        isUiVisible = false;
                        handleGameStop();
                    }
                }
            }
        }
    }

    private void handleGameStart() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String displayMode = prefs.getString(MainActivity.KEY_DISPLAY_MODE, MainActivity.MODE_NOTIFICATION);

        if (MainActivity.MODE_FLOATING_BUTTON.equals(displayMode)) {
            Intent intent = new Intent(this, FloatingButtonService.class);
            String accountName = prefs.getString(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, null);
            if (accountName != null) intent.putExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, accountName);
            startService(intent);
        } else {
            showGameNotification();
        }
    }

    private void handleGameStop() {
        hideGameNotification();
        stopService(new Intent(this, FloatingButtonService.class));
    }

    private void showGameNotification() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String accountName = prefs.getString(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, null);
        Intent overlayIntent = new Intent(this, OverlayService.class);
        if (accountName != null) overlayIntent.putExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, accountName);

        PendingIntent pendingIntent = PendingIntent.getService(this, 0, overlayIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_game_active))
                .setContentText(getString(R.string.notification_text_game_active))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(GAME_NOTIFICATION_ID, notification);
    }

    private void hideGameNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(GAME_NOTIFICATION_ID);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification createServiceNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title_waiting))
                .setContentText(getString(R.string.notification_text_waiting))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }
}
