# 1.5.0 / code 22の公開前検収

2026-10-14 JST。操作者指定の正式版は1.5.0/versionCode22。
wk1oauthのPR #15 merge commit `e0b3f7604ea38d9a1578cd7472ee5814136be4f3`から版・文書を更新する。
認証/APIの実装はPR #15と同じであり、本変更は版番号・公開説明・検証記録のみ。
公開・ストア提出・公開後検収は未実施。

## 既存の実装・本番検証

- PR #15のローカルJUnit 28件成功、merge後の[APK CI](https://github.com/Yui-KitamuraLLC/wakAnd/actions/runs/38443585323)と[Auth Web CI](https://github.com/Yui-KitamuraLLC/wakAnd/actions/runs/38443585457)成功。
- PHP8.3.32・sodium_compat v2.5.2の本番自己検証成功、公開ベクトル40.348ms。configuration/database/schema=true。
  実token全体の本番性能、CI合成token測定、実WakaTime応答を区別する。
- 管理者がmaintenance=falseへ切替。通常経路の入力なしcallback=400、admin.php=404、内部PHP/vendor=403を確認。
- provider_internalでDB通信はTLSなし。共有サーバーではcallback queryログを抑制できない。
  実鍵・正式設定・DBを保持し、一時管理認証とローカル残渣は撤去済み。

## Pixel 6aでの観測（操作者申告）

- ブラウザ認可→アプリ復帰→OAuth連携済み。
- 実付与scope=`read_stats.projects,write_heartbeats`。
- 表示期限=`2126-09-20 02:39:42 +09:00`。token応答本文の期限形式は未取得。
- 自動記録のdashboard反映を画像で確認（0:02:41）。
- 短い手動登録のdashboard反映成功。
- OAuth解除→再連携を確認済み。
- 追加の動作チェックは良好との所感。項目別HTTP結果・Android版・設定保持の詳細等は未受領。

## 1.5.0署名APKでの最終確認

- 上書き更新で表示版が1.5.0になり、既存のアプリ別設定・プロジェクト割当・OAuth連携/独自ホスト設定が残る。
- プロセス再起動後に再取得と記録が継続する。
- 通信断→復帰後に新しい活動を記録でき、連携情報を破棄しない。通信断中の活動の補送は実装していない。
- アプリ使用・ブラウザURLの両方を確認する。
- 署名・package/version/SDK・SHA-256とCI artifact digestを照合する。

CIの生成結果・署名APKの検証情報・実機結果はissue #7/#6へ記録する。
独自ホストは未所有のため実サーバー互換性未検証。資格情報分離は自動試験で確認済み。
実provider更新/失効HTTP結果、期限切れ更新の実測、複数実機の検証等も未実施と明記する。
期限切れ/失効の自動試験と実本番観測を混同しない。

## 公開の境界

版番号・署名APK生成だけで公開完了とは扱わない。wk1oauth向けPRで検収し、mergeは本番配布CIを起動するため適用承認後。
GitHub Release公開・ストア提出は別の公開操作。既存タグ形式に合わせるなら`1.5.0`が候補で、現時点ではタグを作らない。
日英ノートと移行案内を公開し、公開物で更新・再連携・記録を確認して#1へURL/結果を記録する。
