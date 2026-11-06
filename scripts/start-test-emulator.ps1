#requires -Version 7.0
param(
    [ValidateSet('Galeria', 'Downloader')][string]$Project = 'Galeria',
    [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android/Sdk'),
    [ValidateRange(30, 600)][int]$WaitSeconds = 180,
    [switch]$StatusOnly
)
$ErrorActionPreference = 'Stop'
$devices = @{
    Galeria = @{ Name = 'Galeria_Codex_Test_36'; Port = 5554 }
    Downloader = @{ Name = 'Definitive_API_36'; Port = 5560 }
}
$selected = $devices[$Project]
$avdName = $selected.Name
$serial = 'emulator-' + $selected.Port
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$emulator = Join-Path $SdkRoot 'emulator/emulator.exe'
foreach ($tool in @($adb, $emulator)) {
    if (!(Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Ferramenta Android ausente: $tool" }
}
function Get-EmulatorState {
    $lines = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao consultar dispositivos Android.' }
    $target = $null
    foreach ($line in $lines) {
        if ($line -notmatch '^(emulator-\d+)\s+(\S+)') { continue }
        $deviceId = $Matches[1]; $state = $Matches[2]
        if ($deviceId -eq $serial -and $state -ne 'device') {
            # A freshly launched emulator can be offline while Android boots.
            # Treat its port as occupied, never launch a second instance.
            $target = $false
            continue
        }
        if ($state -ne 'device') { continue }
        $names = @(& $adb -s $deviceId emu avd name)
        if ($LASTEXITCODE -ne 0) { throw "Não foi possível identificar $deviceId." }
        $name = [string]($names | Where-Object { $_ -and $_ -ne 'OK' } | Select-Object -First 1)
        $name = $name.Trim()
        if (!$name) { throw "Nome do AVD indisponível em $deviceId." }
        if ($name -eq $avdName -and $deviceId -ne $serial) {
            throw "$avdName já está em $deviceId. Usar essa instância ou encerrá-la antes de usar a porta padrão; não será criada uma cópia."
        }
        if ($deviceId -eq $serial) {
            if ($name -ne $avdName) { throw "$serial pertence a $name; nenhum dispositivo foi encerrado ou substituído." }
            $boot = @(& $adb -s $serial shell getprop sys.boot_completed)
            $target = $LASTEXITCODE -eq 0 -and ($boot -join '').Trim() -eq '1'
        }
    }
    return @{ Connected = ($null -ne $target); Booted = ($target -eq $true) }
}
function Get-AvdProcess {
    @(Get-CimInstance Win32_Process -Filter "Name='emulator.exe' OR Name='qemu-system-x86_64.exe'" |
        Where-Object { $_.CommandLine -match ('(?:-avd\s+|@)"?' + [regex]::Escape($avdName) + '(?:"|\s|$)') })
}
if (!$IsWindows) { throw 'Este iniciador usa as ferramentas Android locais do Windows.' }
$mutex = [Threading.Mutex]::new($false, 'Local\AndroidTestAvd-' + $avdName)
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Outra chamada já está preparando este AVD; aguarde e reutilize a mesma instância.' }
    $state = Get-EmulatorState
    if ($StatusOnly) {
        [pscustomobject]@{ Project = $Project; Avd = $avdName; Device = $serial; Connected = $state.Connected; Booted = $state.Booted }
        return
    }
    $running = @(Get-AvdProcess)
    if (!$state.Connected -and $running.Count -eq 0) {
        $available = @(& $emulator -list-avds)
        if ($LASTEXITCODE -ne 0 -or $available -notcontains $avdName) {
            throw "AVD padrão ausente: $avdName. Restaurar esse nome pelo Device Manager; este iniciador não cria variantes por versão ou rodada."
        }
        # Only this stopped, named test device is eligible for snapshot cleanup.
        # Its userdata, installed apps, accounts and media are never reset.
        $avdHome = if ($env:ANDROID_AVD_HOME) { $env:ANDROID_AVD_HOME } elseif ($env:ANDROID_USER_HOME) { Join-Path $env:ANDROID_USER_HOME 'avd' } else { Join-Path $env:USERPROFILE '.android/avd' }
        $avdHome = [IO.Path]::GetFullPath($avdHome)
        $ini = Join-Path $avdHome ($avdName + '.ini')
        $pathLine = Get-Content -LiteralPath $ini | Where-Object { $_ -match '^path=' } | Select-Object -First 1
        if (!$pathLine) { throw "Caminho do AVD ausente em $ini." }
        $avdFolder = [IO.Path]::GetFullPath($pathLine.Substring(5))
        $expected = Join-Path $avdHome ($avdName + '.avd')
        if ($avdFolder -ne $expected) { throw "Local do AVD personalizado: $avdFolder. Revisar manualmente antes de limpar snapshots." }
        if ((Get-Item -LiteralPath $avdFolder -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Pasta do AVD é um link; limpeza recusada.' }
        $snapshots = [IO.Path]::GetFullPath((Join-Path $avdFolder 'snapshots'))
        if (!$snapshots.StartsWith($avdFolder + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Caminho de snapshots fora do AVD.' }
        if (Test-Path -LiteralPath $snapshots) {
            $entries = @((Get-Item -LiteralPath $snapshots -Force)) + @(Get-ChildItem -LiteralPath $snapshots -Recurse -Force)
            if ($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Link em snapshots; limpeza recusada.' }
            if (@(Get-AvdProcess).Count) { throw 'AVD iniciou durante a preparação; limpeza cancelada.' }
            Remove-Item -LiteralPath $snapshots -Recurse -Force
            Write-Host "Snapshots antigos removidos de $avdName."
        }
        $config = Join-Path $avdFolder 'config.ini'
        $configText = Get-Content -LiteralPath $config -Raw
        foreach ($setting in @('fastboot.forceColdBoot=yes', 'fastboot.forceFastBoot=no', 'firstboot.bootFromDownloadableSnapshot=no', 'firstboot.bootFromLocalSnapshot=no', 'firstboot.saveToLocalSnapshot=no')) {
            $key = ($setting -split '=', 2)[0]
            $pattern = '(?m)^' + [regex]::Escape($key) + '=[^\r\n]*'
            if ($configText -match $pattern) { $configText = [regex]::Replace($configText, $pattern, $setting) } else { $configText = $configText.TrimEnd() + "`r`n" + $setting + "`r`n" }
        }
        [IO.File]::WriteAllText($config, $configText, [Text.UTF8Encoding]::new($false))
        $logs = Join-Path $env:LOCALAPPDATA ('Android/TestDevices/' + $Project)
        New-Item -ItemType Directory -Path $logs -Force | Out-Null
        Start-Process -FilePath $emulator -ArgumentList @('-avd', $avdName, '-port', $selected.Port, '-no-window', '-no-audio', '-no-snapshot', '-gpu', 'swiftshader') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'emulator.log') -RedirectStandardError (Join-Path $logs 'emulator-error.log') | Out-Null
        Write-Host "Iniciando $avdName sem carregar ou salvar snapshots..."
    } else {
        Write-Host "Reutilizando $avdName; dados e aplicativos preservados."
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (!$state.Booted) {
        if ($timer.Elapsed.TotalSeconds -ge $WaitSeconds) { throw "Tempo esgotado para $serial. A instância foi mantida para diagnóstico; nenhuma cópia foi criada." }
        Start-Sleep -Seconds 2
        $state = Get-EmulatorState
    }
    [pscustomobject]@{ Project = $Project; Avd = $avdName; Device = $serial; Connected = $true; Booted = $true }
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
