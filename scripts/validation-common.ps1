Set-StrictMode -Version Latest

function Assert-GalleryDeviceWindow {
    param([string]$WindowDump)
    if ($WindowDump -match '(?im)^\s*mCurrentFocus=.*Application Not Responding:') {
        throw 'Uma janela ANR está interceptando a interface do Android. Recupere o dispositivo antes de executar testes; nenhuma falha será ignorada.'
    }
}

function Get-GalleryTestPlan {
    param([string]$Root, [string[]]$Area = @('All'))
    $groups = [ordered]@{
        Viewer = @('VideoViewerRegressionTest', 'DetailShuffleRotationInstrumentedTest', 'DetailHudInstrumentedTest', 'ExternalMediaOpenInstrumentedTest')
        Playback = @('DetailPlaybackInstrumentedTest', 'PlaybackMemoryInstrumentedTest', 'PlaybackResumeInstrumentedTest', 'VideoMenuInstrumentedTest', 'VideoTimelineInstrumentedTest#playPauseOverridesFingerStillOnTimelineWithoutRestartingOnRelease', 'VideoTimelineInstrumentedTest#backgroundDuringDragKeepsSamePlayerAndLateReleaseCannotUndoPause', 'VideoTimelineInstrumentedTest#dragSeeksBeforeFingerIsReleasedAndRestoresPlaybackState', 'VideoTimelineInstrumentedTest#playbackBarSeeksWithFilmstripClosedAndToggleKeepsBothAvailable')
        Cinema = @('CinemaModeInstrumentedTest', 'AlbumMediaHeaderInstrumentedTest#albumMenuShowsOnlyCinemaModeNameAndPersistsSelection', 'PlaybackResumeInstrumentedTest', 'VideoTimelineInstrumentedTest#playPauseOverridesFingerStillOnTimelineWithoutRestartingOnRelease', 'VideoTimelineInstrumentedTest#backgroundDuringDragKeepsSamePlayerAndLateReleaseCannotUndoPause')
        Timeline = @('VideoTimelineInstrumentedTest', 'PlaybackResumeInstrumentedTest', 'MotionPhotoInstrumentedTest')
        Ocr = @('ImageTextRecognitionInstrumentedTest', 'VideoViewerRegressionTest#queuedManualOcrCannotRevealTextAfterNavigationAndCanBeRetried')
        ImageEditing = @('ImageEditorInstrumentedTest', 'ImageRotationInstrumentedTest', 'ImageMenuInstrumentedTest', 'ImageTextRecognitionInstrumentedTest#documentLongPressAndCustomEditorOfferCopyableText', 'MotionPhotoInstrumentedTest')
        VideoEditing = @('VideoEditInstrumentedTest')
        Editing = @('ImageEditorInstrumentedTest', 'ImageRotationInstrumentedTest', 'ImageMenuInstrumentedTest', 'VideoEditInstrumentedTest', 'ImageTextRecognitionInstrumentedTest#documentLongPressAndCustomEditorOfferCopyableText', 'MotionPhotoInstrumentedTest')
        Albums = @('AlbumGridPinchInstrumentedTest', 'AlbumSelectionInstrumentedTest', 'AlbumFastScrollInstrumentedTest', 'AlbumMediaHeaderInstrumentedTest#sortAndNewFolderOpenAsRightSidePanels', 'AlbumMutationInstrumentedTest', 'VirtualAlbumsInstrumentedTest')
        Catalog = @('AutomaticHiddenAlbumsInstrumentedTest', 'HiddenAlbumDialogInstrumentedTest', 'CatalogMutationStateInstrumentedTest', 'MediaActionsInstrumentedTest', 'VirtualAlbumsInstrumentedTest', 'AlbumCoverInstrumentedTest', 'AlbumMutationInstrumentedTest', 'GalleryDatabaseInstrumentedTest', 'GalleryMigrationInstrumentedTest', 'GalleryUpgradeInstrumentedTest', 'VideoThumbnailFrameInstrumentedTest', 'OptimizationInstrumentedTest')
        Appearance = @('ActionClickFeedbackInstrumentedTest', 'ActivityLoadingFeedbackInstrumentedTest', 'DialogActionAlignmentInstrumentedTest', 'SettingsOverviewInstrumentedTest', 'ThemeCustomizationInstrumentedTest', 'DetailHudInstrumentedTest', 'DetailMetadataInstrumentedTest', 'MainActivitySmokeTest', 'AlbumMediaHeaderInstrumentedTest#albumTitleAndSearchShareTheSameToolbar')
    }
    if (!$Area.Count -or ($Area -contains 'All' -and $Area.Count -ne 1)) { throw 'All não pode ser combinado com outras áreas.' }
    $directory = Join-Path $Root 'app/src/androidTest/kotlin/com/galeria/android'
    $inventory = @{}
    foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.kt') {
        $source = Get-Content -LiteralPath $file.FullName -Raw
        $count = [regex]::Matches($source, '@Test\b').Count
        if ($count) { $inventory[$file.BaseName] = @{ Count = $count; Source = $source } }
    }
    $selected = @()
    if ($Area -contains 'All') { $selected = @($inventory.Keys | Sort-Object) }
    else {
        foreach ($name in $Area) {
            if (!$groups.Contains($name)) { throw "Área desconhecida: $name" }
            $selected += $groups[$name]
        }
        # A whole class subsumes any explicitly selected method from that class.
        $selected = @($selected | Sort-Object -Unique | Where-Object { !($_ -match '#' -and $selected -contains ($_ -split '#')[0]) })
    }
    $expected = 0
    foreach ($entry in $selected) {
        $parts = $entry -split '#'
        if (!$inventory.ContainsKey($parts[0])) { throw "Teste ausente no plano: $entry" }
        if ($parts.Count -eq 2) {
            if ($inventory[$parts[0]].Source -notmatch ('\bfun\s+' + [regex]::Escape($parts[1]) + '\s*\(')) { throw "Método ausente: $entry" }
            $expected++
        } else { $expected += $inventory[$parts[0]].Count }
    }
    if ($expected -le 0) { throw 'Nenhum teste selecionado.' }
    return [pscustomobject]@{ Area = $Area; Classes = @($selected | ForEach-Object { "com.galeria.android.$_" }); Expected = $expected }
}

