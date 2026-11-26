$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$version = '8.11.1'
$cache = Join-Path $env:LOCALAPPDATA 'ShinpStudio\gradle'
$gradle = Join-Path $cache "gradle-$version\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    New-Item -ItemType Directory -Path $cache -Force | Out-Null
    $url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"
    $zip = Join-Path $cache "gradle-$version-bin.zip"
    Invoke-WebRequest -Uri $url -OutFile $zip
    $expected = (Invoke-RestMethod -Uri "$url.sha256").ToString().Trim().Split(' ')[0]
    if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne $expected) { throw 'Gradle SHA256 mismatch' }
    Expand-Archive -Path $zip -DestinationPath $cache -Force
}
if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
}
& $gradle :app:testDebugUnitTest :app:assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host 'APK: app\build\outputs\apk\debug\app-debug.apk'
