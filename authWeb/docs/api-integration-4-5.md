# #4/#5 API統合・接続先切替の検収

PR #15は検収・merge後のCIが成功。主要な実機成功と残検証は[最新の適用記録](production-rollout-20261014.md)を参照。
正式版1.5.0/code22の最終確認は[公開前検収](../../release/verification-1.5.0.md)で管理する。以下の準備時記録と現在地を区別する。

2026-10-14 JST。wk1oauthのPR #14 merge commit `e4ad290`から作業。
本番診断と稼働開始は[診断記録](production-diagnosis-20261014.md)で分ける。

## 実装範囲

- 公式APIは`https://api.wakatime.com/api/v1`へ固定し、通常4 APIにBearer認証を適用。
- 401だけでOAuth更新を要求し、1要求あたり更新1回・再送1回まで。送信前に期限切れ更新済みなら追加更新しない。
  同世代の競合はOAuthManagerの排他・backend世代確認へ渡す。更新失敗・403・429・5xx・通信断・redirectでは自動再送しない。
- 接続先・URL/APIキー設定の世代とOAuth linkを要求開始時に固定。送信直前にも照合。
  切替時は開いているAPI接続を切り、古いキュー/レスポンスを新しい接続先へ適用しない。
  すでに送信した要求は取り消せず、POSTの応答喪失は送達不明として扱う。
- heartbeats.bulkは外側の2xxだけで成功にせず、1件の個別HTTP結果も確認する。個別401も同じ更新上限で処理。
  独自ホストは従来のBasic方式・2xx判定を保持する。
- WakaTime↔独自ホストのトグル。既存URL/APIキー・アプリ別有効設定・プロジェクト割当を維持。
  公式の移行前APIキーを別名で保持し、独自ホストのキーを公式へ送らない。切替だけではOAuthを失効しない。
- 初回移行前の公式APIキーはHeartbeatだけを継続。OAuth使用後の失効/解除/鍵喪失ではAPIキーへfallbackしない。
- 正常空プロジェクト、401、403、通信障害を区別。プロフィール取得失敗でOAuth未連携と表示しない。
  既存のプロジェクト割当を消さず、再取得/再連携を案内。Heartbeat送信失敗を通知へ反映。
- 手動登録は同じexternal_id・本文を401再送と同じ画面での手動再試行に再利用。
  画面再生成時にもID/入力・接続先の照合情報を保持。送達不明後に内容/接続先が変われば再送を止める。
  新規画面は別操作になるため、送達不明時はdashboard確認後に開始する。
- 日英の状態・移行文言とprivacy policyを更新。OAuth画面は許可されたscopeと期限を表示し、tokenを表示しない。

## 検証と残タスク

既存・追加JUnit 28件はローカルJavac/JUnitで成功。Gradleは環境のloopback接続エラーで起動できず、
アプリ全体のGradle試験・署名APK生成はGitHub CIで別途確認する。
追加検証: 401再送上限・同じPOST bytes、403/redirect/通信障害の非更新、世代/連携変更、
公式/独自ホストの資格情報分離、移行設定保持、bulk個別結果と不正応答。

本番maintenance解除・実認可・4 API・実機移行/バックグラウンド/再起動/通信断は未実施。
Pixel 6aの常用v1.4.2から署名APKで上書き更新し、識別した試験記録を使う予定。
独自ホストは所有していないため、モック資格情報分離試験と実サーバー互換性を区別する。
#12は設計完了のclose状態を維持し、#2/#3/#4/#5/#13/#6は実機/実疎通結果で検収する。

## 仕様確認

2026-10-14確認: [公式OAuth・4 API](https://wakatime.com/developers)、
[公式CLIのbulk個別status解析](https://github.com/wakatime/wakatime-cli/blob/develop/pkg/api/heartbeat.go)。
external_id再利用は同一記録の更新、email scopeは公開プロフィールには不要という文書仕様を確認。
実付与scope・token応答・emailなしusers/current成功は実疎通待ち。
