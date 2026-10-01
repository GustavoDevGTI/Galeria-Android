param(
    [string]$Class,
    [switch]$All,
    [ValidateRange(1, 100)][int]$Repeat = 1,
    [string]$Device = 'emulator-5554',
    [switch]$SkipInstall,
    [string]$ReportDirectory
)

$ErrorActionPreference = 'Stop'
if ($All -eq [bool]$Class) {
    throw 'Informe -Class (uma ou mais classes, separadas por vírgula) ou -All.'
}

$root = Split-Path -Parent $PSScriptRoot
$appApk = Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'
$testApk = Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if (!(Test-Path -LiteralPath $appApk) -or !(Test-Path -LiteralPath $testApk)) {
    throw 'APKs de teste ausentes. Compile com :app:assembleDebug :app:assembleDebugAndroidTest primeiro.'
}

$booted = (& adb -s $Device shell getprop sys.boot_completed).Trim()
if ($LASTEXITCODE -ne 0 -or $booted -ne '1') {
    throw "Emulador $Device não está pronto."
}

# The one-time Android full-screen tutorial owns a system window and blocks
# Espresso's input/focus. Seed its acknowledgement on emulators only, not phones.
if ((& adb -s $Device shell getprop ro.kernel.qemu).Trim() -eq '1') {
    & adb -s $Device shell settings put secure immersive_mode_confirmations confirmed
    if ($LASTEXITCODE -ne 0) { throw 'Não foi possível preparar o aviso de tela cheia do emulador.' }
}
if (!$ReportDirectory) { $ReportDirectory = Join-Path $root 'app/build/reports/native-tests' }
New-Item -ItemType Directory -Path $ReportDirectory -Force | Out-Null
$reportPrefix = Get-Date -Format 'yyyyMMdd-HHmmss-fff'

if (!$SkipInstall) {
    foreach ($apk in @($appApk, $testApk)) {
        $installation = & adb -s $Device install -r $apk
        if ($LASTEXITCODE -ne 0 -or $installation -notcontains 'Success') {
            throw "Falha ao instalar $apk : $($installation -join ' ')"
        }
    }
}

$failed = 0
for ($run = 1; $run -le $Repeat; $run++) {
    $arguments = @('-s', $Device, 'shell', 'am', 'instrument', '-w', '-r')
    if (!$All) { $arguments += @('-e', 'class', $Class) }
    $arguments += 'com.galeria.android.test/androidx.test.runner.AndroidJUnitRunner'
    $started = [Diagnostics.Stopwatch]::StartNew()
    $output = & adb @arguments
    $instrumentationExit = $LASTEXITCODE
    $started.Stop()
    $reportPath = Join-Path $ReportDirectory "$reportPrefix-run-$run.txt"
    $output | Set-Content -LiteralPath $reportPath -Encoding UTF8

    $summary = $output | Where-Object { $_ -match '^(OK \(\d+ tests?\)|Tests run:|FAILURES!!!)' } | Select-Object -Last 1
    $success = $instrumentationExit -eq 0 -and $output -match 'INSTRUMENTATION_CODE: -1' -and
        $output -match '^OK \(\d+ tests?\)'
    $seconds = [math]::Round($started.Elapsed.TotalSeconds, 1)
    if ($success) {
        Write-Output "Execução $run/$Repeat`: PASSOU ($seconds s) — $summary"
    } else {
        $failed++
        Write-Output "Execução $run/$Repeat`: FALHOU ($seconds s) — $summary"
        Write-Output "Relatório completo: $reportPath"
        $testClass = ''
        $testName = ''
        foreach ($line in $output) {
            if ($line -match '^INSTRUMENTATION_STATUS: class=(.*)$') { $testClass = $Matches[1] }
            if ($line -match '^INSTRUMENTATION_STATUS: test=(.*)$') { $testName = $Matches[1] }
            if ($line -match '^INSTRUMENTATION_STATUS: stack=(.*)$') {
                Write-Output "$testClass#$testName`: $($Matches[1])"
            }
        }
    }
}

if ($failed -gt 0) { exit 1 }
