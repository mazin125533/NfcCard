# NFC名刺

好きな画像を名刺として全画面に表示し、スマホをかざした相手に、その名刺に設定した URL を NFC で渡す Android アプリです。
HCE（Host Card Emulation）で **NFC Forum Type 4 タグ**をエミュレートし、URI レコードの NDEF を返します。
受け取る側はアプリ不要で、ふつうの NFC タグを読んだときと同じようにブラウザで URL が開きます（Android / iPhone XS 以降）。

## 使い方
1. 「名刺を追加」で画像を選び（複数まとめて選べます）、名前と渡したい URL を入力して保存（`https://` は省略可）
2. 名刺が全画面で表示されたら、相手のスマホの背面にかざす
3. 相手が読み取ると「ブブッ」と振動して知らせます

- 名刺は何枚でも登録できます。2枚以上あると画面上部にタブが出て、タップで切り替えます。選んでいる名刺の URL が渡されます
- 1つの名刺に画像を何枚でも入れられ、左右スワイプで切り替えます
- 画像ごとに裏面を設定できます。ダブルタップか「裏返す」で裏返します
- 編集画面では画像の追加・並べ替え（◀ ▶）・差し替え・取り外し、裏面の設定ができます
- 画面をタップすると「裏返す」「追加」「編集」ボタンが出ます（3秒で隠れます）
- 名刺を表示している間は画面が消えません。アプリを閉じている間はタグとして応答しません
- 画像はアプリ内にコピーして保存するので、元の写真を消しても名刺は残ります（長辺 2560px に縮小）
- 相手の端末は画面ロックを解除し、NFC をオンにしてください

## 必要なもの
- Android 8.0 以上、NFC / HCE 対応端末

## ビルド
JDK 17 と Android SDK（API 35）が必要です。
```
./gradlew assembleDebug
```
APK は `app/build/outputs/apk/debug/` に出力されます。実機テストは `./gradlew connectedDebugAndroidTest`。
デバッグ版はパッケージ名が `io.github.mazin125533.nfccard.debug`（表示名「NFC名刺 (debug)」）なので、リリース版と並べて入れられます。

## リリース（GitHub Actions）
`v*` タグを push すると、署名付きの APK がビルドされて GitHub Release に添付されます。
```
git tag v1.0.0
git push origin v1.0.0
```
main への push と PR でもリリース APK をビルドし、Actions の Artifacts に保存します。

### 署名鍵の準備（最初に1回だけ）
```
powershell -ExecutionPolicy Bypass -File tools\setup-release-signing.ps1
```
鍵を `%USERPROFILE%\.keystores` に作成し、GitHub Secrets（`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`）に登録します。
**鍵とパスワードのファイルは必ずバックアップしてください。** 失うと同じアプリとして更新できなくなります。

## ライセンス

[MIT License](LICENSE)。自由に使用・改変・再配布できます。
