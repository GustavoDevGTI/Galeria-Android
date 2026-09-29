param(
    [string]$Class,
    [switch]$All,
    [ValidateRange(1, 100)][int]$Repeat = 1,
    [string]$Device = 'emulator-5554',
    [switch]$SkipInstall
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
    $started.Stop()

    $summary = $output | Where-Object { $_ -match '^(OK \(\d+ tests?\)|Tests run:|FAILURES!!!)' } | Select-Object -Last 1
    $success = $LASTEXITCODE -eq 0 -and $output -match 'INSTRUMENTATION_CODE: -1' -and
        $output -match '^OK \(\d+ tests?\)'
    $seconds = [math]::Round($started.Elapsed.TotalSeconds, 1)
    if ($success) {
        Write-Output "Execução $run/$Repeat`: PASSOU ($seconds s) — $summary"
    } else {
        $failed++
        Write-Output "Execução $run/$Repeat`: FALHOU ($seconds s) — $summary"
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
