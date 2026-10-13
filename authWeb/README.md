# wakAnd OAuth backend

Androidの `app/` と同階層の `authWeb/` でPHPバックエンドを管理する。
公開URLは **https://yui.eng.pro/android/wakAnd/**。
以前のサブドメイン案は廃止。URLパスの `wakAnd` の大文字小文字は維持する。
関連issue: #2、#8、#9、#12。現在は配置・CIの土台のみでOAuthは未実装。

## 配置とURL

| 項目 | 設定 |
| --- | --- |
| 管理者指定のAndroid用フォルダ | `~/www/webroot/android` |
| wakAnd公開ファイルの物理配置予定 | `~/www/webroot/android/wakAnd/` |
| FTP接続先 | `yui.eng.pro` |
| FTPから見える転送先（管理者指定） | `/android/wakAnd/` |
| リポジトリ内の公開ファイル | `authWeb/public/` |
| CIが生成する転送用ディレクトリ | `authWeb/dist/` |

FTPのルートとOSのホームディレクトリは別物。FTPパスに `~` を入れない。
物理パス・FTPパス・URLの対応は初回配置時に実測確認する。
共有の `/android/` 自体へ同期しない。

```text
app/
authWeb/
  public/index.php       配置確認用PHP
  deploy-files.json      公開ファイルの許可リスト
  scripts/deploy.py      許可リストからdistを作成（通信はしない）
  tests/                 公開境界・転送先の検証
.github/workflows/auth-web.yml
```

公開するのは許可リストにある `public/` 内のファイルと、秘密値を持たない
`src/Config.php` をコピーした `_config.php`。設定・暗号鍵・DBは `~/www/` の外に置く。
秘密設定の転送は管理者が手動で行い、このCIでは扱わない。
配置確認ページは `oauth_ready: false` を返す。認証の稼働確認ではない。

## 認証構成案

### 本番の秘密設定

WakaTimeアプリは既存登録を流用し、client_idは `pJDPWyzHvrc7iconHrnmrPaa`。
管理者が `config/oauth.example.php` をサーバーの **`~/.config/wakand/oauth.php`** として
配置し、サーバー上のコピーだけにApp Secretを記入する。`~`はホームディレクトリを
表す。以前のホーム直下 `.php.config` 案から、プロジェクト専用の配置へ変更した。
ファイル内の配列は従来のひな形と同じ `wakand` セクションを使う。

- ファイルは `~/www/` の外。UNIX権限はまず600とし、PHP実行ユーザーに読み取りが
  必要な場合は所有者・グループに合わせて調整する。公開ディレクトリへ移動しない。
- `client_secret` のプレースホルダーだけを置換する。PHPの単一引用符内では
  バックスラッシュと単一引用符をそれぞれ `\\` と `\'` にエスケープする。
- 実Secret入りファイルはGit・CI転送物へ入れない。NASの控えは管理者が手動で保管する。
- PHPは `~` を自動展開しない。実装時は確定したホームの絶対パスを渡す。
  WebリクエストのパラメーターやHostヘッダーから設定ファイルのパスを組み立てない。
- `src/Config.php` の読み込み関数は秘密設定と `~/www/` の絶対パスを受け取り、
  シンボリックリンク解決後に公開領域内を拒否する。欠落・未編集・不正な設定は
  秘密値を含まないエラーとして扱う。
- 公開用index.phpは引き続き配置確認用。
  `/android/wakAnd/config-check.php` は設定を読み込み、App ID・redirect URI・
  Secretの非空/未編集チェック結果だけを `configured` の真偽値で返す。
  値・秘密ファイルのパス・例外詳細は返さない。成功時200、失敗時503。
  このチェックはWakaTimeへの通信を行わず、Secretの正当性・token交換の成功は保証しない。

