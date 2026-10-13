# wakAnd — Google Play Store 掲載情報

---

## 簡単な説明（Short Description）

WakaTime連携でAndroidのアプリ使用時間を自動トラッキングするクライアントアプリ

---

## 詳細説明（Full Description）

### 有する機能

- **アプリ使用状況の自動トラッキング**: フォアグラウンドで使用中のアプリを検出し、WakaTime APIへHeartbeatを自動送信します
- **アプリ別トラッキング設定**: トラッキング対象のアプリを個別に有効/無効に切り替えられます。アプリごとにWakaTimeプロジェクト名を割り当てることも可能です
- **Chrome URL取得**: アクセシビリティサービスを利用して、Chromeで閲覧中のURLをHeartbeatのentityとして送信します
- **WakaTime OAuth連携**: 外部ブラウザで認可し、連携・再連携・解除を行えます。ユーザー情報、プロジェクト取得、自動記録、手動登録でOAuthを使用します
- **WakaTimeユーザー情報表示**: 取得できたユーザー名とダッシュボードへのリンクを表示します。情報取得失敗と未連携は区別します
- **手動時間登録**: プロジェクト・カテゴリ・時刻を指定して活動時間を登録できます
- **接続先切替**: WakaTimeと独自ホストを切り替えられます。独自ホストは既存のHTTPS API URLとAPIキーを使用し、アプリ別設定・プロジェクト割当も保持します
- **日本語/英語切替**: アプリ内で表示言語を切り替えられます
- **常駐通知**: フォアグラウンドサービスとして動作し、現在トラッキング中のアプリ名を通知バーに表示します。通知のON/OFFも切替可能です
- **新規アプリインストール通知**: 新しいアプリがインストールされた際に、トラッキング設定を促す通知を表示します

### 実装していないWakaTimeの機能

- ダッシュボード・統計情報の表示（グラフ・サマリー等）
- ゴール（目標）の設定・管理
- リーダーボード
- コーディング時間の詳細レポート
- チーム機能
- プラグイン連携（IDE等のエディタプラグインとの統合）
- オフライン時のHeartbeatキューイング・リトライ

### WakaTime依存について

公式WakaTimeでは **WakaTimeアカウントとOAuth認可** を使用します。WakaTimeは開発者向けの時間トラッキングサービスであり、本アプリはそのAPIクライアントとして動作します。WakaTime本体のダッシュボード（https://wakatime.com/dashboard）で記録されたデータを確認できます。独自ホストでは接続先のAPIキーを使用します。

OAuth認証バックエンドは https://yui.eng.pro/android/wakAnd/ でトークンの受け渡し・更新・失効を管理します。活動データはAndroidから選択したAPIホストへ直接送信します。認証情報の保存・解除時の保留処理などは[プライバシーポリシー](../privacy_policy_ja.txt)を参照してください。

---

## 提示すべきスクショのシナリオ

1. **メイン画面（WakaTime OAuth未連携状態）**
   - WakaTime選択トグルとOAuth連携案内が見える状態
   - 各種設定ボタン（使用状況アクセス許可・アクセシビリティ有効化・言語切替・アプリ一覧）が見える状態

2. **メイン画面（OAuth連携済み状態）**
   - WakaTimeユーザー名が表示されている状態
   - ダッシュボードリンクが表示されている状態

3. **アプリ一覧画面**
   - インストール済みアプリの一覧が表示され、各アプリのトラッキングON/OFFスイッチとプロジェクト名設定が見える状態

4. **通知バー（トラッキング中）**
   - フォアグラウンドサービスの常駐通知に、現在使用中のアプリ名が表示されている状態

5. **新規アプリインストール通知**
   - 新しいアプリがインストールされた際の通知表示

6. **言語切替（英語表示）**
   - 英語に切り替えた状態のメイン画面（日本語との対比用）

---

## English listing

### Short description

Track Android app and browser activity with WakaTime OAuth or a custom host.

### Full description

wakAnd automatically records activity in selected Android apps and browser URLs and sends it to WakaTime or a configured custom host. Enable tracking per app and assign projects. View tracking status in a persistent notification, or log offline time manually using a project, category and time range.

Use an external browser to authorize the official WakaTime service with OAuth. Connect, reconnect or disconnect from the app. User information, projects, automatic activity and manual entries use OAuth. A custom host uses its own saved HTTPS API URL and API key. Switching destinations retains app settings and project assignments.

Usage Access is needed for app tracking. Browser URL tracking uses the optional Accessibility Service, enabled through the disclosure and Android settings. English and Japanese are supported. Charts, goals, leaderboards and detailed reports are viewed through the service dashboard. Automatic activity collected while offline is not queued for later delivery.

The authorization backend at https://yui.eng.pro/android/wakAnd/ manages token delivery, refresh and revocation. Activity is sent directly from Android to the selected API host. See the [privacy policy](../privacy_policy.txt) for credential storage and disconnect handling.
