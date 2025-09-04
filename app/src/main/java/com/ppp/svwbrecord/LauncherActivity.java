package com.ppp.svwbrecord;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class LauncherActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // SharedPreferencesから、パスワード設定が完了したかどうかのフラグを取得
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean isPasswordSetupDone = prefs.getBoolean("isPasswordSetupDone", false); // デフォルトはfalse

        Intent intent;
        if (isPasswordSetupDone) {
            // 2回目以降の起動：直接MainActivityへ
            intent = new Intent(this, MainActivity.class);
        } else {
            // 初回起動：PasswordActivityへ
            intent = new Intent(this, PasswordActivity.class);
        }

        startActivity(intent);
        // このLauncherActivityは役目を終えたので閉じる
        finish();
    }
}