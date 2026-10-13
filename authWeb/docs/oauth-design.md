# OAuth基盤設計・実装引き継ぎ

2026-10-13。#2 / #10 / #11 / #12 → #3 / #4 / #5 / #6 / #13。
これは設計。OAuthエンドポイント、DBテーブル、暗号鍵はまだ本番へ導入していない。
本番設定のPHP読み取りと既存CIのFTP配置は確認済み。

## 1. 配置と責務

| 項目 | 決定 |
| --- | --- |
| 公開URL | `https://yui.eng.pro/android/wakAnd/` |
| OAuth callback | 上記配下 `oauth/callback`（登録済み設定と完全一致） |
| Android復帰 | 上記配下 `app/complete`。App Linksはこのパスだけ |
| 秘密設定 | `/home/yui-kitamura/.config/wakand/oauth.php` |
| DB | MySQL 8.0 / InnoDB。SQLite案は不採用 |
| DBホスト | `mysql50009.db.sakura.ne.jp` |
| DB名・ユーザー名 | ともに `db50125_wakand_wakand`（管理者確認済み） |
| DB接続元 | 契約レンタルサーバー内。ローカルPC・GitHub Actionsから本番DBへ接続しない |
| OAuth提供元 | WakaTime公式のみ。他サービス用の汎用テーブルにしない |
| 通常API | Android → `https://api.wakatime.com/api/v1/`。PHPを活動データの中継にしない |

PHPはclient_secret、暗号化したaccess/refresh tokenを保持する。access tokenも保持するのは、
受け渡し・更新の応答を端末が受け取れなかった場合に、同じ世代を再取得できるようにするため。
Androidはaccess token・期限・世代・link_id・端末用資格情報だけを保持する。refresh tokenは渡さない。
DBにはメール、活動内容、プロジェクト一覧、端末名、広告IDを保存しない。
1つの連携は1回の端末認可に対応。同じWakaTimeユーザーの複数端末をユーザーIDで統合しない。

## 2. 秘密設定・DB接続

既存の `wakand` セクションに、実装時に `database` と `token_keys` を追加する。
現行Config.phpは3項目のみ返すため、拡張時は必須項目検証・テストも更新する。

```text
database.host = mysql50009.db.sakura.ne.jp
database.port = 3306（接続確認時に確定）
database.name = db50125_wakand_wakand
database.user = db50125_wakand_wakand
database.password = 管理者が非公開設定へ記入
database.tls_mode = 接続確認後に required または provider_internal を明示設定
database.tls_ca = requiredの場合に信頼するCAの非公開配置パス
token_keys.current = v1
token_keys.keys.v1 = サーバーで生成した32バイト乱数のbase64
rate_limit_key = token暗号鍵とは別の32バイト乱数のbase64
environment = production
```

本資料に秘密値はない。管理者の既存Secretは変更しない。DBパスワードと暗号鍵の配置は後続手順で依頼する。
PDO MySQL、utf8mb4、例外モード、ネイティブprepare、非永続接続を使用。SQL入力はすべてbindする。
時刻はDBの `UNIX_TIMESTAMP()` を基準とするUTC秒。strict SQL modeを確認する。
本番は専用DBを使用。権限を分離できる場合、通常PHPはSELECT/INSERT/UPDATE/DELETEだけ、DDLは管理用ユーザー。
サービスが権限分離できない場合はその制約を記録し、公開のDDL実行エンドポイントを作らない。

FAQは契約サービス外からの接続禁止を明記するが、暗号化やプライベートIPまでは保証しない。
サーバー内PHPから疎通し `Ssl_cipher` と証明書検証可否を確認。TLS対応時は証明書・ホスト名を検証して必須化。
非対応の場合、運用が提供元の内部接続に依存することを管理者へ報告して `provider_internal` を明示採用する。
接続失敗時にTLS検証を自動解除しない。現在は接続もTLSも未確認。

## 3. データモデル

DDL: `../schema/001_oauth_mysql.sql`。このファイルは公開許可リスト・FTP転送対象外。

