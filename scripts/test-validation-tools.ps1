#requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'validation-common.ps1')
$script:checks = 0
function Check([string]$Name, [scriptblock]$Body) {
    try { & $Body; $script:checks++ } catch { throw "Contrato '$Name': $($_.Exception.Message)" }
}
function Require([bool]$Condition) { if (!$Condition) { throw 'Condição não cumprida.' } }
function Reject([scriptblock]$Body) {
    $rejected = $false
    try { & $Body | Out-Null } catch { $rejected = $true }
    Require $rejected
}
function NativeLines([int]$Count = 1, [int]$ResultCode = 0) {
    $lines = @()
    for ($index = 1; $index -le $Count; $index++) {
        $lines += @("INSTRUMENTATION_STATUS: numtests=$Count", 'INSTRUMENTATION_STATUS_CODE: 1', "INSTRUMENTATION_STATUS_CODE: $ResultCode")
    }
    $lines += @("OK ($Count tests)", 'INSTRUMENTATION_CODE: -1')
    return $lines
}

Check 'instrumentação válida' { Require (ConvertFrom-GalleryInstrumentation (NativeLines) 0 1).Success }
Check 'falha adb' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines) 1).Success) }
Check 'ignore não vira aprovação' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 1 -3) 0).Success) }
Check 'assumption não vira aprovação' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 1 -4) 0).Success) }
Check 'erro apesar de resumo OK' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 1 -1) 0).Success) }
Check 'falha apesar de resumo OK' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 1 -2) 0).Success) }
Check 'código desconhecido rejeitado' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 1 42) 0).Success) }
Check 'zero testes rejeitado' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines 0) 0).Success) }
Check 'interrupção sem código final' { Require (!(ConvertFrom-GalleryInstrumentation ((NativeLines) | Where-Object { $_ -notmatch '^INSTRUMENTATION_CODE' }) 0).Success) }
Check 'resumo sem conclusões' { Require (!(ConvertFrom-GalleryInstrumentation @('OK (1 test)', 'INSTRUMENTATION_CODE: -1') 0).Success) }
Check 'resumo duplicado' { Require (!(ConvertFrom-GalleryInstrumentation ((NativeLines) + 'OK (1 tests)') 0).Success) }
Check 'quantidade parcial' { Require (!(ConvertFrom-GalleryInstrumentation (NativeLines) 0 2).Success) }
Check 'conclusão sem início' { Require (!(ConvertFrom-GalleryInstrumentation ((NativeLines) | Where-Object { $_ -ne 'INSTRUMENTATION_STATUS_CODE: 1' }) 0).Success) }

$repo = Split-Path -Parent $PSScriptRoot
Check 'todas as áreas resolvem classes existentes' {
    foreach ($area in @('All', 'Viewer', 'Playback', 'Cinema', 'Timeline', 'Ocr', 'Editing', 'ImageEditing', 'VideoEditing', 'Albums', 'Catalog', 'Appearance')) {
        Require ((Get-GalleryTestPlan $repo @($area)).Expected -gt 0)
    }
}
Check 'base instrumentada após três migrações' { Require ((Get-GalleryTestPlan $repo @('All')).Expected -ge 89) }
Check 'Cinema não executa o cache inteiro da timeline' {
    $plan = Get-GalleryTestPlan $repo @('Cinema')
    Require ($plan.Classes -notcontains 'com.galeria.android.VideoTimelineInstrumentedTest')
    Require ($plan.Classes -contains 'com.galeria.android.VideoTimelineInstrumentedTest#backgroundDuringDragKeepsSamePlayerAndLateReleaseCannotUndoPause')
    Require ($plan.Classes -contains 'com.galeria.android.CinemaModeInstrumentedTest')
}
Check 'edição de foto não executa todos os testes de OCR' {
    $plan = Get-GalleryTestPlan $repo @('ImageEditing')
    Require ($plan.Classes -notcontains 'com.galeria.android.ImageTextRecognitionInstrumentedTest')
    Require ($plan.Classes -contains 'com.galeria.android.ImageTextRecognitionInstrumentedTest#documentLongPressAndCustomEditorOfferCopyableText')
    Require ($plan.Classes -notcontains 'com.galeria.android.VideoEditInstrumentedTest')
}
Check 'Editing equivale à união das edições de foto e vídeo' {
    $both = Get-GalleryTestPlan $repo @('ImageEditing', 'VideoEditing')
    $editing = Get-GalleryTestPlan $repo @('Editing')
    Require ($both.Expected -eq $editing.Expected)
    Require (!(Compare-Object $both.Classes $editing.Classes))
}
Check 'áreas juntas não repetem métodos da timeline' {
    $plan = Get-GalleryTestPlan $repo @('Cinema', 'Timeline')
    Require ($plan.Classes -contains 'com.galeria.android.VideoTimelineInstrumentedTest')
    Require (!($plan.Classes | Where-Object { $_ -like 'com.galeria.android.VideoTimelineInstrumentedTest#*' }))
}
Check 'combinação remove duplicatas' {
    $plan = Get-GalleryTestPlan $repo @('Viewer', 'Ocr')
    Require ($plan.Classes -notcontains 'com.galeria.android.VideoViewerRegressionTest#queuedManualOcrCannotRevealTextAfterNavigationAndCanBeRetried')
    Require ($plan.Classes -contains 'com.galeria.android.VideoViewerRegressionTest')
}
Check 'All não pode virar área parcial' { Reject { Get-GalleryTestPlan $repo @('All', 'Ocr') } }
Check 'área inexistente falha' { Reject { Get-GalleryTestPlan $repo @('Typo') } }

