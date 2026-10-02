#requires -Version 7.0
param(
    [ValidateSet('All', 'Viewer', 'Playback', 'Cinema', 'Timeline', 'Ocr', 'Editing', 'ImageEditing', 'VideoEditing', 'Albums', 'Catalog', 'Appearance')]
    [string[]]$Area = @('All'),
    [switch]$UnitOnly,
    [switch]$Delivery,
    [string]$Device = 'emulator-5554',
    [string[]]$GradleOptions = @('--no-daemon', '--max-workers=2', '--console=plain')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'validation-common.ps1')
$root = Split-Path -Parent $PSScriptRoot
if ($Delivery -and ($UnitOnly -or $Area.Count -ne 1 -or $Area[0] -ne 'All')) { throw 'Entrega exige todas as áreas e emulador; não pode usar -UnitOnly.' }
$plan = Get-GalleryTestPlan $root $Area
$fingerprint = Get-GalleryInputFingerprint $root
$reports = Join-Path $root 'app/build/reports/validation'
$run = Join-Path $reports ((Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$runId = Split-Path -Leaf $run
if ($Delivery) {
    @{ runId = $runId; status = 'running' } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $reports 'delivery-attempt.json') -Encoding utf8
}
$timer = [Diagnostics.Stopwatch]::StartNew()
Push-Location $root
try {
    Write-Output 'Validando as ferramentas de teste...'
    & (Join-Path $PSScriptRoot 'test-validation-tools.ps1')
    $tasks = @(':app:testDebugUnitTest')
    if (!$UnitOnly) { $tasks += @(':app:lintDebug', ':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    $wrapper = if ($IsWindows) { Join-Path $root 'gradlew.bat' } else { Join-Path $root 'gradlew' }
    Write-Output "Gradle: $($tasks -join ', ') (log completo em $run/gradle.txt)"
    & $wrapper @tasks @GradleOptions *> (Join-Path $run 'gradle.txt')
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath (Join-Path $run 'gradle.txt') -Tail 25 | Write-Output
        throw 'Compilação/unitários/lint falharam.'
    }
    $unitsDirectory = Join-Path $run 'unit-xml'
    New-Item -ItemType Directory -Path $unitsDirectory | Out-Null
    Copy-Item -Path (Join-Path $root 'app/build/test-results/testDebugUnitTest/TEST-*.xml') -Destination $unitsDirectory
    $unit = Get-GalleryUnitResult $unitsDirectory 225
    Write-Output "Unitários: $($unit.Total) aprovados, zero ignorados."
    $nativePath = $null; $lintPath = $null
    if (!$UnitOnly) {
        $lintPath = Join-Path $run 'lint.xml'
        Copy-Item -LiteralPath (Join-Path $root 'app/build/reports/lint-results-debug.xml') -Destination $lintPath
        $lint = Get-GalleryLintResult $lintPath
        Write-Output "Lint: zero erros, $($lint.Warnings) avisos. Interface: $($plan.Expected) testes ($($Area -join ', '))."
        $runner = Join-Path $PSScriptRoot 'run-android-tests.ps1'
        $runnerArguments = @('-NoProfile', '-File', $runner, '-Device', $Device, '-ReportDirectory', $run, '-ExpectedTests', $plan.Expected)
        if ($Area -contains 'All') { $runnerArguments += '-All' } else { $runnerArguments += @('-Class', ($plan.Classes -join ',')) }
        # Child process isolates the runner's exit code. No SkipInstall here:
        # the receipt must describe the exact APKs built in this invocation.
        & (Get-Process -Id $PID).Path @runnerArguments
        if ($LASTEXITCODE -ne 0) { throw 'Testes de interface falharam, foram ignorados ou ficaram incompletos.' }
        $nativePath = @(Get-ChildItem -LiteralPath $run -Filter '*-run-1.txt').FullName
        if (@($nativePath).Count -ne 1) { throw 'Relatório instrumentado ausente/ambíguo.' }
        $nativePath = [string]$nativePath
    }
    if ($fingerprint -ne (Get-GalleryInputFingerprint $root)) { throw 'Entradas mudaram durante os testes; aprovação rejeitada.' }
    $mode = if ($Delivery) { 'delivery' } elseif ($UnitOnly) { 'unit' } else { 'area' }
    $unitFiles = @(Get-ChildItem -LiteralPath $unitsDirectory -Filter 'TEST-*.xml')
    $evidence = @($unitFiles.FullName) + @(Join-Path $run 'gradle.txt')
    if (!$UnitOnly) {
        $evidence += @($nativePath, $lintPath, (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk'), (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
    }
    $relative = { param($path) [IO.Path]::GetRelativePath($root, $path).Replace('\', '/') }
    $record = [ordered]@{
        schema = 1; runId = $runId; mode = $mode; status = 'passed'; area = $Area
        device = if (!$UnitOnly) { $Device } else { $null }; buildType = 'debug'
        completedUtc = [DateTime]::UtcNow.ToString('o'); fingerprint = $fingerprint
        unitReports = @($unitFiles | ForEach-Object { & $relative $_.FullName })
        nativeReport = if ($nativePath) { & $relative $nativePath } else { $null }
        lintReport = if ($lintPath) { & $relative $lintPath } else { $null }
        evidence = @($evidence | ForEach-Object { @{ path = (& $relative $_); hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash } })
    }
    $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $run 'result.json') -Encoding utf8
    if ($Delivery) {
        @{ runId = $runId; status = 'passed' } | ConvertTo-Json |
            Set-Content -LiteralPath (Join-Path $reports 'delivery-attempt.json') -Encoding utf8
        # Check the full evidence before updating the latest delivery pointer.
        $checked = Assert-GalleryDelivery $root (Join-Path $run 'result.json')
        Copy-Item -LiteralPath (Join-Path $run 'result.json') -Destination (Join-Path $reports 'delivery.json')
        Write-Output "ENTREGA: regressão completa aprovada ($($checked.NativeTests) testes)."
    }
    $timer.Stop()
    Write-Output "Validação $mode aprovada em $([math]::Round($timer.Elapsed.TotalSeconds, 1)) s. Evidência: $run/result.json"
} catch {
    # Keep old reports for diagnosis, but explicitly block an old approval after
    # this failure. A targeted retry is never promoted to delivery approval.
    @{ status = 'failed'; reason = $_.Exception.Message; mode = if ($Delivery) { 'delivery' } else { 'development' } } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'failure.json') -Encoding utf8
    Register-GalleryValidationFailure $root $_.Exception.Message
    if ($Delivery) {
        @{ runId = $runId; status = 'failed' } | ConvertTo-Json |
            Set-Content -LiteralPath (Join-Path $reports 'delivery-attempt.json') -Encoding utf8
    }
    throw
} finally { Pop-Location }
