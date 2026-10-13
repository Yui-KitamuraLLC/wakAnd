# phpMyAdminでの初期DB導入

本番の初期化・更改は管理者がphpMyAdminから実施する。
2026-10-13管理者申告: 初期4テーブル作成とversion/checksum登録済み。
本番では下記初期SQLを再実行しない。PHPからの照合・接続確認は[#3手順](implementation-3.md)へ。
CIや公開PHPから本番DDLを実行しない。今のSQLは初期作成用であり、更新用ではない。
OAuth実装がDBを使用する前に実施する。DBパスワードをGitHubやチャットへ貼らない。

## 実行前

1. phpMyAdminでホスト `mysql50009.db.sakura.ne.jp`、DB `db50125_wakand_wakand` を選択する。
2. SQLタブで下記を実行し、意図したDB・MySQL 8.0であることと、テーブルが空であることを確認する。
   既存テーブルがあれば初期SQLは実行せず、構成を確認する。DROPや空にする操作は不要。

```sql
SELECT DATABASE(), VERSION();
SHOW TABLES;
```

3. 空でないDBの更改では、先に既存データを管理者の非公開保管先へエクスポートする。
   稼働開始後はメンテナンス化と処理中の交換/更新の終了を確認する。

## 初期SQLの実行

リポジトリの `authWeb/schema/001_oauth_mysql.sql` を選択してインポートする。
秘密値やDB作成命令は含まれない。テーブル4つを作成する。
同じファイルを繰り返し適用すると既存テーブルのエラーになる。無条件の再実行はしない。

## 実行後

```sql
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL;
```

`oauth_schema_versions` / `oauth_links` / `oauth_link_sessions` / `oauth_rate_buckets` の
4テーブルすべてInnoDBであり、sessionsからlinksへの外部キーがあることを確認する。
各テーブルの構造をSQLファイルと照合後、適用したファイルのSHA-256を記録する。
PowerShellでの確認例（リポジトリルートで実行）:

```powershell
(Get-FileHash -Algorithm SHA256 -LiteralPath authWeb/schema/001_oauth_mysql.sql).Hash
```

以下は**書式例**。プレースホルダーを実ファイルの64桁SHA-256に置き換えてから実行する。
初期SQL全体の成功・構造一致を確認するまではversion行を追加しない。

```sql
INSERT INTO oauth_schema_versions (version, checksum_sha256, applied_at)
VALUES (1, UNHEX('<実ファイルの64桁SHA-256>'), UNIX_TIMESTAMP());
```

## 途中で失敗した場合

MySQLのCREATE TABLEは、後の文が失敗しても先に作成したテーブルが残る。
versionは未登録のままにして、失敗文と作成済み構造を確認する。
エラー内容に秘密があれば伏せて共有し、残りの定義に合わせた修復SQLを作る。
一括DROP・自動rollback・上書きインポートを復旧手段にしない。

## 後続の更改

002以降の追加SQLと事前条件・バックアップ・検証・復旧手順を変更ごとに用意する。
本番で実行するのは管理者。CIは独立したMySQLでDDLを検証するのみ。
テーブル作成・version/checksum登録は管理者申告で済み。
2026-10-14 JSTに本番PHPから既存4テーブル・外部キー・version/checksumの照合とDB接続が成功した。
管理者がprovider_internalを明示採用し、TLS暗号化なしを実測。初期SQLの再実行・更改は行っていない。

## 任意: MySQLイベントでの定期掃除

ルーチンは処理の定義、イベントは時刻に応じてSQLを実行する機能。
phpMyAdminにメニューがあるだけでは、イベントが実行可能とは限らない。
まず次の読み取りだけを行う。出力全文の共有は不要で、ON/OFF/DISABLEDとEVENT権限の有無を確認する。

```sql
SHOW VARIABLES LIKE 'event_scheduler';
SHOW GRANTS FOR CURRENT_USER;
```

ONかつ対象DBにEVENT権限（またはそれを含む権限）があれば採用候補。
OFF/DISABLEDや権限不足なら提供元の制限を確認する。共有DBサーバーのglobal設定を勝手に変更しない。
CREATE ROUTINE権限はEVENT権限の代わりにならない。

採用時も別の更改SQLで導入し、重複実行制御・件数制限・実際の最終実行結果を確認する。
安全な初期対象は期限切れrate bucketの削除。session/linkの掃除は状態遷移の実装と合わせて追加し、
未受領tokenや失効待ち・結果不明のtokenを単純な日付条件で削除しない。
WakaTimeへのHTTPS失効とtoken復号はPHPの責務。DBイベントで外部通信を実装しない。
イベント不可でも、利用時の期限検証とPHPの件数制限付き掃除で認証は成立する。

出典: [MySQLイベント](https://dev.mysql.com/doc/refman/8.0/en/events-overview.html)、
[スケジューラ設定](https://dev.mysql.com/doc/refman/8.0/en/events-configuration.html)。