# Synthetic receipts exercise the automation only, never the Android app. They
# live in a unique temporary fixture and cannot match the real source/APK hashes.
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('Galeria-validation-tools-' + [Guid]::NewGuid().ToString('N'))
$inputDir = Join-Path $fixture 'app/src/androidTest/kotlin/com/galeria/android'
$reports = Join-Path $fixture 'app/build/reports/validation'
$run = Join-Path $reports 'synthetic'
foreach ($directory in @($inputDir, $run, (Join-Path $fixture 'app/build/outputs/apk/debug'), (Join-Path $fixture 'app/build/outputs/apk/androidTest/debug'))) {
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
}
('@Test fun case() {}' + "`n") * 89 | Set-Content -LiteralPath (Join-Path $inputDir 'SyntheticTest.kt')
$unitPath = Join-Path $run 'TEST-unit.xml'
$lintPath = Join-Path $run 'lint.xml'
$nativePath = Join-Path $run 'native.txt'
'<testsuite tests="225" failures="0" errors="0" skipped="0" />' | Set-Content -LiteralPath $unitPath
'<issues><issue severity="Warning" /></issues>' | Set-Content -LiteralPath $lintPath
NativeLines 89 | Set-Content -LiteralPath $nativePath
foreach ($path in @('app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
    'synthetic, not an Android APK' | Set-Content -LiteralPath (Join-Path $fixture $path)
}
Check 'unitários válidos' { Require ((Get-GalleryUnitResult $run 225).Total -eq 225) }
Check 'menos unitários rejeitado' { Reject { Get-GalleryUnitResult $run 226 } }
Check 'lint com avisos permitido' { Require ((Get-GalleryLintResult $lintPath).Warnings -eq 1) }
Check 'lint com erro bloqueia' {
    '<issues><issue severity="Error" /></issues>' | Set-Content -LiteralPath $lintPath
    Reject { Get-GalleryLintResult $lintPath }
    '<issues><issue severity="Warning" /></issues>' | Set-Content -LiteralPath $lintPath
}
Check 'XML que não é lint rejeitado' {
    '<unrelated />' | Set-Content -LiteralPath $lintPath
    Reject { Get-GalleryLintResult $lintPath }
    '<issues><issue severity="Warning" /></issues>' | Set-Content -LiteralPath $lintPath
}
Check 'unitário ignorado bloqueia' {
    '<testsuite tests="225" failures="0" errors="0" skipped="1" />' | Set-Content -LiteralPath $unitPath
    Reject { Get-GalleryUnitResult $run 225 }
    '<testsuite tests="225" failures="0" errors="0" skipped="0" />' | Set-Content -LiteralPath $unitPath
}
Check 'unitário com erro bloqueia' {
    '<testsuite tests="225" failures="0" errors="1" skipped="0" />' | Set-Content -LiteralPath $unitPath
    Reject { Get-GalleryUnitResult $run 225 }
    '<testsuite tests="225" failures="0" errors="0" skipped="0" />' | Set-Content -LiteralPath $unitPath
}
$fingerprint = Get-GalleryInputFingerprint $fixture
Check 'saídas de build não invalidam fontes' {
    'output' | Set-Content -LiteralPath (Join-Path $run 'extra-output.txt')
    Require ($fingerprint -eq (Get-GalleryInputFingerprint $fixture))
}
$relative = { param($path) [IO.Path]::GetRelativePath($fixture, $path).Replace('\', '/') }
$record = @{
    schema = 1; runId = 'synthetic'; mode = 'delivery'; status = 'passed'; completedUtc = [DateTime]::UtcNow.ToString('o')
    fingerprint = $fingerprint; unitReports = @(& $relative $unitPath); lintReport = (& $relative $lintPath); nativeReport = (& $relative $nativePath)
    evidence = @(@($unitPath, $lintPath, $nativePath, (Join-Path $fixture 'app/build/outputs/apk/debug/app-debug.apk'), (Join-Path $fixture 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) |
        ForEach-Object { @{ path = (& $relative $_); hash = (Get-FileHash -LiteralPath $_).Hash } })
}
$receipt = Join-Path $run 'result.json'
function SaveReceipt { $record | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $receipt }
function SaveAttempt([string]$State = 'passed', [string]$Id = 'synthetic') {
    @{ runId = $Id; status = $State } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $reports 'delivery-attempt.json')
}
SaveReceipt; SaveAttempt
Check 'evidência íntegra aceita' { Require ((Assert-GalleryDelivery $fixture $receipt).NativeTests -eq 89) }
Check 'fontes alterados invalidam aprovação' {
    '@Test fun added() {}' | Add-Content -LiteralPath (Join-Path $inputDir 'SyntheticTest.kt')
    Reject { Assert-GalleryDelivery $fixture $receipt }
    ('@Test fun case() {}' + "`n") * 89 | Set-Content -LiteralPath (Join-Path $inputDir 'SyntheticTest.kt')
}
Check 'APK alterado invalida aprovação' {
    'modified' | Add-Content -LiteralPath (Join-Path $fixture 'app/build/outputs/apk/debug/app-debug.apk')
    Reject { Assert-GalleryDelivery $fixture $receipt }
    'synthetic, not an Android APK' | Set-Content -LiteralPath (Join-Path $fixture 'app/build/outputs/apk/debug/app-debug.apk')
}
Check 'relatório adulterado invalida aprovação' {
    'changed' | Add-Content -LiteralPath $nativePath
    Reject { Assert-GalleryDelivery $fixture $receipt }
    NativeLines 89 | Set-Content -LiteralPath $nativePath
}
Check 'teste parcial não libera entrega' {
    $record.mode = 'area'; SaveReceipt; Reject { Assert-GalleryDelivery $fixture $receipt }; $record.mode = 'delivery'; SaveReceipt
}
Check 'tentativa posterior falhada bloqueia aprovação antiga' {
    SaveAttempt 'failed'; Reject { Assert-GalleryDelivery $fixture $receipt }; SaveAttempt
}
Check 'tentativa em andamento bloqueia aprovação antiga' {
    SaveAttempt 'running'; Reject { Assert-GalleryDelivery $fixture $receipt }; SaveAttempt
}
Check 'outra execução substitui evidência antiga' {
    SaveAttempt 'passed' 'new-run'; Reject { Assert-GalleryDelivery $fixture $receipt }; SaveAttempt
}
Check 'falha de área depois da aprovação bloqueia entrega' {
    Register-GalleryValidationFailure $fixture 'synthetic failure'
    Reject { Assert-GalleryDelivery $fixture $receipt }
    # Only this synthetic fixture is advanced; real evidence is never modified.
    $record.completedUtc = [DateTime]::UtcNow.AddSeconds(1).ToString('o'); SaveReceipt
}
Check 'evidência fora do projeto rejeitada' {
    $previous = $record.evidence[0].path; $record.evidence[0].path = '../outside.xml'; SaveReceipt
    Reject { Assert-GalleryDelivery $fixture $receipt }; $record.evidence[0].path = $previous; SaveReceipt
}
Check 'relatório obrigatório ausente rejeitado' {
    $previous = $record.nativeReport; $record.nativeReport = 'missing'; SaveReceipt
    Reject { Assert-GalleryDelivery $fixture $receipt }; $record.nativeReport = $previous; SaveReceipt
}
Check 'aprovação continua verificável depois dos casos negativos' { Require ((Assert-GalleryDelivery $fixture $receipt).UnitTests -eq 225) }
Check 'ANR do SystemUI bloqueia antes dos testes' {
    Reject { Assert-GalleryDeviceWindow '  mCurrentFocus=Window{abc u0 Application Not Responding: com.android.systemui}' }
}
Check 'ANR de qualquer app também bloqueia' {
    Reject { Assert-GalleryDeviceWindow '  mCurrentFocus=Window{abc u0 Application Not Responding: com.galeria.android}' }
}
Check 'janela saudável não é confundida com ANR histórico' {
    Assert-GalleryDeviceWindow "  mCurrentFocus=Window{abc u0 com.galeria.android/.MainActivity}`n  old-title=Application Not Responding: com.android.systemui"
}
Write-Output "Ferramentas de validação: $script:checks contratos aprovados (sem emulador; fixtures sintéticos)."
