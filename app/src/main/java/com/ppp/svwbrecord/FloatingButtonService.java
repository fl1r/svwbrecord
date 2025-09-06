package com.ppp.svwbrecord;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

public class FloatingButtonService extends Service {
    private static final String TAG = "FloatingButtonService"; // ログ用タグ

    private WindowManager windowManager;
    private View floatingButtonView;
    private WindowManager.LayoutParams params;
    private Button simpleButton; // 操作対象のボタン

    private static final int FLOATING_BUTTON_NOTIFICATION_ID = 3;
    private static final String FLOATING_BUTTON_CHANNEL_ID = "FloatingButtonChannel";

    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;
    private boolean isInDragMode = false;
    private boolean wasDraggedAfterLongPress = false; // 長押し後にドラッグされたか

    private float initialTouchX, initialTouchY; // ACTION_DOWN時の画面上の生の座標
    private int initialParamsX, initialParamsY; // ACTION_DOWN時のWindowManager.LayoutParamsの座標
    private float touchSlop; // クリックとスワイプを区別するための閾値

    private String signedInAccountName;

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called");
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");
        createNotificationChannel();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");

        if (intent != null && intent.hasExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME)) {
            this.signedInAccountName = intent.getStringExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME);
            Log.d(TAG, "Received account name: " + this.signedInAccountName);
        }

        Notification notification = createServiceNotification();
        startForeground(FLOATING_BUTTON_NOTIFICATION_ID, notification);

        if (floatingButtonView != null) {
            Log.d(TAG, "Floating button view already exists.");
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Creating new floating button view.");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingButtonView = LayoutInflater.from(this).inflate(R.layout.floating_button_layout, null);
        simpleButton = floatingButtonView.findViewById(R.id.simple_button);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0; // 初期位置 X
        params.y = 100; // 初期位置 Y

        try {
            windowManager.addView(floatingButtonView, params);
            Log.d(TAG, "Floating button view added to WindowManager.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding floatingButtonView to WindowManager: " + e.getMessage(), e);
            floatingButtonView = null; // エラーが発生したら null に戻す
            stopSelf(); // サービスも停止
            return START_NOT_STICKY;
        }

        simpleButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "Simple button clicked. Starting OverlayService and stopping self.");
                Intent overlayIntent = new Intent(FloatingButtonService.this, OverlayService.class);

                // ★修正点：保持しているアカウント名を Intent に追加する
                if (signedInAccountName != null && !signedInAccountName.isEmpty()) {
                    overlayIntent.putExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, signedInAccountName);
                    startService(overlayIntent);
                    stopSelf(); // OverlayServiceを開始したら、自身（FloatingButtonService）を停止する
                } else {
                    Log.e(TAG, "Account name is missing. Cannot start OverlayService.");
                    Toast.makeText(FloatingButtonService.this, "アカウント情報がありません", Toast.LENGTH_SHORT).show();
                    // ここで stopSelf() を呼ぶかは仕様による
                }
            }
        });

        simpleButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        initialParamsX = params.x;
                        initialParamsY = params.y;
                        wasDraggedAfterLongPress = false; 
                        isInDragMode = false; 

                        longPressRunnable = new Runnable() {
                            @Override
                            public void run() {
                                isInDragMode = true; 
                                Log.d(TAG, "Long press detected. Drag mode enabled.");
                            }
                        };
                        longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                        return true; 

                    case MotionEvent.ACTION_MOVE:
                        float currentX = event.getRawX();
                        float currentY = event.getRawY();
                        float deltaX = currentX - initialTouchX;
                        float deltaY = currentY - initialTouchY;

                        if (isInDragMode) { 
                            params.x = initialParamsX + (int) deltaX;
                            params.y = initialParamsY + (int) deltaY;
                            windowManager.updateViewLayout(floatingButtonView, params);
                            wasDraggedAfterLongPress = true;
                        } else {
                            if (Math.sqrt(deltaX * deltaX + deltaY * deltaY) > touchSlop) {
                                longPressHandler.removeCallbacks(longPressRunnable);
                                Log.d(TAG, "Movement detected before long press. Long press cancelled.");
                            }
                        }
                        return true; 

                    case MotionEvent.ACTION_UP:
                        longPressHandler.removeCallbacks(longPressRunnable);
                        Log.d(TAG, "Action UP. isInDragMode: " + isInDragMode + ", wasDraggedAfterLongPress: " + wasDraggedAfterLongPress);
                        if (isInDragMode) { 
                            isInDragMode = false;
                            return true;
                        } else {
                            float moveDeltaX = event.getRawX() - initialTouchX;
                            float moveDeltaY = event.getRawY() - initialTouchY;
                            if (Math.sqrt(moveDeltaX * moveDeltaX + moveDeltaY * moveDeltaY) < touchSlop) {
                                Log.d(TAG, "Tap detected. Performing click.");
                                v.performClick();
                                return true; 
                            } else {
                                Log.d(TAG, "Swipe detected (not a tap, long press cancelled).");
                                return true; 
                            }
                        }

                    case MotionEvent.ACTION_CANCEL:
                        Log.d(TAG, "Action CANCEL.");
                        longPressHandler.removeCallbacks(longPressRunnable);
                        isInDragMode = false;
                        wasDraggedAfterLongPress = false;
                        return true; 
                }
                return false; 
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called");
        if (windowManager != null && floatingButtonView != null) {
            Log.d(TAG, "Removing floating button view from WindowManager.");
            try {
                windowManager.removeView(floatingButtonView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing floatingButtonView: " + e.getMessage(), e);
            }
            floatingButtonView = null;
            simpleButton = null;
        }
        if(longPressHandler != null) {
            longPressHandler.removeCallbacksAndMessages(null);
        }
        stopForeground(true);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                FLOATING_BUTTON_CHANNEL_ID,
                "フローティングボタン サービス",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("フローティングボタンの表示状態を通知します");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createServiceNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, FLOATING_BUTTON_CHANNEL_ID)
                .setContentTitle("フローティングボタン")
                .setContentText("タップして操作")
                .setSmallIcon(R.drawable.ic_launcher_foreground) 
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