| テーブル | 内容・寿命 |
| --- | --- |
| oauth_schema_versions | 適用済みDDL番号・ファイルSHA-256・適用時刻 |
| oauth_links | 端末別の認可、端末資格情報のハッシュ、暗号化token一式、世代、処理中操作、失効待ち |
| oauth_link_sessions | 開始から10分の認可セッション。state・verifier・ブラウザ結合情報のハッシュ |
| oauth_rate_buckets | API濫用を抑える短期カウンター。期限はバケット終了から1時間 |

リンクIDはサーバー生成の16バイト乱数、APIではpaddingなしbase64url（22文字）。識別子だけでは認証不可。
state、launch_token、device_secret、browser_nonceは各32バイト乱数。DBにはSHA-256の32バイト値だけ。
端末は通信前にdevice_secretとverifierを生成・安全に保存し、開始APIへハッシュだけ送る。
verifierは32バイト乱数のbase64url（43文字）、challengeはそのASCII文字列のSHA-256。
これは端末→自前PHPの受け渡し結合であり、WakaTime側PKCEの対応を仮定するものではない。
ハッシュ比較は定数時間比較。UUID・時刻・端末情報で秘密値を代用しない。

token_bundleはJSON（access_token、refresh_token、token_type、実付与scope、expires_at）を
XChaCha20-Poly1305で暗号化したBLOB。BLOBの先頭24バイトをnonce、残りを暗号文＋認証タグとする。
暗号化ごとに新しいnonceを生成し、AADは `wakand|production|<link_id>|<generation>|<key_id>`。
AADとDB行の対応を検証して、他行・他環境からの差し替えを拒否する。鍵はDBに保存しない。
access_expires_at・granted_scopeを索引用/判定用に平文でも持つが、復号内容との一致を検証する。
不一致、復号失敗、鍵不明は503。新規の空DB・別鍵へ自動切り替えない。
世代は初回交換成功で1、その後成功した更新ごとに+1。秘密情報変更を原子的にcommitする。
tokenレスポンスの期限を解釈し、365日などを固定値で代用しない。未知形式・refresh欠落は連携未完了。
更新応答にrefreshがない場合の旧値再利用は、提供元の仕様/実測を確認してから実装する。

## 4. 認可とAPI契約

ベースは公開URL。JSONはUTF-8、POST本文最大8KiB、未知フィールドは拒否する。
API認証は `Authorization: Bearer <device_secret>` と `X-Wakand-Link-Id: <link_id>`。
WakaTime access tokenを自前APIの認証には使わない。秘密値・verifierはURLへ入れない。
応答は `Cache-Control: no-store`。外部へのリダイレクト追従・任意return URL・クライアント指定scopeを許可しない。

| 経路 | 入力と出力 |
| --- | --- |
| POST v1/link/start | device_secret_hash / verifier_challenge（base64url各32バイト）。201: link_id / browser_url / expires_at。scopeはサーバー固定 |
| GET oauth/launch | link_id / launch_token。ハッシュ・期限・未使用を検証し、ブラウザnonceをCookieへ設定してWakaTimeへ303 |
| GET oauth/callback | code+state または error+state。state・Cookie・期限・未使用を検証し、サーバーでtoken交換。成功/拒否後はApp Linkへ303 |
| GET app/complete | link_idのみ。Androidが開始済みIDと照合する。未インストール時は秘密を含まない案内。JSによるtoken受取はしない |
| POST v1/link/complete | 端末認証＋verifier。200: access_token / token_type=Bearer / expires_at / generation / granted_scope。認可待ちは202 |
| POST v1/token/refresh | 端末認証＋observed_generation / reason（expired または unauthorized）。200: 上記token情報。競合中は409+Retry-After |
| POST v1/link/disconnect | 端末認証、空JSON。失効済み200、受理・失効保留202。ローカルの通常利用は受理を待たず停止 |

