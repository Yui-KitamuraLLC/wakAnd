# PR #14配置後の本番診断

2026-10-14 01:50頃 JST、管理者がFFFTP・ブラウザで読取診断を実行し、秘密を含まない結果を共有した。

- PR #14はwk1oauthへmerge済み。merge commit: `e4ad29009d7173b2259373e88904f4c143cd7634`。
- [本番配布CI](https://github.com/Yui-KitamuraLLC/wakAnd/actions/runs/38431349784)のdeployとnative/sodium_compat検証成功をGitHubで確認。
- 公開61ファイルの転送記録のサイズ・SHA-256と候補の一致は前工程で確認済み（今回再転送していない）。
- 本番PHP 8.3.32を使用。管理診断は`crypto.backend=sodium_compat`、`available=true`、`self_test=true`、`php_64bit=true`。
- `self_test_ms=40.348`。本番の公開既知ベクトル検証時間であり、token全体の往復・上流通信時間ではない。
  CI PHP8.3.35での合成token往復中央値（2KB=7.765ms、16KB=55.993ms）とは別測定。
- `extensions.pdo_mysql/curl=true`、`extensions.sodium=false`、`sodium_module_available=false`。
  同梱版の自己検証が成功したためsodium拡張なしでも利用可能。
- `configuration/database/schema=true`、`maintenance=true`。
- `tls_mode=provider_internal`、`tls_encrypted=false`、`database_tls_advertised=false`。管理者採用済みのTLSなし接続であり、TLS成功とは記録しない。
- `operations_in_progress/operations_expired/revocation_pending=0`。
- 今回新設した30分限定admin-access.phpは診断後に管理者が撤去済みと回答。
  CodexがHTTPSでadmin.php=404を再確認し、今回のローカル一時認証2ファイルも削除した。
  初期鍵生成・鍵変更・DDL/DML/掃除・本番DBへのPC/CI接続は実行していない。
- 公開HTTP再確認: root/config-check=200、callback=503、入力不足app/complete=400、
  _crypto.phpとvendor/autoload.php=403、admin.php=404。
- oauth.php/generated-keys.phpは正式な本番設定・実鍵として保持。共有PHP設定・他サイトは変更していない。

配置・暗号診断が成功したことと、OAuth稼働開始・実WakaTime疎通・実機検証を区別する。
maintenanceは未解除。#4/#5のAPI/画面統合、#13/#6の実認可・scope/token応答・App Links・移行等は未完了。
#12は設計完了のclose状態を維持する。

実機試験の準備: 管理者はPixel 6aの常用v1.4.2からAPKを更新して試せる。
利用可能なのは常用WakaTimeアカウント。独自ホストは所有していないため実疎通未実施。
試験活動を識別し、既存設定と既存アカウントの他の連携を保持する。
