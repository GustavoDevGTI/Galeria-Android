#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$PreviousApk,
    [string]$Device = 'emulator-5554',
    [switch]$ResetEmulatorFixture
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$currentApk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
foreach ($path in @($PreviousApk, $currentApk, $testApk)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "APK ausente: $path" }
}
if ((& adb -s $Device shell getprop ro.kernel.qemu).Trim() -ne '1') {
    throw 'A fixture só pode executar no emulador, nunca no celular pessoal.'
}
if ((& adb -s $Device shell getprop sys.boot_completed).Trim() -ne '1') { throw 'Emulador não está pronto.' }
if (!$ResetEmulatorFixture) {
    throw 'Use -ResetEmulatorFixture: a preparação remove SOMENTE a instalação de testes com.galeria.android no emulador. Nenhum dado é limpo entre a versão antiga e a nova.'
}
$report = Join-Path $root ('app/build/reports/upgrade/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $report -Force | Out-Null
function Install-Checked([string]$Path) {
    $result = & adb -s $Device install -r $Path
    if ($LASTEXITCODE -ne 0 -or $result -notcontains 'Success') { throw "Instalação falhou: $result" }
}
function Invoke-Probe([string]$Phase) {
    $result = & adb -s $Device shell am instrument --user 0 -w -r -e upgradePhase $Phase com.galeria.android.test/com.galeria.android.GalleryTestRunner
    $exitCode = $LASTEXITCODE
    $result | Set-Content -LiteralPath (Join-Path $report "$Phase.txt") -Encoding utf8
    if ($exitCode -ne 0 -or ($result -join "`n") -notmatch "UPGRADE_$Phase`: PASSED" -or
        ($result -join "`n") -match 'FAILED|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Fixture $Phase falhou; consulte $report/$Phase.txt"
    }
}
& adb -s $Device uninstall com.galeria.android.test | Out-Null
& adb -s $Device uninstall com.galeria.android | Out-Null
Install-Checked $PreviousApk
Install-Checked $testApk
foreach ($permission in @('android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO')) {
    & adb -s $Device shell pm grant com.galeria.android $permission
    if ($LASTEXITCODE -ne 0) { throw "Falha ao conceder $permission" }
}
Invoke-Probe 'seed'
# The only operation between seed and verify is an in-place install, retaining
# SharedPreferences, database, permissions and the private thumbnail cache.
Install-Checked $currentApk
Invoke-Probe 'verify'
@{
    previousSha256 = (Get-FileHash -LiteralPath $PreviousApk).Hash
    currentSha256 = (Get-FileHash -LiteralPath $currentApk).Hash
    testSha256 = (Get-FileHash -LiteralPath $testApk).Hash
    status = 'passed'; device = $Device; sourceVersion = '0.8.62'
    dataClearedBetweenInstallations = $false
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $report 'result.json') -Encoding utf8
Write-Output "Atualização por cima APROVADA, sem limpar dados: $report/result.json"
