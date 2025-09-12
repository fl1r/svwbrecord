package com.ppp.svwbrecord;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport; // Ensure this is imported
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {

    private static final String TAG = "OverlayService";

    public static final String KEY_LAST_USED_DECK = "last_used_deck";
    // 一時的な入力状態を保存するためのキー
    private static final String PREFS_DRAFT_NAME = "OverlayDraftPrefs";
    private static final String KEY_DRAFT_MY_DECK_POS = "draft_my_deck_pos";
    private static final String KEY_DRAFT_OPPONENT_DECK_POS = "draft_opponent_deck_pos";
    private static final String KEY_DRAFT_OPPONENT_RANK_POS = "draft_opponent_rank_pos";
    private static final String KEY_DRAFT_TURN_ID = "draft_turn_id";
    private static final String KEY_DRAFT_WIN_LOSS_ID = "draft_win_loss_id";

    private static final int MAX_RETRIES = 3;
    private static final long IMMEDIATE_RETRY_DELAY_MS = 2000; // 2秒
    private static final long DELAYED_RETRY_DELAY_MS = 3 * 60 * 1000; // 3分

    private WindowManager windowManager;
    private View overlayView;

    private Spinner myDeckSpinner;
    private Spinner opponentDeckSpinner;
    private Spinner opponentRankSpinner;
    private RadioGroup turnRadioGroup;
    private RadioGroup winLossRadioGroup;

    private Button recordButton;

    private ArrayAdapter<String> myDeckAdapter;
    private ArrayAdapter<String> opponentDeckAdapter;
    private ArrayAdapter<String> opponentRankAdapter;

    private List<String> myDeckNamesList = new ArrayList<>();
    private List<String> opponentDeckNamesList = new ArrayList<>();
    private List<String> opponentRankList = new ArrayList<>();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private static final String SPREADSHEET_ID = BuildConfig.SPREADSHEET_ID;
    private GoogleAccountCredential credential;
    private String signedInAccountName;



    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate called");

        credential = GoogleAccountCredential.usingOAuth2(
                getApplicationContext(), Collections.singleton(SheetsScopes.SPREADSHEETS));
        Log.d(TAG, "GoogleAccountCredential initialized.");

        myDeckAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, myDeckNamesList);
        myDeckAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        opponentDeckAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, opponentDeckNamesList);
        opponentDeckAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        opponentRankAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, opponentRankList);
        opponentRankAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");

        if (intent != null && intent.hasExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME)) {
            signedInAccountName = intent.getStringExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME);
            if (signedInAccountName != null && !signedInAccountName.isEmpty()) {
                credential.setSelectedAccountName(signedInAccountName);
                Log.d(TAG, "Using Google Account for Sheets API: " + signedInAccountName);
            } else {
                Log.e(TAG, "Signed-in account name from intent is null or empty.");
                Toast.makeText(this, "認証アカウント情報がありません。", Toast.LENGTH_LONG).show();
                stopSelf();
                return START_NOT_STICKY;
            }
        } else {
            Log.e(TAG, "Intent or signed-in account name extra is missing.");
            Toast.makeText(this, "認証情報がサービスに渡されませんでした。", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (overlayView != null) {
            Log.d(TAG, "OverlayView already exists.");
            return START_NOT_STICKY;
        }

        Log.d(TAG, "Creating new OverlayView");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager is null");
            stopSelf();
            return START_NOT_STICKY;
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null);
        if (overlayView == null) {
            Log.e(TAG, "Failed to inflate overlay_layout.");
            stopSelf();
            return START_NOT_STICKY;
        }

        myDeckSpinner = overlayView.findViewById(R.id.my_deck_spinner);
        opponentDeckSpinner = overlayView.findViewById(R.id.opponent_deck_spinner);
        opponentRankSpinner = overlayView.findViewById(R.id.opponent_rank_spinner);
        turnRadioGroup = overlayView.findViewById(R.id.turn_radio_group);
        winLossRadioGroup = overlayView.findViewById(R.id.win_loss_radio_group);
        recordButton = overlayView.findViewById(R.id.record_button);

        setupUIElements();

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        try {
            windowManager.addView(overlayView, params);
            Log.d(TAG, "OverlayView added.");
        } catch (Exception e) {
            Log.e(TAG, "Error adding overlayView: " + e.getMessage(), e);
            overlayView = null; 
            stopSelf();
            return START_NOT_STICKY;
        }

        Button recordButton = overlayView.findViewById(R.id.record_button);
        if (recordButton != null) {
            recordButton.setOnClickListener(v -> {
                Log.d(TAG, "Record button clicked.");
                collectInputDataAndRecord();
            });
        } else {
            Log.e(TAG, "Record button not found!");
        }

        Button closeButton = overlayView.findViewById(R.id.close_button);
        if (closeButton != null) {
            closeButton.setOnClickListener(v -> {
                Log.d(TAG, "Close button clicked.");
                saveDraftState();
                stopSelf();
            });
        } else {
            Log.e(TAG, "Close button not found!");
        }

        return START_NOT_STICKY;
    }

    private void saveDraftState() {
        SharedPreferences draftPrefs = getSharedPreferences(PREFS_DRAFT_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = draftPrefs.edit();

        editor.putInt(KEY_DRAFT_MY_DECK_POS, myDeckSpinner.getSelectedItemPosition());
        editor.putInt(KEY_DRAFT_OPPONENT_DECK_POS, opponentDeckSpinner.getSelectedItemPosition());
        editor.putInt(KEY_DRAFT_OPPONENT_RANK_POS, opponentRankSpinner.getSelectedItemPosition());
        editor.putInt(KEY_DRAFT_TURN_ID, turnRadioGroup.getCheckedRadioButtonId());
        editor.putInt(KEY_DRAFT_WIN_LOSS_ID, winLossRadioGroup.getCheckedRadioButtonId());

        editor.apply();
        Log.d(TAG, "Draft state saved.");
    }

    private void setupUIElements() {
        Log.d(TAG, "Setting up UI elements...");
        loadDeckNames();
        loadOpponentRanks();

        if (myDeckSpinner != null) {
            myDeckSpinner.setAdapter(myDeckAdapter);
            SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            String lastUsedDeck = prefs.getString(KEY_LAST_USED_DECK, null);
            if (lastUsedDeck != null) {
                int spinnerPosition = myDeckAdapter.getPosition(lastUsedDeck);
                if (spinnerPosition >= 0) {
                    myDeckSpinner.setSelection(spinnerPosition);
                    Log.d(TAG, "Restored last used deck selection: " + lastUsedDeck);
                }
            }
        }
        else Log.e(TAG, "myDeckSpinner is null");

        if (opponentDeckSpinner != null) opponentDeckSpinner.setAdapter(opponentDeckAdapter);
        else Log.e(TAG, "opponentDeckSpinner is null");

        if (opponentRankSpinner != null) {
            opponentRankSpinner.setAdapter(opponentRankAdapter);
            opponentRankSpinner.setSelection(opponentRankAdapter.getCount() - 1);
        }

        // 保存された前回の入力履歴を復元する
        restoreDraftState();

        RadioGroup.OnCheckedChangeListener radioListener = (group, checkedId) -> validateInputs();
        turnRadioGroup.setOnCheckedChangeListener(radioListener);
        winLossRadioGroup.setOnCheckedChangeListener(radioListener);

        AdapterView.OnItemSelectedListener spinnerListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                validateInputs();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                validateInputs();
            }
        };
        myDeckSpinner.setOnItemSelectedListener(spinnerListener);
        opponentDeckSpinner.setOnItemSelectedListener(spinnerListener);
        opponentRankSpinner.setOnItemSelectedListener(spinnerListener);

        // ★追加：初期状態をチェック
        validateInputs();
        Log.d(TAG, "UI elements setup complete.");
    }

    private void restoreDraftState() {
        SharedPreferences draftPrefs = getSharedPreferences(PREFS_DRAFT_NAME, Context.MODE_PRIVATE);

        // 各スピナーの選択位置を復元
        if (draftPrefs.contains(KEY_DRAFT_MY_DECK_POS)) {
            myDeckSpinner.setSelection(draftPrefs.getInt(KEY_DRAFT_MY_DECK_POS, 0));
        }
        if (draftPrefs.contains(KEY_DRAFT_OPPONENT_DECK_POS)) {
            opponentDeckSpinner.setSelection(draftPrefs.getInt(KEY_DRAFT_OPPONENT_DECK_POS, 0));
        }
        opponentRankSpinner.setSelection(draftPrefs.getInt(KEY_DRAFT_OPPONENT_RANK_POS, opponentRankAdapter.getCount() - 1));

        // 各ラジオボタンの選択状態を復元 (-1は未選択)
        int turnId = draftPrefs.getInt(KEY_DRAFT_TURN_ID, -1);
        if (turnId != -1) {
            turnRadioGroup.check(turnId);
        }
        int winLossId = draftPrefs.getInt(KEY_DRAFT_WIN_LOSS_ID, -1);
        if (winLossId != -1) {
            winLossRadioGroup.check(winLossId);
        }
        Log.d(TAG, "Draft state restored.");
    }

    private void validateInputs() {
        if (recordButton == null) return;

        boolean isTurnSelected = turnRadioGroup.getCheckedRadioButtonId() != -1;
        boolean isWinLossSelected = winLossRadioGroup.getCheckedRadioButtonId() != -1;

        // 全ての条件が満たされた場合のみボタンを有効化
        recordButton.setEnabled(isTurnSelected && isWinLossSelected);
    }

    private void loadDeckNames() {
        Log.d(TAG, "--- Start Loading Deck Names ---");
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);

        // ① 元となる全デッキリストを読み込む
        Set<String> savedDeckNamesSet = prefs.getStringSet(MainActivity.KEY_DECK_NAMES_LIST, new HashSet<>());
        List<String> loadedDeckNames = new ArrayList<>(savedDeckNamesSet);
        Log.d(TAG, "Loaded " + loadedDeckNames.size() + " decks from master list.");

        // ② 並び替えの基準となるリストを読み込む
        String sortOrderString = prefs.getString(MainActivity.KEY_DECK_SORT_ORDER_LIST, "");

        if (!sortOrderString.isEmpty()) {
            List<String> sortOrderList = new ArrayList<>(Arrays.asList(sortOrderString.split(",")));
            Log.d(TAG, "Found custom sort order list with " + sortOrderList.size() + " items.");
            // デバッグ用に読み込んだ並び順リストの内容をログに出力
            Log.d(TAG, "Sort Order List: " + sortOrderList.toString());

            loadedDeckNames.sort((deck1, deck2) -> {
                int index1 = sortOrderList.indexOf(deck1);
                int index2 = sortOrderList.indexOf(deck2);

                // デバッグ用にどのデッキがどの位置にあるかログに出力
                if (index1 == -1) {
                    Log.w(TAG, "Deck not found in sort list (will be at end): '" + deck1 + "'");
                }
                if (index2 == -1) {
                    Log.w(TAG, "Deck not found in sort list (will be at end): '" + deck2 + "'");
                }

                if (index1 == -1) index1 = Integer.MAX_VALUE;
                if (index2 == -1) index2 = Integer.MAX_VALUE;
                return Integer.compare(index1, index2);
            });
            Log.d(TAG, "Deck names sorted by custom order.");

        } else {
            Log.w(TAG, "Custom sort order not found. Sorting alphabetically as a fallback.");
            Collections.sort(loadedDeckNames);
        }

        // ③ UIに反映
        myDeckNamesList.clear();
        opponentDeckNamesList.clear();

        myDeckNamesList.add("（自分のデッキを選択）");
        opponentDeckNamesList.add("（相手のデッキを選択）");

        if (!loadedDeckNames.isEmpty()) {
            myDeckNamesList.addAll(loadedDeckNames);
            opponentDeckNamesList.addAll(loadedDeckNames);
        }

        myDeckAdapter.notifyDataSetChanged();
        opponentDeckAdapter.notifyDataSetChanged();
        Log.d(TAG, "--- Finish Loading Deck Names ---");
    }

    private void loadOpponentRanks() {
        Log.d(TAG, "Loading opponent ranks...");
        opponentRankList.clear();
        opponentRankList.addAll(Arrays.asList("BEYOND", "LEGEND", "ULTIMATE", "EPIC", "EPIC未満", "ダイヤモンド", "サファイア", "ルビー", "(ランク選択)"));
        opponentRankAdapter.notifyDataSetChanged();
        Log.d(TAG, "Opponent ranks loaded. Adapter notified.");
    }

    private void collectInputDataAndRecord() {
        Log.d(TAG, "Collecting input data and attempting to record with OAuth...");
        
        if (recordButton != null) {
            recordButton.setEnabled(false);
        }
        
        if (credential.getSelectedAccountName() == null) {
            mainThreadHandler.post(() -> Toast.makeText(OverlayService.this, "Googleアカウント認証情報がありません。", Toast.LENGTH_LONG).show());
            Log.e(TAG, "Google Account not set in credential for OverlayService. Aborting record.");
            return;
        }

        String myDeck = (myDeckSpinner != null && myDeckSpinner.getSelectedItem() != null) ? myDeckSpinner.getSelectedItem().toString() : "";
        String opponentDeck = (opponentDeckSpinner != null && opponentDeckSpinner.getSelectedItem() != null) ? opponentDeckSpinner.getSelectedItem().toString() : "";
        String opponentRank = (opponentRankSpinner != null && opponentRankSpinner.getSelectedItem() != null) ? opponentRankSpinner.getSelectedItem().toString() : "";
        String turn = "";
        String winLoss = "";

        if (opponentRank.equals("(ランク選択)")) opponentRank = "";

        if (turnRadioGroup != null) {
            int selectedTurnId = turnRadioGroup.getCheckedRadioButtonId();
            if (selectedTurnId == R.id.radio_first_turn) turn = "先攻";
            else if (selectedTurnId == R.id.radio_second_turn) turn = "後攻";
            else { turn = "未選択"; Log.w(TAG, "Turn not selected."); }
        } else Log.w(TAG, "turnRadioGroup is null.");

        if (winLossRadioGroup != null) {
            int selectedWinLossId = winLossRadioGroup.getCheckedRadioButtonId();
            if (selectedWinLossId == R.id.radio_win) winLoss = "勝ち";
            else if (selectedWinLossId == R.id.radio_loss) winLoss = "負け";
            else { winLoss = "未選択"; Log.w(TAG, "Win/Loss not selected."); }
        } else Log.w(TAG, "winLossRadioGroup is null.");

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String playerNameForSheet = prefs.getString(MainActivity.KEY_USERNAME, null);

        if (playerNameForSheet == null || playerNameForSheet.isEmpty() || playerNameForSheet.equals(MainActivity.DEFAULT_PLAYER_PROMPT)) {
            mainThreadHandler.post(() -> Toast.makeText(OverlayService.this, "プレイヤー名が設定されていません。", Toast.LENGTH_LONG).show());
            Log.e(TAG, "Player name (for sheet) not selected or invalid.");
            return;
        }

        String recordDate = new SimpleDateFormat("MM/dd", Locale.getDefault()).format(new Date());

        Log.i(TAG, "Collected Data: SheetName=" + playerNameForSheet + ", Date=" + recordDate +
                ", OpponentRank=" + opponentRank + ", MyDeck=" + myDeck +
                ", Turn=" + turn + ", OpponentDeck=" + opponentDeck + ", WinLoss=" + winLoss +
                " (Authenticated as: " + credential.getSelectedAccountName() + ")");

        if (SPREADSHEET_ID.isEmpty() || SPREADSHEET_ID.equals("YOUR_SPREADSHEET_ID")) {
            mainThreadHandler.post(() -> Toast.makeText(OverlayService.this, "スプレッドシートIDが設定されていません", Toast.LENGTH_LONG).show());
            Log.e(TAG, "Spreadsheet ID is not set.");
            return;
        }

        final String sheetName = playerNameForSheet;
        final List<Object> rowData = Arrays.asList(recordDate, opponentRank, myDeck, turn, opponentDeck, winLoss);

        // 最後に使ったデッキを保存
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_USED_DECK, myDeck).apply();

        // ★リトライ処理を開始
        attemptToRecordWithRetries(sheetName, rowData, 1, false);
    }

    /**
     * リトライロジックを管理するメソッド
     * @param sheetName 書き込み先のシート名
     * @param rowData 書き込むデータ
     * @param attemptCount 現在の試行回数
     * @param isDelayedRetry 遅延リトライフェーズかどうか
     */
    private void attemptToRecordWithRetries(final String sheetName, final List<Object> rowData, final int attemptCount, final boolean isDelayedRetry) {
        executorService.execute(() -> {
            try {
                // ★実際の書き込み処理を呼び出す
                performSheetUpdate(sheetName, rowData);

                // 成功した場合
                mainThreadHandler.post(() -> {
                    Toast.makeText(getApplicationContext(), "対戦記録を保存しました！", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Match record successfully saved.");
                    clearDraftState();
                    stopSelf();
                });

            } catch (UserRecoverableAuthIOException | GoogleJsonResponseException e) {
                // ★リトライ不可能なエラー (認証やAPI権限の問題)
                Log.e(TAG, "Unrecoverable API error.", e);
                mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "記録保存エラー(認証等): " + e.getMessage(), Toast.LENGTH_LONG).show());

            } catch (IOException e) {
                // ★リトライ可能なネットワークエラー
                Log.w(TAG, "Network error on attempt " + attemptCount + " (isDelayed=" + isDelayedRetry + ")", e);

                if (attemptCount < MAX_RETRIES) {
                    // --- 即時リトライまたは遅延リトライの継続 ---
                    try {
                        if (!isDelayedRetry) {
                            mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "記録に失敗、リトライします... (" + attemptCount + "/" + MAX_RETRIES + ")", Toast.LENGTH_SHORT).show());
                            Thread.sleep(IMMEDIATE_RETRY_DELAY_MS);
                        } else {
                            mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "バックグラウンドでリトライ中... (" + attemptCount + "/" + MAX_RETRIES + ")", Toast.LENGTH_SHORT).show());
                            Thread.sleep(IMMEDIATE_RETRY_DELAY_MS);
                        }
                        attemptToRecordWithRetries(sheetName, rowData, attemptCount + 1, isDelayedRetry);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    if (!isDelayedRetry) {
                        // --- 即時リトライが上限に達したので、遅延リトライへ移行 ---
                        Log.w(TAG, "Immediate retries failed. Scheduling delayed retries.");
                        mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "ネットワークが不安定です。3分後にバックグラウンドで再試行します。", Toast.LENGTH_LONG).show());

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            attemptToRecordWithRetries(sheetName, rowData, 1, true);
                        }, DELAYED_RETRY_DELAY_MS);

                    } else {
                        // --- 全てのリトライが失敗 ---
                        Log.e(TAG, "All retry attempts failed.", e);
                        mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                }
            } catch (Exception e) {
                // その他の予期せぬエラー
                Log.e(TAG, "An unexpected error occurred during record attempt.", e);
                mainThreadHandler.post(() -> Toast.makeText(getApplicationContext(), "予期せぬエラー: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /**
     * 実際にGoogle Sheetsへの書き込みを行うメソッド
     */
    private void performSheetUpdate(String sheetName, List<Object> rowData) throws IOException {
        HttpTransport httpTransport = new NetHttpTransport();
        Sheets sheetsService = new Sheets.Builder(httpTransport, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName(getApplicationInfo().loadLabel(getPackageManager()).toString())
                .build();

        // 最終行を探す
        final String searchRange = sheetName + "!A3:A";
        ValueRange response = sheetsService.spreadsheets().values().get(SPREADSHEET_ID, searchRange).execute();
        List<List<Object>> values = response.getValues();
        int targetRow = 3 + (values != null ? values.size() : 0);
        Log.d(TAG, "Target row for update is: " + targetRow);

        // データ書き込み
        final String updateRange = sheetName + "!A" + targetRow + ":G" + targetRow;
        ValueRange body = new ValueRange().setValues(Arrays.asList(rowData));
        sheetsService.spreadsheets().values()
                .update(SPREADSHEET_ID, updateRange, body)
                .setValueInputOption("USER_ENTERED")
                .execute();
    }

    private void clearDraftState() {
        SharedPreferences draftPrefs = getSharedPreferences(PREFS_DRAFT_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = draftPrefs.edit();

        editor.remove(KEY_DRAFT_OPPONENT_DECK_POS);
        editor.remove(KEY_DRAFT_OPPONENT_RANK_POS);
        editor.remove(KEY_DRAFT_TURN_ID);
        editor.remove(KEY_DRAFT_WIN_LOSS_ID);

        editor.apply();
        Log.d(TAG, "Draft state cleared, keeping my deck selection.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy called.");
        if (overlayView != null && windowManager != null) {
            Log.d(TAG, "Removing overlayView.");
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlayView: " + e.getMessage(), e);
            }
            overlayView = null;
        }

        Log.d(TAG, "Requesting GameWatchService to refresh UI state.");
        // GameWatchServiceにUIの更新を依頼する
        Intent refreshIntent = new Intent(this, GameWatchService.class);
        refreshIntent.setAction(GameWatchService.ACTION_REFRESH_UI);
        startService(refreshIntent);

        // 1. 現在の設定を SharedPreferences から読み込む
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        String displayMode = prefs.getString(MainActivity.KEY_DISPLAY_MODE, MainActivity.MODE_NOTIFICATION);

        // 2. フローティングボタンモードの場合のみ、サービスを再起動する
        if (MainActivity.MODE_FLOATING_BUTTON.equals(displayMode)) {
            Log.d(TAG, "Floating button mode detected. Requesting to restore FloatingButtonService.");
            Intent floatingButtonIntent = new Intent(this, FloatingButtonService.class);
            if (signedInAccountName != null) {
                floatingButtonIntent.putExtra(MainActivity.KEY_SIGNED_IN_ACCOUNT_NAME, signedInAccountName);
            }
            startService(floatingButtonIntent);
        } else {
            Log.d(TAG, "Notification mode detected. Not restoring FloatingButtonService.");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called");
        return null;
    }
}