startでoauth_links(pending)とsessionを同一transactionで作る。stateはまだNULL。
通信再試行で複数できても10分で期限切れ。
browser_urlは固定ホストのoauth/launchだけ。launchで新stateを生成し、state_hashの保存、
launch_token消費、browser_nonce_hash保存を原子的に行ってから認可URLへ303する。
Cookie名はlink_id別、Secure/HttpOnly/SameSite=Lax、Path=/android/wakAnd/oauth/、Max-Age=600、Domain指定なし。
固定originを検証し、同一origin上の他アプリが侵害された場合の分離には限界があることを認識する。
Cookieがないcallbackは拒否し、stateだけを根拠に交換しない。code/state重複パラメーターも拒否。
stateは交換開始時に消費し、codeはメモリ内のみ。拒否・再送・期限切れで交換を再実行しない。
認可開始scopeは `read_stats.projects,write_heartbeats`。email要否は#10/#4の実測待ち。

completeは同じ端末資格情報＋verifierでだけ実行できる。初回ready→activeの遷移は1回だけ。
応答喪失時はセッション有効期限内なら同一連携の現在世代を再返却する（再認可・新しい連携は作らない）。
有効期限後は端末資格情報でrefreshから取得。未受領readyの期限切れはrevoke_pendingへ移し、以降取得不可。
Androidがブラウザから戻らなくても、保存済みIDとverifierでcompleteを再試行できる。
callbackのaccess logにはcode/stateが入り得るため、当該経路のquery記録抑制・保持権限を確認する。
PHPログ・応答・解析サービスへURL全文、Cookie、Authorization、SQL引数、上流応答本文を出さない。

失敗JSONは `error`（固定コード）と秘密を含まないrequest_idのみ。
400 invalid_request、401 device_unauthorized、409 operation_in_progress/reauth_required、
410 link_expired/link_disconnected、429 rate_limited、502 provider_error、503 service_unavailable。
WakaTime APIの401/403はAndroid側で別分類。プロフィール取得失敗を未連携へ直結させない。

## 5. 更新・解除の競合とクラッシュ

InnoDB transactionで `SELECT ... FOR UPDATE` し、認証・状態・generationを再確認する。
常にlinks→sessionsの順でロック。ネットワーク待ち中はDBトランザクションを保持しない。
operation_id（16バイト乱数）、operation_deadline（DB時刻+60秒）、状態をcommitしてから外部通信。
外部通信は接続5秒/全体20秒を上限にする。完了時は同じoperation_idの行だけ更新する。
60秒は再実行の許可期限ではなく、結果不明と判定する期限。外部操作の厳密なexactly-onceは保証できない。

```text
pending → exchanging → ready → active → refreshing → active
             └失敗/不明→ reauth_required
active / refreshing → reauth_required（確実な失効、更新結果不明）
任意の利用可能状態 → disconnect_requested=1 → revoke_pending → revoking → revoked
```

refreshでは、端末のobserved_generationがDBより古ければ現在のtokenを返すだけ。
同じ世代で期限が60秒以内、またはその世代でAPI 401を受けた場合のみ更新を予約する。
端末は1回のAPI要求につき更新1回・再試行1回まで。上流403、通信断、5xxで無条件更新しない。
不明な未来のgenerationは400。同世代の同時要求は1つだけ上流へ送り、他は409。
成功は新token一式・generation・期限・scopeを一括保存。DB失敗後に古いrefreshで自動再送しない。
送信前の接続失敗を証明できる場合だけactiveへ戻せる。送信後timeout・プロセス終了・不明な応答は
reauth_requiredとし、旧token一式を失効処理用に隔離する。通常APIへの返却・更新は停止する。
結果不明のoperation_idはunresolved_operation_idへ退避し、同じlinkで新たな交換/更新を禁止する。
遅れて到着した該当操作の成功結果は新世代として隔離保存し、失効へ回収する。

