#requires -Version 7.0
param([string]$Receipt)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'validation-common.ps1')
$root = Split-Path -Parent $PSScriptRoot
if (!$Receipt) { $Receipt = Join-Path $root 'app/build/reports/validation/delivery.json' }
$result = Assert-GalleryDelivery $root $Receipt
Write-Output "Pré-validação aprovada: $($result.UnitTests) unitários, $($result.NativeTests) instrumentados, lint sem erros ($($result.Warnings) avisos)."
Write-Output 'Isso não valida assinatura/APK release nem publica arquivos. Preserve a confirmação manual dos codecs/aparelhos reais.'