[環境パス資料](https://faq.interlink.or.jp/faq2/View/md.aspx?id=999&view)では
公開領域は `/home/アカウント名/www/`、PHPコマンドは `/usr/local/bin/php`。
管理画面のホーム名を当てはめた配置案は以下（PHPからの実読取は別途確認）:

```text
/home/yui-kitamura/.config/wakand/oauth.php     管理者が手動配置する秘密設定
/home/yui-kitamura/www/            公開領域全体。秘密設定を置かない
```

資料の755/705は公開実行ファイル・ディレクトリ向けであり、秘密設定へそのまま
適用しない。URLの `/android/`、FTPの `/android/` と
OS上の `/home/...` を混同しない。ドメイン別公開ディレクトリの対応は既存設定に従う。

### 認証の責務

- PHP 8.3以上（新規環境は8.4以上を推奨）、外部HTTPS通信、SQLiteまたはMySQL。
- client_secretと暗号化したrefresh tokenはサーバー、access tokenと
  端末別バックエンド認証情報はAndroidに保持する。
- 認可・更新・解除はPHP経由、通常のWakaTime APIはAndroidから直接呼ぶ。
- cronは必須にしない。有効期限は使用時に必ず検証し、期限切れデータは
  アクセス時に件数制限付きで削除する。無アクセス時の削除期限保証や
  失効失敗の自動再試行が必要になったら定期実行を追加する。

予定経路（未実装・未登録）:

| URLパス | 用途 |
| --- | --- |
| `/android/wakAnd/v1/link/start` | 端末と認可セッションの紐付け |
| `/android/wakAnd/oauth/callback` | WakaTimeのcallback、state検証・交換 |
| `/android/wakAnd/app/complete` | Androidへ戻るApp Link |
| `/android/wakAnd/v1/link/complete` | 開始端末への一回限りの受け渡し |
| `/android/wakAnd/v1/token/refresh` | 端末認証・排他制御付き更新 |
| `/android/wakAnd/v1/link/disconnect` | 対象連携の失効・削除 |

登録redirect URI候補:
`https://yui.eng.pro/android/wakAnd/oauth/callback`。
認可・交換・更新で同じ値を使う。上位サイトのrewriteに吸われないことを
確認し、実装時にこのパス配下だけのルーティングを追加する。

## Android App Links

App Linksの関連付けファイルは **https://yui.eng.pro/.well-known/assetlinks.json**。
`/android/wakAnd/.well-known/` ではない。配置実体は
`site-root/.well-known/assetlinks.json`。このCIの転送範囲に含めず、
サイト管理者が既存の関連付けを保持して追記する。

applicationIdは `pro.eng.yui.android.wakAnd` のまま。
配布版署名のSHA-256を確認して関連付ける。Androidの受信対象は
`/android/wakAnd/app/complete` に限定し、OAuth callbackや他のサイトを受信しない。
認可トークンはURLへ載せない。Cookieを使う場合はこのアプリのパスに限定するが、
同一origin上の別コンテンツとはブラウザのセキュリティ境界を共有する。

## CIと払い出し

[既存CI](https://github.com/yui-Kitamura/SeibuAkitsuSignBoard/blob/main/.github/workflows/publishStaticGHPage.yml)
と同じ `SamKirkland/FTP-Deploy-Action@v4.3.4`、同じSecrets名を使う。
PHPはWebサーバーで実行するため、GitHub Pagesへの配信・artifactの再取得は不要。

```yaml
with:
  server: yui.eng.pro
  username: ${{ secrets.ftp_user }}
  password: ${{ secrets.ftp_password }}
  local-dir: ./authWeb/dist/
  server-dir: /android/wakAnd/
```

管理者にお願いするもの:

1. 上記ディレクトリとURLの対応、既存 `yui.eng.pro` のHTTPSでPHPが動くことの確認。
   新しいサブドメイン・DNS・証明書の払い出しは不要。
2. 専用FTPユーザーと `/android/wakAnd/` への転送権限。
3. GitHub Environment `auth-web-production` と、Secrets `ftp_user` / `ftp_password`。
   Environmentの配布許可ブランチはmainとwk1oauthに限定する。
4. PHPバージョン、cURL/OpenSSL/sodium/PDO SQLite（またはPDO MySQL）、
   公開領域外の保存先、外部WakaTimeへのHTTPS通信の確認。
5. App Links用のサイト共通 `/.well-known/assetlinks.json` への追記。

`protocol`省略は既存CIと同じ平文FTP（既定21番）。FTPS利用可能なら
`protocol: ftps` と `security: strict` を追加する。現状のCIは秘密設定やDBを転送しない。
パスワードやOAuth secretはチャット・issue・ソースへ貼らない。

## 検証と公開

- 対象ファイル変更のPR/main・wk1oauth push、および手動実行で検証する。
- main・wk1oauth pushまたはそのブランチからの手動実行は、検証後にFTP同期する。
- Pythonテストで公開許可リスト・パス逸脱・共有ルートへの誤転送を検証する。
- CIでPHP構文と `/android/wakAnd/` 配下のHTTP応答を検証する。
- FTP同期は専用の `.wakand-auth-sync-state.json` を使用する。
  `dangerous-clean-slate` は無効。ただし以前このCIが管理したファイルが
  許可リストから外れた場合は同期処理で削除される。共有ファイルを管理対象に入れない。
- ファイル単位の同期で、リリース全体の原子的切替・自動ロールバックはない。
  OAuth本体公開前に切替・失敗時の復旧方式を確定する。
- 転送後に公開URLのJSON応答を確認する。PHPの実行が確認できるまで
  secretをサーバーへ配置しない。
- 実サーバー接続・転送・GitHub CI実行は未実施。

ローカル検証: `python -m unittest discover -s authWeb/tests -v`。
PHPがある環境では `php -l authWeb/public/index.php`。

出典: [WakaTime](https://wakatime.com/developers#authentication)、
[App Links](https://developer.android.com/training/app-links/configure-assetlinks)、
[FTP Action](https://github.com/SamKirkland/FTP-Deploy-Action/tree/v4.3.4)。