function ConvertFrom-GalleryInstrumentation {
    param([string[]]$Lines, [int]$ExitCode, [int]$Expected = 0)
    $ok = @($Lines | Where-Object { $_ -match '^OK \((\d+) tests?\)$' })
    $total = if ($ok.Count -eq 1) { [int]([regex]::Match($ok[0], '\d+').Value) } else { 0 }
    $codes = @($Lines | Where-Object { $_ -match '^INSTRUMENTATION_STATUS_CODE: ' } | ForEach-Object { [int]($_ -replace '^INSTRUMENTATION_STATUS_CODE: ', '') })
    $passed = @($codes | Where-Object { $_ -eq 0 }).Count
    $skipped = @($codes | Where-Object { $_ -in @(-3, -4) }).Count
    $failed = @($codes | Where-Object { $_ -notin @(0, 1, -3, -4) }).Count
    $started = @($codes | Where-Object { $_ -eq 1 }).Count
    $reason = if ($ExitCode -ne 0) { "adb terminou com código $ExitCode" }
        elseif ($Lines -notcontains 'INSTRUMENTATION_CODE: -1') { 'Instrumentação incompleta ou abortada' }
        elseif ($skipped) { "$skipped testes ignorados/assumptions não cumpridas" }
        elseif ($failed) { "$failed testes com falha/erro" }
        elseif ($total -le 0) { 'Resumo ausente, duplicado ou execução vazia' }
        elseif ($passed -ne $total -or $started -ne $total) { 'Resumo não corresponde aos testes iniciados/concluídos' }
        elseif ($Expected -gt 0 -and $total -ne $Expected) { "Esperados $Expected testes; executados $total" }
        else { '' }
    return [pscustomobject]@{ Success = !$reason; Total = $total; Passed = $passed; Failed = $failed; Skipped = $skipped; Reason = $reason }
}

function Get-GalleryUnitResult {
    param([string]$Directory, [int]$Minimum = 1)
    $files = @(Get-ChildItem -LiteralPath $Directory -Filter 'TEST-*.xml' -ErrorAction Stop)
    $total = 0; $failed = 0; $skipped = 0
    foreach ($file in $files) {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        $total += [int]$xml.testsuite.tests
        $failed += [int]$xml.testsuite.failures + [int]$xml.testsuite.errors
        $skipped += [int]$xml.testsuite.skipped
    }
    if ($total -lt $Minimum -or $failed -or $skipped) { throw "Unitários: $total testes, $failed falhas/erros, $skipped ignorados; mínimo $Minimum." }
    return [pscustomobject]@{ Total = $total; Failed = $failed; Skipped = $skipped }
}

function Get-GalleryLintResult {
    param([string]$Path)
    [xml]$xml = Get-Content -LiteralPath $Path -Raw
    if ($xml.DocumentElement.Name -ne 'issues') { throw 'Relatório lint ausente/inválido.' }
    $issues = @($xml.SelectNodes('/issues/issue'))
    $errors = @($issues | Where-Object { $_.severity -in @('Error', 'Fatal') }).Count
    if ($errors) { throw "Lint contém $errors erros." }
    return [pscustomobject]@{ Errors = $errors; Warnings = @($issues | Where-Object { $_.severity -eq 'Warning' }).Count }
}

