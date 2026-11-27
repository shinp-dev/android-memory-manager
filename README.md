# Memory Manager — Shinp Studio

Android用のローカルRAMモニター。root不要。アプリ別PSS取得と強制終了には内蔵shell権限サービス（Binder）を使います。ADBはその起動にのみ使用します。初回は端末自身とペア設定します。Shizuku・PC・root不要（APKビルドには開発環境が必要）。RAM全体表示・オーバーレイはADB接続なしでも利用できます。ADB通信先は127.0.0.1のみ。外部サーバーへのデータ送信・広告・常時スクリーンキャプチャはありません。

## 機能
- 総RAM、利用可能RAM、差分の概算使用量、メモリ逼迫状態を表示（3秒更新）。
- `dumpsys meminfo` の Total PSS by process を取得し、前後のPID/UID一覧とパッケージUIDを照合して集約。使用量降順、検索、詳細、手動更新。
- サードパーティアプリのみ、確認後に単体・選択一括強制終了。自アプリ、Shizuku、システムアプリ、既定ホームとIME、帰属不明プロセスは保護。
- オーバーレイは初期OFF。アプリのスイッチでON/OFF。ドラッグ移動、短押しで一覧を開く、長押しでOFF。通知からもOFF可能（通知の許可が必要）。OFFでサービスも停止。再起動時の自動復帰なし。
- オーバーレイの更新は端末のActivityManagerを利用し、重いPSS全件取得を定期実行しない。メイン画面の更新ボタン、接続直後、強制終了後のみPSS取得。

## ビルド（Windows）
JDK 17、Android SDK Platform 35、Build Tools 35.0.0 を用意する。Android StudioのSDK Managerで導入可能。展開したフォルダで以下を実行する。初回はGradleと依存ライブラリのダウンロードが必要。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Gradle 8.11.1を導入済みなら `gradle :app:assembleDebug` でも可。GitHub Actions設定も同梱しているので、リポジトリへpushすればdebug APKを生成するワークフローを利用できる（ワークフローの実行は未確認）。異なる署名で既にインストールされている場合は更新できない。

## 使い方
1. Wi-Fiに接続し、Androidの開発者向けオプションで「ワイヤレスデバッグ」をONにする。
2. Memory Managerの「サービス接続」を開き、Android設定とアプリを分割画面にする。設定で「ペア設定コードによるデバイスのペア設定」を開いたまま、アプリに6桁のコードを入力して「接続」。ペア用・接続用ポートは自動検出する。自動検出できない場合は設定画面の各ポートを入力する（ペア用ポートと接続用ポートは異なる）。
   - 認証鍵はアプリの非バックアップ領域に保存。次回起動時は常駐サービスへのBinder再接続を優先する。接続できなければワイヤレスデバッグをONにして、コードを空欄のまま「接続」を押す。
   - ペア設定を解除したい場合は、Androidのワイヤレスデバッグ画面で Memory Manager の登録を削除する。不要な場合はワイヤレスデバッグをOFFにする。
3. 必要な場合だけ「終了」またはチェックして一括終了。終了後に再取得。
4. RAMオーバーレイをONにし「他のアプリの上に表示」を許可。不要な場合はOFF。

## 制限と検証状況
- 内蔵ADBライブラリは第三者セキュリティ監査未実施。接続先をループバックに限定し、任意コマンド入力機能は提供しない。
- Android SDK・ADB実機がない作成環境のため、APKビルド、ADBペア設定・接続・強制終了、Android 17上での動作は未検証。Kotlin JVM上でPSS解析・ADB応答・UID照合・終了制御のテストを実行し、追加21シナリオが通過。Android SDKと依存取得先への接続がないためAPKのビルドは未実施。
- PSSは共有ページを按分した値。全RAM使用量との差がカーネル等を含むため、一覧合計とRAM全体は一致しない。利用可能RAMは空きRAMと同義ではない。
- PID/UID照合によって独自プロセス名にも対応する。共有UID、分離プロセス、他ユーザー、PID変動など帰属が不確かな項目は個別表示し終了不可。終了直前にUID・現在ユーザー・第三者アプリ・既定ホーム/IME・稼働状態を再照合する。OS上の同時変更による競合を完全には排除できない。
- force-stopは通知、同期、未保存データ等に影響しうる。終了指示の成功はメモリ解放量を保証しない。システムの自動メモリ管理を優先し、必要時だけ使用する。
- 端末再起動・ネットワーク切替後などにワイヤレスデバッグの再有効化が必要な場合がある。OSによるオーバーレイ非表示制限もある。個人利用・サイドロード向け。Google Play公開は未検討。
- CPU使用率やRAMの厳密なカーネル/キャッシュ内訳は初版に未実装。

## パーサーテスト
```bash
kotlinc app/src/main/java/com/shinpstudio/memorymanager/MemoryParser.kt app/src/main/java/com/shinpstudio/memorymanager/ShellFrame.kt app/src/main/java/com/shinpstudio/memorymanager/ProcessIdentity.kt app/src/main/java/com/shinpstudio/memorymanager/ShellMemoryService.kt tests/ParserTest.kt -include-runtime -d parser-test.jar
java -jar parser-test.jar
```

## 参考資料
- https://github.com/MuntashirAkon/libadb-android
- 内蔵ライブラリ: libadb-android 3.1.1、Conscrypt 2.5.3、Bouncy Castle 1.78.1（初回ビルド時に取得しAPKに梱包。実行時の追加インストール不要）。
- https://developer.android.com/tools/dumpsys
- https://developer.android.com/develop/background-work/services/fgs/service-types

## v0.2.1
変更点・残存リスク・実機チェックは `AUDIT.md` を参照。WindowsビルドとCIでユニットテストも実行する。

## v0.3.0: Wi-Fi切断後の利用（実機未検証）
- 初回または端末再起動後はWi-Fi接続中に「サービス接続」を押す。ペア設定済みなら全欄空白で起動できる。ポート自動検出失敗時は設定画面の接続用ポートを入力。
- ADBは app_process を別セッションで起動するためにのみ使用し、起動後は切断。以降は Binder IPC のためWi-FiやPCとの接続を必要としない。サービスがOSに停止されなければ4G/5G切替後も利用可能な設計。
- Binder受け渡しのContentProviderはDUMP権限およびshell UIDに限定。サービス側もアプリUIDを検証し、固定コマンドのみ許可。強制終了はサービス側でもUID・ユーザー・保護対象を再検証。大きなPSS応答はパイプで転送。ネットワーク待受なし。
- app_processの離脱、Android 17の非公開APIによるBinder受け渡し、Wi-Fi切断後の生存は未検証。失敗時はWi-Fiに再接続しサービスを起動。端末再起動後はサービス再起動が必要。logcatタグ MemoryShell。
- 実機試験: ①Wi-Fiで起動・一覧更新 ②Wi-FiをOFFにして4G/5Gで更新 ③テスト対象アプリを終了 ④アプリ画面を閉じて再起動し再接続 ⑤端末再起動後、Wi-Fiで起動 ⑥非許可UID・保護対象拒否。
