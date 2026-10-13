# CLIを使わない管理者手順

2026-10-13 JST。FFFTP・ブラウザ・既存phpMyAdminを使用する。
公式[CGI/PHP案内](https://faq.interlink.or.jp/faq2/View/md.aspx?id=999&view)にはPHP実行パスがあるが、
SSH/対話CLIが利用可能とは確認できない。PHP実行パスの記載をシェルログイン可能の根拠にしない。
サーバー内CLIを必須とする以前の手順を、この手順で置き換える。

## 配置前の確認

- 本番初期4テーブルとversion/checksumは管理者申告で作成・登録済み。再インポート・DROPは不要。
- `~/.config/wakand/oauth.php`は既存の公開領域外の配置を保持する。
- 既存client_id/client_secret/redirect_uriを保持し、maintenance=trueとdatabase設定を追加する。
  `config/oauth.example.php`は構造の参照用。既存設定をひな形全体で上書きしない。
- DBパスワードは非公開ファイルだけに記入する。TLS modeは自動fallbackしない。
  提供元のCAと証明書・ホスト名検証を使える場合はrequired。
  使えない場合は提供元へ確認し、管理者が内部接続に依存する運用を明示採用するときだけprovider_internal。
  未確認のTLS仕様を成功扱いにしない。本番DBへPCやCIから直接接続しない。
- 秘密設定・鍵のフォルダは所有者とPHP実行ユーザーに必要な権限だけを付ける。
  公開PHP向けの755/705案内を秘密ファイルへそのまま適用しない。

## ブラウザ管理画面の有効化

`/android/wakAnd/admin.php`は、非公開admin-access.phpがない/期限切れの場合404。
画面・処理とも短期間だけ有効にする。鍵・DBパスワード・OAuth secretをURLへ渡さない。

1. 検収後の配置はmaintenance=trueのまま行う。PR/wk3impl pushは本番転送しない。
   mergeせず先に確認する場合は、管理者がPRの転送物を確認して対象フォルダだけへ手動配置する。
   `scripts/deploy.py`による転送物には秘密設定・実鍵・DDL・管理用認証ファイルは含まれない。
2. 管理者のWindows PCで `scripts/prepare-admin-access.ps1` を実行する。
   ローカルPowerShellを使うが、サーバーCLI・MySQLクライアントは不要。
   ユーザープロファイル下に本人だけが読める新しいフォルダを作り、
   `admin-access.php`（キーのSHA-256＋期限）と`access-key.txt`（管理キー）を保存する。
   キーそのものは標準出力に出ない。30分以内に後続操作を行う。
3. FFFTPで **admin-access.phpだけ** をサーバーの
   `/home/yui-kitamura/.config/wakand/admin-access.php` へ置く。権限600を基本にPHPの読取を確認する。
   FTP上の見え方は既存oauth.phpと同じ非公開フォルダを使う。`www/`内には置かない。
   access-key.txtはサーバーへ転送しない。
4. `https://yui.eng.pro/android/wakAnd/admin.php` をブラウザで開き、
   ローカルaccess-key.txtの内容をパスワード欄へ貼り付ける。チャットやログへ貼らない。
   リクエストは同一originのHTTPS POST＋Authorizationヘッダー。CORS・URL認証・Cookieログインは使わない。
   30分を過ぎたら新しい管理用認証ファイルを作成・置換する。

キー作成後も実設定や画面入力を含むスクリーンショットは共有せず、確認結果のフラグだけを共有する。

## 初期鍵生成と既存設定への追加

1. 管理画面の「非公開領域へ初期鍵を生成」を押す。
   PHPのrandom_bytesで暗号鍵と独立したrate limit鍵を生成し、
   `~/.config/wakand/generated-keys.php`へ権限600で保存する。画面はkeys_createdのみを返す。
   既存ファイルや既存鍵設定があれば拒否し、上書き・鍵ローテーションは行わない。
2. 既存oauth.phpの冒頭（returnより前）に下記を追加する。

```php
$privateKeys = require __DIR__ . '/generated-keys.php';
```

3. 既存wakand配列へ、値を直接表示・コピーせず下記2項目を追加する。

```php
'token_keys' => $privateKeys['token_keys'],
'rate_limit_key' => $privateKeys['rate_limit_key'],
```

4. environment/maintenance/databaseも既存wakand配列へ追加し、FFFTPでoauth.phpを更新する。
   保存している旧鍵がある場合はそのまま使い、新規生成しない。

PHPが非公開フォルダへ書けない場合はprivate_storage_unavailableになる。
管理者が所有者/グループ/必要最小の権限を確認する。公開領域へ鍵を置いたり、777で回避したりしない。
権限を調整できない場合は手順を止めて確認する。この制約が解消するまで稼働は開始しない。

## DB接続・schema確認と開始

1. 管理画面の「設定・DB接続を確認」を押す。
   extensionsはpdo_mysql/curl/sodium個別の真偽、configuration/database/schema/tls_encrypted、
   tls_mode/maintenance、処理中/期限超過の操作数、失効保留件数を返す。
   DBホスト・ユーザー・パスワード・鍵・token・例外本文は返さない。診断はDDL/DML/上流OAuthを実行しない。
   database_tls_advertisedは、サーバーの初期MySQL greetingがTLS対応を通知するかの真偽（取得不可はnull）。
   設定・鍵が未完成でも、指定済みDBホストへのサーバー内接続で確認できる。
   この測定はユーザー名・DBパスワードやクライアントパケットを送らず接続を閉じる。
   trueは証明書検証や暗号化接続成功を意味しない。実接続のtls_encryptedと分けて扱う。
2. configuration/database/schemaのtrueを確認する。
   requiredではtls_encrypted=trueも必要。provider_internalの暗号化有無は実測結果として記録する。
   schema不一致はphpMyAdminで既存構造・適用ファイル・登録済みchecksumを照合する。
   エラーを消すために再作成・DROP・無条件のバージョン登録はしない。
3. 配置・Apache経路・診断が確認できたら、管理者がFFFTPでmaintenance=falseへ変更する。
4. 管理画面を再利用しない場合、FFFTPでサーバーの非公開admin-access.phpを削除する。
   admin.phpが404へ戻ることを確認する。期限切れでも自動無効になる。
   ローカルのaccess-key.txt/期限切れ認証ファイルは不要になり次第管理者が削除する。
   **generated-keys.phpは稼働中の実鍵なので削除しない。** 鍵のバックアップはDBバックアップと別に非公開保管する。

実WakaTime認可・実機App Linksは#13/#6で検証する。診断成功をtoken交換成功と扱わない。

## 手動の失効保留・期限切れ処理

CLI/cronがなくても、通常リクエスト末尾の件数制限付き掃除は動く。
無アクセス時やmaintenance=true中に手動処理したい場合は、同じ期限付き認証で管理画面を有効にし、
「保留中の失効・期限切れを処理」を明示実行する。最大20行＋外部失効1件で、結果は成功フラグと保留件数だけ。
maintenance設定は変更しない。token交換・更新・任意SQL・コマンド実行・DDLは提供しない。
診断のoperations_in_progressが0になるまで更改を待ち、operations_expiredは本処理で回収した上で
結果不明・失効保留を確認する。互換性のないDB更改は管理者だけがphpMyAdminから行う。
MySQLイベントは任意の補助であり、外部HTTPS失効やtoken復号の代わりにはならない。

CLI用スクリプトはCLIが別途利用可能な環境の補助手段として残す。標準手順の必須条件にしない。

MySQL greetingの根拠: [Protocol::HandshakeV10](https://dev.mysql.com/doc/dev/mysql-server/latest/page_protocol_connection_phase_packets_protocol_handshake_v10.html)、
[CLIENT_SSL capability](https://dev.mysql.com/doc/dev/mysql-server/latest/group__group__cs__capabilities__flags.html)。
