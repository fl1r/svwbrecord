package com.ppp.svwbrecord;

import android.Manifest;
import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport; // Ensure this is imported
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    public static final String PREFS_NAME = "AppPrefs";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PLAYER_NAMES_LIST = "player_names_list";
    public static final String KEY_DECK_NAMES_LIST = "deck_names_list";
    public static final String KEY_DISPLAY_MODE = "display_mode";
    public static final String MODE_NOTIFICATION = "notification";
    public static final String MODE_FLOATING_BUTTON = "floating_button";
    public static final String KEY_SIGNED_IN_ACCOUNT_NAME = "signed_in_account_name";

    public static final String KEY_DECK_SORT_ORDER_LIST = "deck_sort_order_list";

    private static final String SHEET_NAME_FOR_SORTING = "デッキ別戦績";
    private static final String SORTING_COLUMN = "A";

    private static final int REQUEST_OVERLAY_PERMISSION = 101;
    private static final int REQUEST_POST_NOTIFICATIONS_PERMISSION = 102;
    private static final int RC_SIGN_IN = 9001;

    private Spinner playerNameSpinner;
    private ArrayAdapter<String> playerNameAdapter;
    private List<String> playerNamesList = new ArrayList<>();
    private RadioGroup displayModeRadioGroup;
    private Button saveButton;
    private SignInButton signInButton;
    private Button signOutButton;
    private TextView signedInStatusText;

    private Button forceStopButton;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private static final String SPREADSHEET_ID = BuildConfig.SPREADSHEET_ID;
    private static final String SHEET_NAME_FOR_LISTS = "デッキリスト一覧";
    private static final String PLAYER_NAME_COLUMN = "C";
    private static final String DECK_NAME_COLUMN = "A";
    protected static final String DEFAULT_PLAYER_PROMPT = "（プレイヤーを選択）";

    private GoogleSignInClient mGoogleSignInClient;
    private GoogleAccountCredential credential;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        signInButton = findViewById(R.id.sign_in_button);
        signOutButton = findViewById(R.id.sign_out_button);
        signedInStatusText = findViewById(R.id.signed_in_status_text);

        playerNameSpinner = findViewById(R.id.player_name_spinner);
        displayModeRadioGroup = findViewById(R.id.display_mode_radio_group);
        saveButton = findViewById(R.id.save_button);
        forceStopButton = findViewById(R.id.force_stop_button);

        playerNamesList = new ArrayList<>();
        playerNameAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, playerNamesList);
        playerNameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        playerNameSpinner.setAdapter(playerNameAdapter);

        credential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Collections.singleton(SheetsScopes.SPREADSHEETS));

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS))
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        signInButton.setOnClickListener(v -> signIn());
        signOutButton.setOnClickListener(v -> signOut());

        loadSettings();

        saveButton.setOnClickListener(v -> {
            if (GoogleSignIn.getLastSignedInAccount(this) == null) {
                Toast.makeText(this, "Googleアカウントでサインインしてください", Toast.LENGTH_SHORT).show();
                return;
            }
            saveSettings();
            checkPermissionsAndStartServices();
        });

        forceStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 1. 全てのサービスを停止する
                stopService(new Intent(MainActivity.this, GameWatchService.class));
                stopService(new Intent(MainActivity.this, FloatingButtonService.class));
                stopService(new Intent(MainActivity.this, OverlayService.class));

                // 2. アプリのタスクを完全に終了させる
                finishAndRemoveTask();
            }
        });

        updateUI(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        checkPreviouslySignedIn();
    }

    private void checkPreviouslySignedIn() {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
        if (account != null) {
            Log.d(TAG, "Previously signed in as: " + account.getEmail());
            if (account.getAccount() != null) {
                credential.setSelectedAccount(account.getAccount());
                updateUI(account);
                loadSpreadsheetData();
            } else {
                 Log.w(TAG, "GoogleSignInAccount.getAccount() is null, cannot set credential. Attempting sign out.");
                 signOut();
                 updateUI(null);
            }
        } else {
            Log.d(TAG, "Not previously signed in.");
            updateUI(null);
        }
    }

    private void signIn() {
        Log.d(TAG, "Attempting to sign in.");
        Intent signInIntent = mGoogleSignInClient.getSignInIntent();
        startActivityForResult(signInIntent, RC_SIGN_IN);
    }

    private void signOut() {
        Log.d(TAG, "Attempting to sign out.");
        mGoogleSignInClient.signOut()
                .addOnCompleteListener(this, task -> {
                    Toast.makeText(MainActivity.this, "サインアウトしました", Toast.LENGTH_SHORT).show();
                    credential.setSelectedAccountName(null);
                    updateUI(null);
                    playerNamesList.clear();
                    playerNamesList.add(DEFAULT_PLAYER_PROMPT);
                    playerNameAdapter.notifyDataSetChanged();
                    if (!playerNamesList.isEmpty()) playerNameSpinner.setSelection(0);
                });
    }

    private void updateUI(@Nullable GoogleSignInAccount account) {
        if (account != null) {
            signedInStatusText.setText("サインイン中: " + account.getEmail());
            signInButton.setVisibility(View.GONE);
            signOutButton.setVisibility(View.VISIBLE);
            playerNameSpinner.setEnabled(true);
            saveButton.setEnabled(true);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString(KEY_SIGNED_IN_ACCOUNT_NAME, account.getEmail()).apply();

        } else {
            signedInStatusText.setText("未サインイン");
            signInButton.setVisibility(View.VISIBLE);
            signOutButton.setVisibility(View.GONE);
            playerNameSpinner.setEnabled(false);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_SIGNED_IN_ACCOUNT_NAME).apply();
        }
    }

    private void loadSpreadsheetData() {
        if (credential.getSelectedAccountName() == null && credential.getSelectedAccount() == null) {
            Log.w(TAG, "Not signed in or credential account not set. Cannot load spreadsheet data.");
            return;
        }

        Log.d(TAG, "Attempting to load spreadsheet data with OAuth...");

        if (SPREADSHEET_ID.isEmpty() || SPREADSHEET_ID.equals("YOUR_SPREADSHEET_ID")) {
            Log.e(TAG, "Spreadsheet ID is not set.");
            Toast.makeText(this, "スプレッドシートIDが設定されていません", Toast.LENGTH_LONG).show();
            return;
        }

        executorService.execute(() -> {
            try {
                HttpTransport httpTransport = new NetHttpTransport(); // MODIFIED HERE
                Sheets sheetsService = new Sheets.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential)
                        .setApplicationName(getApplicationInfo().loadLabel(getPackageManager()).toString())
                        .build();

                Log.d(TAG, "Fetching player names...");
                List<String> fetchedPlayerNames = fetchSpreadsheetColumn(sheetsService, SPREADSHEET_ID, SHEET_NAME_FOR_LISTS, PLAYER_NAME_COLUMN);
                Log.d(TAG, "Fetching deck names...");
                List<String> fetchedDeckNames = fetchSpreadsheetColumn(sheetsService, SPREADSHEET_ID, SHEET_NAME_FOR_LISTS, DECK_NAME_COLUMN);

                List<String> fetchedSortOrder = fetchSpreadsheetColumn(sheetsService, SPREADSHEET_ID, SHEET_NAME_FOR_SORTING, SORTING_COLUMN);

                mainThreadHandler.post(() -> {
                    Log.d(TAG, "Spreadsheet data fetched. Player names count: " + (fetchedPlayerNames != null ? fetchedPlayerNames.size() : 0) +
                            ", Deck names count: " + (fetchedDeckNames != null ? fetchedDeckNames.size() : 0));
                    if (fetchedPlayerNames != null) {
                        savePlayerNamesToPrefs(fetchedPlayerNames);
                        updatePlayerNameSpinnerAndSelection(fetchedPlayerNames);
                    }
                    if (fetchedDeckNames != null) {
                        saveDeckNamesToPrefs(fetchedDeckNames);
                    }
                    if (fetchedSortOrder != null) {
                        saveDeckSortOrderToPrefs(fetchedSortOrder);
                    }
                    if (fetchedPlayerNames != null || fetchedDeckNames != null) {
                        Toast.makeText(MainActivity.this, "スプレッドシートからデータを更新しました", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error fetching spreadsheet data with OAuth", e);
                mainThreadHandler.post(() -> Toast.makeText(MainActivity.this, "データ読み込みエラー(認証確認): " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
    private void saveDeckSortOrderToPrefs(List<String> sortOrder) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        // Listは直接保存できないため、カンマ区切りの文字列に変換して保存
        String sortOrderString = String.join(",", sortOrder);
        prefs.edit().putString(KEY_DECK_SORT_ORDER_LIST, sortOrderString).apply();
        Log.d(TAG, "Deck sort order saved to SharedPreferences.");
    }
    private List<String> fetchSpreadsheetColumn(Sheets sheetsService, String spreadsheetId, String sheetName, String columnName) throws IOException {
        Log.d(TAG, "fetchSpreadsheetColumn (OAuth): sheet=" + sheetName + ", col=" + columnName);
        String range = sheetName + "!" + columnName + "1:" + columnName;
        Log.d(TAG, "Fetching range: " + range);

        ValueRange response = sheetsService.spreadsheets().values()
                .get(spreadsheetId, range)
                .execute();

        List<List<Object>> values = response.getValues();
        List<String> columnData = new ArrayList<>();
        if (values != null) {
            for (List<Object> row : values) {
                if (row != null && !row.isEmpty() && row.get(0) != null && !row.get(0).toString().trim().isEmpty()) {
                    columnData.add(row.get(0).toString().trim());
                }
            }
        }
        Log.d(TAG, "Fetched " + columnData.size() + " items for " + columnName + " from " + sheetName);
        return columnData;
    }

    private void savePlayerNamesToPrefs(List<String> playerNames) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_PLAYER_NAMES_LIST, new HashSet<>(playerNames));
        editor.apply();
        Log.d(TAG, "Player names saved to SharedPreferences.");
    }

    private void saveDeckNamesToPrefs(List<String> deckNames) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putStringSet(KEY_DECK_NAMES_LIST, new HashSet<>(deckNames));
        editor.apply();
        Log.d(TAG, "Deck names saved to SharedPreferences.");
    }

    private List<String> loadPlayerNamesFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Set<String> playerNamesSet = prefs.getStringSet(KEY_PLAYER_NAMES_LIST, new HashSet<>());
        List<String> loadedPlayerNames = new ArrayList<>(playerNamesSet);
        if (!loadedPlayerNames.contains(DEFAULT_PLAYER_PROMPT) && !loadedPlayerNames.isEmpty()) {
            loadedPlayerNames.add(0,DEFAULT_PLAYER_PROMPT);
        } else if (loadedPlayerNames.isEmpty()){
            loadedPlayerNames.add(DEFAULT_PLAYER_PROMPT);
        }
        Log.d(TAG, "Player names loaded from SharedPreferences: " + loadedPlayerNames.size() + " items");
        return loadedPlayerNames;
    }

     private void updatePlayerNameSpinnerAndSelection(List<String> newPlayerNames) {
        String previouslySelectedPlayer = playerNameSpinner.getSelectedItem() instanceof String ? (String) playerNameSpinner.getSelectedItem() : null;
        if (previouslySelectedPlayer == null || previouslySelectedPlayer.equals(DEFAULT_PLAYER_PROMPT)){
             SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
             previouslySelectedPlayer = prefs.getString(KEY_USERNAME, DEFAULT_PLAYER_PROMPT);
        }

        playerNamesList.clear();
        if (newPlayerNames != null) {
            playerNamesList.addAll(newPlayerNames);
        }
        if (!playerNamesList.contains(DEFAULT_PLAYER_PROMPT) && !playerNamesList.isEmpty()) {
             playerNamesList.add(0, DEFAULT_PLAYER_PROMPT);
        } else if (playerNamesList.isEmpty()) {
            playerNamesList.add(DEFAULT_PLAYER_PROMPT);
        }
        playerNameAdapter.notifyDataSetChanged();

        int selectionIndex = 0;
        if (!playerNamesList.isEmpty()) {
            if (previouslySelectedPlayer != null) {
                int spinnerPosition = playerNamesList.indexOf(previouslySelectedPlayer);
                if (spinnerPosition >= 0) {
                    selectionIndex = spinnerPosition;
                    Log.d(TAG, "Restored selection for player: " + previouslySelectedPlayer);
                } else {
                    Log.d(TAG, "Previously selected player '" + previouslySelectedPlayer + "' not in new list. Selecting prompt.");
                }
            } else {
                 Log.d(TAG, "No previous player selection, selecting prompt.");
            }
            playerNameSpinner.setSelection(selectionIndex);
        } else {
            Log.d(TAG, "Player names list is empty after update.");
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        List<String> loadedPlayerNames = loadPlayerNamesFromPrefs();
        updatePlayerNameSpinnerAndSelection(loadedPlayerNames);

        String displayMode = prefs.getString(KEY_DISPLAY_MODE, MODE_NOTIFICATION);
        if (MODE_FLOATING_BUTTON.equals(displayMode)) {
            displayModeRadioGroup.check(R.id.radio_floating_button);
        } else {
            displayModeRadioGroup.check(R.id.radio_notification);
        }
        Log.d(TAG, "Settings loaded. Player: " + prefs.getString(KEY_USERNAME, "") + ", Mode: " + displayMode);
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (playerNameSpinner.getSelectedItem() != null &&
            !playerNameSpinner.getSelectedItem().toString().equals(DEFAULT_PLAYER_PROMPT)) {
            editor.putString(KEY_USERNAME, playerNameSpinner.getSelectedItem().toString());
        } else {
            editor.remove(KEY_USERNAME);
        }

        int selectedId = displayModeRadioGroup.getCheckedRadioButtonId();
        if (selectedId == R.id.radio_notification) {
            editor.putString(KEY_DISPLAY_MODE, MODE_NOTIFICATION);
        } else if (selectedId == R.id.radio_floating_button) {
            editor.putString(KEY_DISPLAY_MODE, MODE_FLOATING_BUTTON);
        }
        editor.apply();
        Log.d(TAG, "Settings saved. Player: " + prefs.getString(KEY_USERNAME, "null") + ", Mode: " + prefs.getString(KEY_DISPLAY_MODE, ""));
    }

    private void checkPermissionsAndStartServices() {
        Intent gameWatchServiceIntent = new Intent(MainActivity.this, GameWatchService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(gameWatchServiceIntent);
        } else {
            startService(gameWatchServiceIntent);
        }
        Log.d(TAG, "GameWatchService started from checkPermissionsAndStartServices.");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS_PERMISSION);
                return; 
            }
        }
        proceedWithServiceStartup();
    }

    private void proceedWithServiceStartup() {
        String displayMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DISPLAY_MODE, MODE_NOTIFICATION);
        Log.d(TAG, "proceedWithServiceStartup: Display mode = " + displayMode);

        if (MODE_FLOATING_BUTTON.equals(displayMode)) {
            startFloatingButtonService();
        } else {
            stopFloatingButtonService();
            Toast.makeText(MainActivity.this, "設定を保存しました。通知モードです。", Toast.LENGTH_SHORT).show();
        }
    }

    private void startFloatingButtonService() {
        if (GoogleSignIn.getLastSignedInAccount(this) == null) {
            Toast.makeText(this, "フローティングボタンを使用するにはGoogleアカウントでサインインしてください", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            Toast.makeText(this, "フローティングボタンを表示するには、他のアプリの上に表示する権限を許可してください。", Toast.LENGTH_LONG).show();
        } else {
            Intent serviceIntent = new Intent(this, FloatingButtonService.class);
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
            if (account != null && account.getEmail() != null) {
                 serviceIntent.putExtra(KEY_SIGNED_IN_ACCOUNT_NAME, account.getEmail());
                 ContextCompat.startForegroundService(this, serviceIntent);
                 Log.d(TAG, "FloatingButtonService started or already running with account: " + account.getEmail());
                 Toast.makeText(MainActivity.this, "設定を保存しました。フローティングボタンモードです。", Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "Signed in account email is null, cannot pass to FloatingButtonService");
                Toast.makeText(this, "サインインアカウント情報が取得できませんでした。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopFloatingButtonService() {
        Intent serviceIntent = new Intent(this, FloatingButtonService.class);
        stopService(serviceIntent);
        Log.d(TAG, "FloatingButtonService stopped.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
            handleSignInResult(task);
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            String displayMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_DISPLAY_MODE, MODE_NOTIFICATION);
            if (MODE_FLOATING_BUTTON.equals(displayMode)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "オーバーレイ権限が付与されました。", Toast.LENGTH_SHORT).show();
                    startFloatingButtonService();
                } else {
                    Toast.makeText(this, "フローティングボタンの表示権限が許可されませんでした。", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void handleSignInResult(Task<GoogleSignInAccount> completedTask) {
        try {
            GoogleSignInAccount account = completedTask.getResult(ApiException.class);
            Log.d(TAG, "signInResult:success, user: " + (account != null ? account.getEmail() : "null"));
            if (account != null && account.getAccount() != null) {
                credential.setSelectedAccount(account.getAccount());
                updateUI(account);
                loadSpreadsheetData();
            } else {
                 Log.w(TAG, "signInResult:success but GoogleSignInAccount or its internal account is null.");
                 updateUI(null);
            }
        } catch (ApiException e) {
            Log.w(TAG, "signInResult:failed code=" + e.getStatusCode());
            Toast.makeText(this, "サインインに失敗しました: " + e.getStatusCode(), Toast.LENGTH_LONG).show();
            updateUI(null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                proceedWithServiceStartup();
            } else {
                Toast.makeText(this, "通知の権限が許可されませんでした。", Toast.LENGTH_LONG).show();
                proceedWithServiceStartup(); 
            }
        }
    }
}
