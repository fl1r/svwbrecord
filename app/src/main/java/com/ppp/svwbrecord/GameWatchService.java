package com.ppp.svwbrecord;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class GameWatchService extends Service {

    private static final String TARGET_PACKAGE_NAME = "jp.co.cygames.ShadowverseWorldsBeyond";

    private static final int NOTIFICATION_ID = 1;
    private static final int GAME_NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "GameWatchChannel";

    private Handler handler = new Handler();
    private Runnable runnable;
    private boolean isGameRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        Notification notification = createServiceNotification();
        startForeground(NOTIFICATION_ID, notification);

        // 監視ループを開始
        runnable = new Runnable() {
            @Override
            public void run() {
                checkForegroundApp();
                handler.postDelayed(this, 2000); // 2秒後にもう一度実行
            }
        };
        handler.post(runnable); // 最初の実行

        return START_STICKY;
    }

    private void checkForegroundApp() {
        UsageStatsManager usm = (UsageStatsManager) this.getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        List<UsageStats> appList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);

        if (appList != null && appList.size() > 0) {
            SortedMap<Long, UsageStats> sortedMap = new TreeMap<>();
            for (UsageStats usageStats : appList) {
                sortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (sortedMap.size() > 0) {
                String currentApp = sortedMap.get(sortedMap.lastKey()).getPackageName();

                if (TARGET_PACKAGE_NAME.equals(currentApp)) {
                    if (!isGameRunning) {
                        isGameRunning = true;
                        handleGameStart();
                    }
                } else {
                    if (isGameRunning) {
                        isGameRunning = false;
                        handleGameStop(); // ★修正点: handleGameStart() から handleGameStop() に変更
                    }
                }
            }
        }
    }
    /**
     * ゲーム開始時の処理
     */
    private void handleGameStart() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String displayMode = prefs.getString(MainActivity.KEY_DISPLAY_MODE, MainActivity.MODE_NOTIFICATION);

        if (MainActivity.MODE_FLOATING_BUTTON.equals(displayMode)) {
            // フローティングボタンを表示するサービスを開始
            Intent intent = new Intent(this, FloatingButtonService.class);
            String accountName = prefs.getString(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, null);
            if (accountName != null) {
                intent.putExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, accountName);
                startService(intent);

                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                Log.e("GameWatchService", "Account name not found for FloatingButtonService.");
            }

        } else {
            // 通知を表示
            showGameNotification();
        }
    }

    /**
     * ゲーム終了時の処理
     */
    private void handleGameStop() {
        // 通知を消す (どちらのモードでも共通)
        hideGameNotification();

        // フローティングボタンを消すサービスを停止
        Intent fbIntent = new Intent(this, FloatingButtonService.class); // 変数名をintentからfbIntentに変更
        stopService(fbIntent);

        Notification notification = createServiceNotification();
        startForeground(NOTIFICATION_ID, notification);
    }

    private void showGameNotification() {
        Intent overlayIntent = new Intent(this, OverlayService.class); // 変数名をintentからoverlayIntentに変更
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, overlayIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("シャドウバースWB プレイ中")
                .setContentText("タップして戦績を記録")
                .setSmallIcon(R.drawable.ic_launcher_foreground) // 事前にアイコン画像を準備
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

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d("GameWatchService", "Task removed, stopping service.");
        // サービス自体を停止する
        // これにより、関連するすべてのUI（通知、フローティングボタン）が停止・消滅する
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(runnable); // サービス終了時にループを止める
        // サービスが破棄される際にも表示されている可能性のあるUIを消す
        handleGameStop();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- 通知関連のヘルパーメソッド ---
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Game Watch Service", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("ゲームの起動を監視します");
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createServiceNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("戦績記録サービス")
                .setContentText("待機中...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .build();
    }
}
