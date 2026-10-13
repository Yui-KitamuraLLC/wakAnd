# sodium_compat同梱と配置手順

2026-10-14 JST。PR #14をwk1oauthへmergeし、本番配布済み。稼働開始・実OAuth疎通は未実施。
配置後の[本番診断](production-diagnosis-20261014.md)でsodium_compatの自己検証とDB/schema照合成功。
maintenance=trueを維持し、管理認証の撤去後にadmin.php=404を再確認した。
共有サーバーのPHP設定や拡張を変更せず、wakAndの公開フォルダだけへPHPライブラリを同梱する。
既存のXChaCha20-Poly1305、24バイトnonce、認証タグ、AAD、キーIDを保持する。
生成済み実鍵・rate limit鍵・秘密設定・DBの変更は不要。AES-GCMへの移行案は採用しない。

## 依存元と制約

- [paragonie/sodium_compat v2.5.2](https://github.com/paragonie/sodium_compat/releases/tag/v2.5.2)。
  commit `3246b36803c177847e677862fedeb7f937869adb`を固定し、公式アーカイブから49ファイルを無改変で同梱。
  GitHub APIのタグ署名検証はverified=true。独自の暗号監査を行ったという意味ではない。
- ISCライセンスを同梱。取得元・アーカイブSHA-256・各ファイルSHA-256は
  `sodium-compat-manifest.json`、再取得手順は `scripts/vendor-sodium.py` に固定している。
  配布作成時に欠落・改変・追加ファイル・パス逸脱を拒否する。実行時に外部取得しない。
- ネイティブsodiumがあれば優先する。ない場合はPHP 8.1以上・64bitで同梱版を使う。
  このアプリの配置先だけで読み込み、他サイトのPHPバージョン・php.ini・拡張には作用しない。
- [上流の説明](https://github.com/paragonie/sodium_compat#readme)では正式な独立暗号監査は未実施。
  純PHP版はネイティブ版と同等の速度やサイドチャネル耐性を保証しない。
  `fastMult=false`を保持し、速度優先の危険な切替を使わない。
- 各リクエストで公開の既知ベクトルによる暗号化・復号を1回検証する。
  検証失敗・ライブラリ欠落・未対応環境では通常OAuthを秘密非表示の503で停止する。

## 配布物作成とレビュー

`scripts/deploy.py`は通信せず、許可リストと依存チェックサムから新しいフォルダを作る。
既存のdistや作成済みフォルダへの上書きを拒否する。

```powershell
$env:FTP_SERVER='yui.eng.pro'
$env:FTP_SERVER_DIR='/android/wakAnd/'
python authWeb/scripts/deploy.py --output C:/review/wakand-release
```

出力先の親フォルダは先に用意する。公開4ファイル、アプリPHP7ファイル、
DDL照合用 `_schema.php`、同梱依存49ファイルの合計61ファイル。
DDL本体、oauth.php、generated-keys.php、admin-access.php、管理キー、開発用テストは含めない。
ZIPと全ファイルのSHA-256を配布候補に付け、レビューしたコミット・CI・転送物の対応を確認する。

CIではネイティブ版と実際にsodium拡張を無効化したPHPで既知ベクトル、改ざん拒否、
暗号文の相互互換、MySQLの競合・解除・結果不明処理を検証する。
合成tokenの256/2048/16384バイト往復時間も保存するが、本番の性能測定や実WakaTime疎通には代用しない。

## レビュー後の配置

1. `maintenance=true`を維持し、処理中の交換・更新がないことを確認する。
   現在の公開ファイルを公開領域外へバックアップする。秘密設定・実鍵は既存位置を保持する。
2. 対象は `/www/webroot/android/wakAnd/` だけ。共有 `/android/` を同期・削除しない。
   `.htaccess`を先に配置し、`_vendor/`全体へのHTTPアクセスが403になることを確認する。
3. `_vendor/sodium_compat/`の49ファイルと `_crypto.php`を配置し、
   残りのアプリPHPを含む61ファイルをレビュー済み転送物と揃える。
   ライブラリだけを途中まで配置した状態で稼働を開始しない。
4. [ブラウザ管理者手順](browser-admin.md)で30分有効の管理認証を新しく用意し、読取診断を行う。
   **初期鍵の再生成を行わない。**既存generated-keys.phpを削除・上書きしない。
5. sodium拡張がない本番では `crypto.backend=sodium_compat`、`available=true`、`self_test=true`、
   `php_64bit=true`と、configuration/database/schema=trueを確認する。
   `extensions.sodium=false`自体は失敗条件ではない。`self_test_ms`は公開ベクトルの所要時間のみ。
   DBは管理者採用済みprovider_internal、tls_encrypted=falseを継続記録し、TLS成功とは扱わない。
6. `_crypto.php`、vendorのautoload.php/LICENSE/README.mdへの直接アクセス403、
   公開ルート200、経路rewrite、管理認証の期限・無効時404を確認する。
   期限付き管理認証は確認後削除し、実鍵を残す。
7. 配置診断成功とOAuth稼働開始を分ける。maintenance解除と#13/#6の実認可・端末疎通は
   別途承認した検証で行い、実scope/応答を#10/#11/#2へ反映する。

本番のDDL再インポート・DROP・バージョン登録し直し、鍵ローテーション、PHP全体設定変更は不要。
wk3implのpush/PRでは転送しない。wk1oauth/mainへのmergeは既存CIの転送を起動するため、
mergeも本番適用としてレビュー後に行う。

## 配置失敗時

maintenance=trueのまま、欠落・破損とPHPの64bit対応を確認する。
診断の成功フラグ以外に鍵・token・例外詳細を共有しない。
復旧が必要ならバックアップしたアプリPHP一式を揃えて戻す。
`_vendor/`拒否ルールは旧実装にも支障がない。実鍵・DB・秘密設定は巻き戻さない。
旧実装へ戻した場合、sodium未提供の本番では通常OAuthは引き続き503となる。
