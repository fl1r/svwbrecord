package com.ppp.svwbrecord;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class PasswordActivity extends AppCompatActivity {

    private static final String SHARED_PASSWORD = BuildConfig.SHARED_PASSWORD;

    private EditText passwordEditText;
    private Button confirmButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password);

        passwordEditText = findViewById(R.id.password_edit_text);
        confirmButton = findViewById(R.id.confirm_button);

        confirmButton.setOnClickListener(v -> {
            String inputText = passwordEditText.getText().toString();

            if (SHARED_PASSWORD.equals(inputText)) {
                // パスワードが一致した場合

                // 1. SharedPreferencesに「設定完了」のフラグを保存
                SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean("isPasswordSetupDone", true).apply();

                // 2. メイン画面に遷移
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);

                // 3. このパスワード画面は閉じる
                finish();

            } else {
                // パスワードが不一致の場合
                Toast.makeText(this, "パスワードが違います", Toast.LENGTH_SHORT).show();
            }
        });
    }
}