function Get-GalleryInputFingerprint {
    param([string]$Root)
    # Never enumerate build outputs, the Git directory, local.properties or keys.
    $directories = @('app/src', 'app/schemas', 'app/libs', 'benchmark/src', 'scripts', 'gradle', '.github', 'buildSrc/src', 'build-logic/src')
    $files = @()
    foreach ($directory in $directories) {
        $path = Join-Path $Root $directory
        if (Test-Path -LiteralPath $path) { $files += @(Get-ChildItem -LiteralPath $path -File -Recurse) }
    }
    foreach ($directory in @('', 'app', 'benchmark', 'buildSrc', 'build-logic')) {
        $path = if ($directory) { Join-Path $Root $directory } else { $Root }
        if (Test-Path -LiteralPath $path) {
            $files += @(Get-ChildItem -LiteralPath $path -File | Where-Object { $_.Name -match '(\.gradle(\.kts)?$|^gradle\.properties$|^gradlew(\.bat)?$|proguard.*\.pro$)' })
        }
    }
    $lines = @($files | Sort-Object FullName -Unique | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($Root, $_.FullName).Replace('\', '/')
        "$relative $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
    })
    if (!$lines.Count) { throw 'Nenhuma entrada de build encontrada.' }
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes(($lines -join "`n"))))).Replace('-', '') }
    finally { $hash.Dispose() }
}

function Assert-GalleryDelivery {
    param([string]$Root, [string]$Receipt)
    $Root = [IO.Path]::GetFullPath($Root)
    $record = Get-Content -LiteralPath $Receipt -Raw | ConvertFrom-Json
    if ($record.schema -ne 1 -or $record.mode -ne 'delivery' -or $record.status -ne 'passed') { throw 'Não há aprovação completa de entrega.' }
    $latest = Get-Content -LiteralPath (Join-Path $Root 'app/build/reports/validation/delivery-attempt.json') -Raw | ConvertFrom-Json
    if ($latest.status -ne 'passed' -or $latest.runId -ne $record.runId) { throw 'A última tentativa de entrega não foi aprovada ou substituiu esta evidência.' }
    $failurePath = Join-Path $Root 'app/build/reports/validation/last-failure.json'
    if (Test-Path -LiteralPath $failurePath) {
        $failure = Get-Content -LiteralPath $failurePath -Raw | ConvertFrom-Json
        if ([DateTime]$failure.failedUtc -gt [DateTime]$record.completedUtc) { throw 'Houve falha depois da aprovação; investigue e execute -Delivery novamente.' }
    }
    if ($record.fingerprint -ne (Get-GalleryInputFingerprint $Root)) { throw 'Fontes/configuração/testes mudaram após a validação. Execute -Delivery novamente.' }
    if (@($record.evidence).Count -lt 5) { throw 'Evidências insuficientes.' }
    $verified = @{}
    foreach ($evidence in $record.evidence) {
        $path = [IO.Path]::GetFullPath((Join-Path $Root $evidence.path))
        if (!$path.StartsWith($Root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidência fora do projeto.' }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $evidence.hash) { throw "Evidência alterada: $($evidence.path)" }
        $verified[$evidence.path] = $path
    }
    foreach ($path in @($record.nativeReport, $record.lintReport, 'app/build/outputs/apk/debug/app-debug.apk', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')) {
        if (!$verified.ContainsKey($path)) { throw "Evidência obrigatória ausente: $path" }
    }
    $units = @($record.unitReports)
    if (!$units.Count) { throw 'Relatórios unitários ausentes.' }
    $total = 0
    foreach ($path in $units) {
        if (!$verified.ContainsKey($path)) { throw 'Relatório unitário sem verificação de integridade.' }
        [xml]$xml = Get-Content -LiteralPath $verified[$path] -Raw
        if ([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped) { throw 'Falha/ignore no relatório unitário.' }
        $total += [int]$xml.testsuite.tests
    }
    # Three former instrumented rules now run on the JVM: 222 + 3 / 92 - 3.
    if ($total -lt 225) { throw 'Cobertura unitária abaixo da base de 225 testes.' }
    $plan = Get-GalleryTestPlan $Root @('All')
    $native = ConvertFrom-GalleryInstrumentation (Get-Content -LiteralPath $verified[$record.nativeReport]) 0 $plan.Expected
    if (!$native.Success -or $native.Total -lt 89) { throw "Regressão completa não aprovada: $($native.Reason)" }
    $lint = Get-GalleryLintResult $verified[$record.lintReport]
    return [pscustomobject]@{ UnitTests = $total; NativeTests = $native.Total; Warnings = $lint.Warnings; Fingerprint = $record.fingerprint }
}

function Register-GalleryValidationFailure {
    param([string]$Root, [string]$Reason)
    $directory = Join-Path $Root 'app/build/reports/validation'
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    @{ failedUtc = [DateTime]::UtcNow.ToString('o'); reason = $Reason } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $directory 'last-failure.json') -Encoding utf8
}
