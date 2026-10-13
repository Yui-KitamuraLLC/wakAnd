# Issue #1: WakaTime OAuth 移行

このファイルは、今回の移行に固有の方針をまとめたものです。
日程・進捗・依存関係・完了条件は GitHub の issue とマイルストーンで管理します。
GitHub 側の方針が変わった場合は、このファイルも更新してください。

- 親 issue: https://github.com/Yui-KitamuraLLC/wakAnd/issues/1
- マイルストーン: https://github.com/Yui-KitamuraLLC/wakAnd/milestone/1

## 目標と優先順位

2026年10月13日〜16日（JST）のスプリントで、全機能 OAuth 対応版の検証・公開を目指します。
本案を最優先で進めます。2026年11月1日は WakaTime 側の切替期限です。

認証方式と必要環境を初日に確定することが日程の前提です。
外部回答待ち・バックエンド準備・ストア審査などの阻害要因は親 issue に記録します。
暫定版が必要になっても、本案の未完了タスクは閉じません。

## WakaTime 認証

### #8で決定したスプリント範囲（2026/10/13）

- WakaTime公式は外部ブラウザのAuthorization Code＋PHPバックエンド方式で進める。
- バックエンドは `authWeb/`、公開先は `https://yui.eng.pro/android/wakAnd/`。
- 公式以外は従来のAPIキー方式・既存設定の互換維持を対象とする。
  他サービスへのOAuth対応、自動判定、汎用OAuth基盤化はスプリント外。
- PKCE非対応を実証したわけではない。追加調査を今回のバックエンド実装の着手条件にしない。
- #8は方式・範囲の決定としてclose。登録設定は#9、設計は#12、実装は#3、
  実疎通は#13/#6で引き続き管理する。

### 実装上の条件

- #12の設計成果物は `authWeb/docs/oauth-design.md`、初期DDLは
  `authWeb/schema/001_oauth_mysql.sql`。設計完了と本番導入・実疎通を区別する。
- DBは管理者が用意したMySQL 8.0を利用。契約レンタルサーバー内から接続する。
  初期4テーブルとversion/checksum登録は管理者申告で済み。再作成・DROPは行わない。
  #3実装・管理者確認は `authWeb/docs/implementation-3.md`。2026-10-14に非公開鍵生成・設定参照・PHPのDB/schema照合を確認。
  管理者がprovider_internalを明示採用し、実接続はTLS暗号化なし。maintenance=trueを保持。
  PHP8.3でもsodiumが未提供のため通常OAuthは未稼働。sodium_compatをアプリ内へ同梱してXChaCha20を維持する。
  実装・配布準備は `authWeb/docs/sodium-compat-deployment.md`。本番適用前にPR #14をレビューする。
  管理者の標準手順は `authWeb/docs/browser-admin.md`（FFFTP＋ブラウザ）。サーバーCLIを必須にしない。
  本番DBへローカルPC・GitHub Actionsから接続しない。CIでは独立したテストDBを使う。

- WakaTime は2026年11月1日から API キーを活動送信専用に変更する旨を通知している。
- 読み取り API と external_durations は OAuth 対応を前提にする。
- POST であることだけを根拠に、API キーで利用できると判断しない。
- ネイティブアプリ向け OAuth の方式・スコープは公式仕様と疎通で確認する。
- client_secret をソースコードや APK に埋め込まない。
- API キー、access token、refresh token、client_secret をログ・issue・PR に出力しない。
- トークンの保存・期限切れ・更新・失効・再認可・連携解除を扱う。
- OAuth callback の state を検証する。
- 認証方式と接続先を明確に管理し、WakaTime 用の認証情報を別ホストへ送らない。
- Wakapi 等のカスタム接続先では、そのサーバーの仕様に応じた API キー利用を維持する。

公式仕様: https://wakatime.com/developers

## 作業の参照先

- #2 認証方式・スコープ確定、OAuth アプリ設定: https://github.com/Yui-KitamuraLLC/wakAnd/issues/2
- #3 認可・トークン管理・連携解除: https://github.com/Yui-KitamuraLLC/wakAnd/issues/3
- #4 API 対応・カスタム接続先維持: https://github.com/Yui-KitamuraLLC/wakAnd/issues/4
- #5 移行導線・連携状態表示: https://github.com/Yui-KitamuraLLC/wakAnd/issues/5
- #6 実機・移行・失効・カスタム接続先の検証: https://github.com/Yui-KitamuraLLC/wakAnd/issues/6
- #7 対応版公開・移行案内: https://github.com/Yui-KitamuraLLC/wakAnd/issues/7
