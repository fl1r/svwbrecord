# SVWB戦績記録アプリ

Shadowverse: Worlds Beyondの対戦結果を記録するための非公式Androidツールです。

**※ 一般利用者向けのインストール方法と使い方は、[こちらのWikiページ](https://github.com/fl1r/svwbrecord/wiki)を参照してください。**

## 技術スタック

* **言語**: Java
* **プラットフォーム**: Android (minSdk 33)
* **主なAPI**:
    * Google Sheets API v4
    * Google Sign-In for Android
* **主なAndroidコンポーネント**:
    * Foreground Service
    * WindowManager (Overlay)
    * UsageStatsManager

## ビルド方法

1.  `gradle.properties`ファイルに、以下のプロパティを追加してください。
    * `MY_SPREADSHEET_ID_FOR_SELF`: 開発用のスプレッドシートID
    * `MY_SPREADSHEET_ID_FOR_FRIENDS`: 配布用のスプレッドシートID

2.  コマンドラインから以下のコマンドを実行します。
    * **自分用ビルド**: `.\gradlew installDebug`
    * **友人用ビルド**: `.\gradlew installDebug -Ptarget=friends`