disconnectは行ロック下でdisconnect_requested=1をcommitしてから202を返す。
更新/交換が処理中なら完了側が新tokenを保存した上でrevoke_pendingにする。activeへ戻してはならない。
処理中でなければその場で失効を試行する。providerへのrevokeは対象tokenのみ、all=trueは送らない。
200なら失効対象の世代が変わっていないことを行ロック下で確認し、暗号文・鍵IDをNULL化してrevoked。
失効通信中に遅延成功の新世代を回収した場合、その暗号文を削除せずrevoke_pendingへ戻す。
失効済み行への遅延成功も通常利用は再開せず、revoke_pendingにして新tokenを失効する。
結果不明ならrevoke_pendingへ戻し、指数バックオフ
（30秒〜最大6時間）で再試行。失効は同じtokenで再試行可能という公式契約を使う。
旧tokenによる200は、失われた更新結果の新tokenまで失効した証明にはならない。
その場合はrevocation_uncertain=1を残し「提供元での解除確認が必要」と表示する。
遅延応答のtokenを回収できればそのtokenも失効する。回収不能ならWakaTime側の連携管理で確認する。

解除後の端末資格情報ハッシュは7日だけ残し、disconnectの再送/状態確認にだけ使う。
通常token取得には即時使用不可。7日後にNULL化するが、失効待ちの暗号文は勝手に削除しない。
失効済みかつ不確実性なしの行は7日後削除可能。認可放置もreadyのtokenを先に失効する。
端末のオフライン解除は暗号化した失効専用キューへ最小情報を退避、通常tokenを即時削除。
次回接続でdisconnectを送る。アンインストール前に送れなければ提供元の失効は保証できない。

## 6. cronなしの運用

すべての利用箇所で期限・状態を確認する。削除ジョブが動かなくても期限切れセッションは利用不可。
リクエスト末尾の掃除は最大20行、外部失効は最大1件。DBで処理を予約してから実行し、競合を避ける。
リクエスト全体の時間予算を守れないときは実行せず、次回へ持ち越す。
無アクセス時の物理削除や上流失効完了日時は保証しない。pending件数/最古日時のみ秘密なしで管理者へ確認可能にする。
CLIメンテナンスを別途実装すれば手動実行できる。cronの利用可否はOAuth開始の必須条件にしない。
MySQLイベントは任意の補助とする。event_scheduler=ONとEVENT権限を確認してから別SQLで導入する。
ルーチンを作るだけでは定期実行にならない。初期対象は期限切れrate bucketの削除に限定し、
外部HTTPS失効・token復号はPHPへ残す。確認SQLと制約は `database-setup.md` に記載。
公開startはIP由来HMACごとに10件/10分、全体100件/10分を初期値とする。
認証失敗30件/分/IP、refresh 6件/分/link、complete 60件/分/linkを上限の初期値とし429で制限。
IPはREMOTE_ADDRを用い、信頼設定のないX-Forwarded-Forを採用しない。生IPはDBに保存しない。
rate bucketの更新も原子的に行い、上限到達時はセッション作成前に拒否する。

## 7. Android・Heartbeat・接続先切替

| 状態 | Heartbeat | ユーザー・プロジェクト・手動登録 |
| --- | --- | --- |
| 公式OAuth active | Bearer | Bearer |
| 公式・移行前の既存APIキーのみ | 既存Basicを維持 | OAuth連携を案内 |
| 公式OAuthが要再認可/解除済み | 送信停止・状態表示。APIキーへ暗黙fallbackしない | 状態表示 |
| カスタム接続先 | その接続先用の既存APIキー | 従来互換を保持 |

公式キーでのHeartbeat成功は#4/#11の実測対象。文書上write-onlyを確認したことと実測を区別する。
公式APIはHTTPS、許可ホスト、標準ポート、固定API prefixを送信直前に確認。リダイレクトへ資格情報を転送しない。
バックエンド用device_secretもyui.eng.proの固定経路にのみ送る。接続先ごとに資格情報を名前空間分離する。
接続先切替では旧タスクを止め、送信直前に接続先世代を再確認。未送信活動を別接続先へ自動転送しない。
OAuth解除は明示操作。接続先切替だけでは旧連携をサーバー失効しないが、選択外の資格情報は使用しない。
OAuth資格情報と失効キューはAndroid Keystoreで暗号化し、専用保存領域をbackup_rulesと
data_extraction_rules双方から除外。復元/鍵喪失は再認可、アプリ別設定やプロジェクト割当は初期化しない。
再連携は新link_id。受取成功後に端末の参照を原子的に切り替え、旧連携を失効キューへ送る。
ネットワーク断や表示名未取得だけで既存連携を破棄しない。

