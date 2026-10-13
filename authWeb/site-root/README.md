# サイト管理者が配置するファイル

`.well-known/assetlinks.json` を次のURLへ配置する。

https://yui.eng.pro/.well-known/assetlinks.json

これは `/android/wakAnd/` 向けFTP CIには含まれない。
既存のassetlinks.jsonがある場合は既存エントリを残して配列に統合する。
HTTPS・HTTP 200・JSONのContent-Typeで、認証やリダイレクトなしに返す。

署名の根拠（2026/10/13確認）:

- GitHub release `1.4.2` の `app-release.apk`
- APK SHA-256: `b477d7eeba83ee9a842fa4389fd23cbd6721e2b9de6b5bb0f070ea2a7ab449b6`
- Android SDK apksignerで署名検証に成功し、署名証明書のSHA-256を採用。
- applicationId: `pro.eng.yui.android.wakAnd`
- [リリース](https://github.com/Yui-KitamuraLLC/wakAnd/releases/tag/1.4.2)

この指紋はGitHub配布APK用。Google Play App Signingで別の証明書を使っている
場合は、Play Consoleの「アプリ署名鍵の証明書」のSHA-256も配列に追加する。
アップロード鍵とアプリ署名鍵を混同しない。

Android側のApp Links intent-filterは今後実装するため、このファイルを置く
だけではOAuth連携は完成しない。受信対象は `/android/wakAnd/app/complete` に限定する。
