#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$Apk,
    [string]$Device = 'emulator-5554',
    [string]$ReportDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'validation-common.ps1')
$root = Split-Path -Parent $PSScriptRoot
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$testApk = Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($IsWindows) { Join-Path $env:LOCALAPPDATA 'Android/Sdk' } else { throw 'Configure ANDROID_HOME.' }
$aapt = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending |
    ForEach-Object { Join-Path $_.FullName $(if ($IsWindows) { 'aapt2.exe' } else { 'aapt2' }) } |
    Where-Object { Test-Path -LiteralPath $_ })[0]
if (!$aapt) { throw 'aapt2 não encontrado no SDK.' }
$badging = & $aapt dump badging $apkPath
if ($LASTEXITCODE -ne 0 -or ($badging -join "`n") -notmatch "package: name='com.galeria.android'") { throw 'APK não pertence à galeria.' }
if (($badging -join "`n") -match 'application-debuggable') { throw 'Smoke release rejeita APK debuggable; teste o código otimizado.' }
if ((& adb -s $Device shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Smoke exclusivo para emulador dedicado; não instala em celulares.' }
if ((& adb -s $Device shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Emulador não está pronto.' }
Assert-GalleryDeviceWindow ((& adb -s $Device shell dumpsys window) -join "`n")
if (!$ReportDirectory) { $ReportDirectory = Join-Path $root ('app/build/reports/release-ocr/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
$wrapper = Join-Path $root $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
Push-Location $root
try {
    & $wrapper :app:assembleDebugAndroidTest -PocrReleaseSmoke=true --no-daemon --max-workers=2 --console=plain *> (Join-Path $ReportDirectory 'test-build.txt')
    if ($LASTEXITCODE -ne 0) { throw "Não foi possível compilar o runner SDK-only. Relatórios: $ReportDirectory" }
} finally { Pop-Location }
foreach ($package in @($apkPath, $testApk)) {
    $installed = & adb -s $Device install -r $package
    if ($LASTEXITCODE -ne 0 -or $installed -notcontains 'Success') {
        throw 'Instalação recusada. Use assinatura compatível; o script não desinstala nem limpa dados.'
    }
}
& adb -s $Device logcat -c
$timer = [Diagnostics.Stopwatch]::StartNew()
$output = & adb -s $Device shell am instrument -w -r 'com.galeria.android.test/com.galeria.android.OcrReleaseSmokeRunner'
$instrumentationExit = $LASTEXITCODE
$timer.Stop()
$output | Set-Content -LiteralPath (Join-Path $ReportDirectory 'instrumentation.txt') -Encoding utf8
& adb -s $Device logcat -d -v brief -s ComponentDiscovery GalleryOCR AndroidRuntime |
    Set-Content -LiteralPath (Join-Path $ReportDirectory 'logcat.txt') -Encoding utf8
$result = ConvertFrom-GalleryInstrumentation $output $instrumentationExit 1
[ordered]@{
    mode = 'release-ocr-smoke'; passed = $result.Success; tests = $result.Total
    device = $Device; seconds = [math]::Round($timer.Elapsed.TotalSeconds, 1)
    apkSha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash
    testApkSha256 = (Get-FileHash -LiteralPath $testApk -Algorithm SHA256).Hash
    reason = $result.Reason
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ReportDirectory 'result.json') -Encoding utf8
if (!$result.Success) {
    Get-Content -LiteralPath (Join-Path $ReportDirectory 'instrumentation.txt') -Tail 35 | Write-Output
    throw "Smoke OCR release falhou: $($result.Reason). Relatórios: $ReportDirectory"
}
Write-Output "OCR release: 1 teste aprovado em $([math]::Round($timer.Elapsed.TotalSeconds, 1)) s. Relatórios: $ReportDirectory"