## 8. 導入・開発分離・復旧

開発用は別WakaTimeアプリ、別callback、別DB/ユーザー、別暗号鍵。URL/DB名は未払い出し。
当面の自動テストはモック提供元＋CIの使い捨てMySQL 8.0で行い、本番DB/SecretをCIへ渡さない。
実認可試験は識別したテストアカウントとテスト活動で#13へ記録する。

DDLの初期化・更改は管理者がphpMyAdminで実行（管理者指定）。手順は `database-setup.md`。
SQLは既存テーブルをDROP/上書きせず、
空の専用DBが前提。MySQLのDDLは暗黙commitがあるため、BEGINで全DDLが戻ると思わない。
部分適用失敗時はバージョン記録を書かず、テーブル定義を確認して修復する。通常HTTPから自動DDLしない。
適用後schema versionとSHA-256を記録し、アプリは一致しないDBに対して503で停止する。

既存FTP同期は複数ファイルの原子的切替ではない。OAuth稼働開始前に、非公開設定のmaintenanceを
ON→全APIを503化→配置→管理者によるDDL/確認→maintenance OFFの手順を実装する。
切替中の認可は開始しない。進行中交換/更新の排出を確認してから互換性のない変更を適用する。
追加列など前方互換を優先し、DB自動downgradeは行わない。

DBバックアップは暗号化した管理者保管先へ、token暗号鍵は別管理。Git・artifact・公開FTPへ置かない。
鍵ローテーションは旧鍵で読取可能な期間を設け、新鍵IDで再暗号化してから旧鍵を廃棄する。
古いDBを復元すると消費済みstateやtoken世代が巻き戻る。復元後は全sessionを無効化、全linkを
reauth_requiredへ移して、旧tokenを自動更新せず再認可を求める。保存済み活動設定は維持する。

## 9. 実装者への着手順と検証

1. PHPのpdo_mysql/cURL/sodium、DBから見たバージョン/InnoDB/TLS/権限を秘密なしで確認。
2. 管理者用設定ひな形・サーバー内の鍵生成手順を用意。DBの接続だけを確認し、DDLは手順に沿って適用。
3. CIに独立MySQLを起動し、DDLとRepositoryの状態遷移・暗号化を検証。
4. #3: start/launch/callback/complete、refresh/disconnect、Android Keystore・App Links。
5. #4/#5: 認証表、エラー分類、既存設定を保つ移行UI、外部活動のexternal_id再利用。
6. #10/#11/#13/#6: 最小scope、4 API、端末再起動、更新・失効、カスタム接続先を実測。

必須の競合試験: 同時callbackで交換1回、同時refreshで上流1回、応答喪失後の同世代再取得、
更新中disconnectでactiveへ戻らない、timeout後に古いrefreshを再送しない、遅延成功を失効へ回収。
境界試験: state/ブラウザCookie/verifier不一致、期限、再送、暗号文/AAD差し替え、DB停止、
SQL migrationの部分失敗、秘密がログ/URL/バックアップ/FTPへ混入しないこと。
本資料とDDLだけでこれらの試験を成功扱いしない。

## 根拠

- [WakaTime認証・scope・refresh・revoke](https://wakatime.com/developers#authentication)
- [契約外からMySQLへ接続できないこと](https://faq.interlink.or.jp/faq2/View/md.aspx?id=1188&view&view)
- [MySQL行ロック](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html)
- [MySQL DDLの暗黙commit](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html)
- [PDO MySQL](https://www.php.net/manual/en/ref.pdo-mysql.php)
- [PHP sodium AEAD](https://www.php.net/manual/en/function.sodium-crypto-aead-xchacha20poly1305-ietf-encrypt.php)
- [Android backup](https://developer.android.com/identity/data/autobackup)
- [OAuth Security BCP](https://www.rfc-editor.org/rfc/rfc9700.html)
