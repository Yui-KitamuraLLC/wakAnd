# #3 認証基盤実装と管理者確認

2026-10-13 JST。`wk1oauth` → `wk3impl` → `wk1oauth`向けPR。検収後に後続へ進む。

## 実装範囲

PHPのstart/launch/callback/complete/refresh/disconnect、端末・ブラウザ結合、token暗号化、
世代・行ロックによる排他、結果不明の隔離、解除・遅延成功の回収、回数制限、件数制限付き掃除。
AndroidはKeystore + noBackupFilesDir + AtomicFileで端末資格情報/access token/期限/世代を保存。
認可待ちと失効専用キューを再起動後も復元し、App Linkを開始済みlink_idと照合する。
再連携は新連携受取後に参照を切替え、旧連携を失効キューへ移す。

`OAuthManager.accessToken()`と`generation()`は#4の通常API向けアダプター。
既存WakaTimeClientの4 APIへの適用・送信直前の接続先世代検証は#4で実施する。
このPRだけで通常API・HeartbeatのOAuth対応完了とは扱わない。
最小の認可/解除画面を追加し、移行・エラー表示とWakaTime↔独自ホストトグルは#5へ引き継ぐ。
従来のAPIキー・URL・アプリ設定は本実装の保存領域と分離して保持する。

実測が必要な#10/#11/#13/#6と#2は未完了。#12は引き続き設計完了のclose状態。

## 本番準備の現在地

管理者申告: 初期4テーブル作成済み、version=1とチェックサム登録済み。
サーバーからの読み取り照合はまだ行っていない。既存DDLの再インポート・DROPは不要。
DB設定・暗号鍵は未設定、PHPからのDB接続・TLSは未確認。
開発用OAuthアプリ/DB/URLは未払い出し。自動試験はモック提供元と独立MySQL 8.0のみ。

## 管理者が行う追加設定（サーバーCLI不要）

標準手順を [ブラウザ・FFFTPでの管理者手順](browser-admin.md) に変更した。
提供元のCGI/PHP案内の実行パスは、SSH/対話CLIが利用可能という証拠にはならない。

- 既存の公開領域外oauth.phpを保持し、maintenance/environment/databaseをFFFTPで追加する。
- Windows PCで期限付き管理用認証ファイルを作り、FFFTPで非公開フォルダへ置く。
- 30分だけ有効なHTTPS管理画面から初期鍵を生成する。実鍵は非公開ファイルへ直接保存し、画面に出さない。
- oauth.phpはgenerated-keys.phpをrequireしてtoken_keys/rate_limit_keyを参照する。
- 同じ管理画面でPHP拡張/DB接続/TLS/schemaを読取診断し、必要な失効保留・期限切れ処理を明示実行する。
- 確認後は非公開admin-access.phpを削除し、管理画面が404に戻ることを確認する。
  生成した実鍵のgenerated-keys.phpは削除しない。

秘密値を出力せず、管理者によるFTP設定以外にDB接続情報を持ち出さない。
PHPの非公開フォルダへの書込権限と、実環境での管理画面動作は未確認。
本番DBへの外部接続・DDL再実行・DROPは行わない。CLI用スクリプトは利用可能な環境の補助手段として残す。

schema診断はversion・既知DDL checksum・MySQL 8.0・4 InnoDBテーブル・外部キーを照合する。
checksumは同じ001ファイルのLF/CRLFの2表現を許可する。SQLの内容が異なるものは503。
登録行が不一致なら既存構造と適用ファイルを管理者が照合し、無条件に登録し直さない。

## 配置・稼働開始

検収とPR merge前にmaintenance=trueを維持する。wk3impl push/PRでは本番転送しない。
wk1oauth/mainへmergeすると既存CIでFTP配置されるため、管理者設定の準備を先に済ませる。
公開物はindex.php/config-check.php/admin.php/.htaccessと秘密を含まない固定リストのPHPライブラリのみ。
admin.phpは非公開の期限付き認証ファイルがない/期限切れなら404。
Apacheは本ディレクトリ内のRewriteEngine・AllowOverrideを許可する必要がある。
CIでApacheの経路とライブラリ直接アクセス拒否を検証するが、実サーバーでの確認は別途必要。

- ルートの`oauth_ready:false`は互換維持した配置プローブ。稼働可否の判定には使わない。
- config-check.phpも従来OAuth登録値の読取だけ。本番DBやtoken交換の成功を意味しない。
- 新OAuth経路はmaintenance未設定/true、DB・鍵・checksum不整合で503になる。
- 全ファイル配置と診断が成功した後、管理者がmaintenance=falseへ切り替える。
- callbackアクセスログのquery（code/state）を記録しない設定・保持権限はサイト管理者が確認する。
  PHP自身は上流本文・SQL引数・Authorization・Cookie・URL全文をログへ出さない。
- 実際の外部ブラウザ→認可→Android復帰→受取→更新→失効は#13/#6で識別したテストアカウントを使う。
  tokenは記録しない。expires_in/明示timezone付きexpires_at・refresh token・実付与scopeを実測で確認する。
  未知応答やrefresh欠落を成功扱いにしない。旧refresh再利用の暗黙補完はしない。

更改時はmaintenance=trueにして処理中交換/更新の完了を確認する。互換性のないDDLは管理者だけが適用。
maintenance=true中の手動掃除は期限付き認証の管理画面から可能。最大20行＋外部失効1件で、成功フラグと件数だけ返す。
cron・MySQLイベントは必須にしない。無アクセス時の上流失効完了・物理削除日時は保証しない。
失われたtoken更新結果を回収できない場合、WakaTime側の連携管理で解除確認を行う。
端末Keystore鍵喪失時も既存アプリ設定は残る。読めなくなった連携の失効は提供元で確認する。

## 検証と検収の境界

- Python: 転送許可リスト・パス逸脱・秘密/schema除外。
- PHP: 構文、秘密設定の出力抑制、maintenance・鍵・TLSモード検証。
  管理用認証の期限・同一origin・Authorization、無効時404、秘密非出力、鍵の非公開保存・上書き拒否も検証。
- MySQL 8.0: DDLとschema gate、Cookie/state/verifier境界、暗号文/AAD差し替え、
  独立接続でのcallback/refresh競合、応答喪失後再取得、解除競合、結果不明後再送禁止、
  遅延成功の回収、失効保留・保持・制限。
- Apache: 経路rewrite・ライブラリ直接アクセス拒否・DDL非公開。
- Android: ビルド・JUnitの送信先/復帰URL検証、応答喪失とセッション失効後の受取回復、
  通信中のローカル解除と遅延応答による復活防止、再連携とオフライン失効キュー保持。
- 未実施: 本番PHP拡張/DB/TLS/権限、実WakaTime応答、実機App Links/Keystore/移行/通信断。
  CIのモック試験をこれらの実測成功として報告しない。

根拠: [WakaTime公式OAuth仕様](https://wakatime.com/developers#authentication)（2026-10-13確認）。
token endpointはJSONを明示要求、refreshにも登録redirect_uriを送信、revokeは対象tokenのみ。
all=true/user_idは送らない。
