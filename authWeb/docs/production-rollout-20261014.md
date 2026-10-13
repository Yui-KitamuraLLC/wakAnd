# OAuth本番適用・実機観測の更新

2026-10-14 JST。01:50頃の[配置後暗号診断](production-diagnosis-20261014.md)以降の状況。

- PR #14（`e4ad290`）の本番配布成功後、管理者が非公開oauth.phpのmaintenanceをfalseへ変更。
  当初callback503が継続したが、管理者が別の記述箇所も修正した後、02:42 JSTに入力なしcallback=400 invalid_requestを確認。
  秘密設定全文は取得せず、どの記述が有効だったかは管理者回答の範囲。
- PR #15を承認後wk1oauthへmerge。commit `e0b3f7604ea38d9a1578cd7472ee5814136be4f3`、03:02:50 JST。
  [本番配布CI](https://github.com/Yui-KitamuraLLC/wakAnd/actions/runs/38443585457)と[APK CI](https://github.com/Yui-KitamuraLLC/wakAnd/actions/runs/38443585323)成功。
  PR #15のauthWeb差分は文書だけで、公開PHP/vendor/schema照合コードはPR #14と同じ。
- 03:05 JSTにroot/config-check=200、callback/app-complete入力不足=400、内部PHP/vendor=403、admin.php=404を再確認。
- PHP8.3.32、sodium_compat v2.5.2、既存XChaCha20/実鍵/AAD/DB形式を保持。
  provider_internalでTLSなし。DBへのPC/CI接続・DDL/再作成・鍵再生成は行っていない。
- 一時管理認証2回分とローカル一時ファイル/空フォルダを撤去済み。正式oauth.php/generated-keys.phpを保持。
- 操作者のPixel 6aでは実認可→アプリ復帰→連携済み、実付与2 scope、自動/手動記録のdashboard反映、OAuth解除→再連携を確認。
  scopeは`read_stats.projects,write_heartbeats`、画面の期限は`2126-09-20 02:39:42 +09:00`。
  token応答本文のexpires_in/形式、API個別HTTP結果は未取得で、画面/操作者観測と区別する。
- 共有サーバー仕様でcallback queryログを抑制できないと管理者が回答。code/stateがアクセスログに残り得る制約は継続。
  PHPの秘密非出力とは別で、共有設定・他サイトを変更しない。

主要な正常操作の実機成功と全E2E完了を区別する。通信復帰・移行保持/再起動・アプリとURL双方・独自ホスト実環境等は残検証。
正式版1.5.0/code22の[公開前検収](../../release/verification-1.5.0.md)へ引き継ぐ。
設計完了の#12 closeは維持。他issueは残検証を一括で完了扱いしない。
