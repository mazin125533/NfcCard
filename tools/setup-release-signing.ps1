# リリース用の署名鍵を作り、GitHub Actions の Secrets に登録する（最初に1回だけ実行）
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-release-signing.ps1
#
# - 鍵はリポジトリの外（%USERPROFILE%\.keystores）に作る。リポジトリには絶対に入れない
# - パスワードはランダム生成し、鍵と同じフォルダのテキストに保存する
# - この鍵を失うと同じアプリとして更新できなくなるので、両方のファイルをバックアップすること
# 必要なもの: JDK（keytool）、gh CLI（gh auth login 済み）
$ErrorActionPreference = 'Stop'

$repo  = 'mazin125533/NfcCard'
$alias = 'nfccard'
$dir   = Join-Path $env:USERPROFILE '.keystores'
$ks    = Join-Path $dir 'nfccard-release.jks'
$note  = Join-Path $dir 'nfccard-release-password.txt'

$keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
if (-not $keytool) {
    $keytool = Get-ChildItem 'C:\Program Files\Java', 'C:\Program Files\Eclipse Adoptium' -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $keytool) { throw 'keytool が見つかりません（JDK をインストールしてください）' }

$gh = (Get-Command gh -ErrorAction SilentlyContinue).Source
if (-not $gh) { $gh = 'C:\Program Files\GitHub CLI\gh.exe' }
if (-not (Test-Path $gh)) { throw 'gh が見つかりません（winget install GitHub.cli）' }

New-Item -ItemType Directory -Force $dir | Out-Null

if (Test-Path $ks) {
    Write-Host "既存の鍵を使います: $ks"
    $pw = (Get-Content $note | Where-Object { $_ -like 'password*' }) -replace '^password[^:]*:\s*', ''
    if (-not $pw) { throw "パスワードが $note から読めません" }
} else {
    $bytes = New-Object byte[] 21
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $pw = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'

    & $keytool -genkeypair -keystore $ks -storetype PKCS12 -alias $alias -keyalg RSA -keysize 4096 `
        -validity 10000 -storepass $pw -keypass $pw -dname 'CN=mazin125533, O=NfcCard'
    if ($LASTEXITCODE -ne 0) { throw 'keytool に失敗しました' }

    @(
        'NfcCard リリース署名鍵'
        "keystore: $ks"
        "alias: $alias"
        "password (store/key 共通): $pw"
        ''
        'この鍵を失うと、同じアプリとして更新できなくなります。安全な場所にバックアップしてください。'
    ) | Set-Content -Encoding utf8 $note
    Write-Host "鍵を作成しました: $ks"
}

# Secrets に登録（値は標準入力で渡し、画面やコマンド履歴に残さない）
[Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)) | & $gh secret set ANDROID_KEYSTORE_BASE64 --repo $repo
$pw    | & $gh secret set ANDROID_KEYSTORE_PASSWORD --repo $repo
$pw    | & $gh secret set ANDROID_KEY_PASSWORD      --repo $repo
$alias | & $gh secret set ANDROID_KEY_ALIAS         --repo $repo

& $gh secret list --repo $repo
Write-Host ''
Write-Host "完了。$dir の2ファイルをバックアップしてください。"
