# Emulador de testes reutilizável — 06/11/2026

AVD padrão da Galeria: `Galeria_Codex_Test_36`, porta 5554 (`emulator-5554`). O antigo `Galeria_API_36` foi removido. Não criar AVDs por versão, data ou rodada; reutilizar o padrão e manter o isolamento em relação ao Downloader.

`./scripts/start-test-emulator.ps1` reutiliza a instância existente ou inicia o mesmo AVD sem carregar/salvar snapshots. Não cria variantes, não usa `-wipe-data` e conserva dados e aplicativos. Falha sem encerrar outros dispositivos se a porta estiver ocupada. A ausência do AVD exige restaurar o nome padrão pelo Device Manager.

`run-android-tests.ps1` prepara automaticamente esse AVD ao usar a porta padrão. Assim, `run-validation.ps1` usa a instância reutilizável na etapa instrumentada. `-List` e `-UnitOnly` não iniciam emuladores. Dispositivos explicitamente selecionados são preservados. O iniciador remove somente snapshots antigos do próprio AVD, antes de abrir uma instância parada; não faz limpeza de dados ou exclusão automática de outros AVDs.

A configuração atual desativa Quick Boot e snapshots automáticos. A limpeza removeu os snapshots anteriores da Galeria. Logs da última abertura ficam em `%LOCALAPPDATA%\Android\TestDevices\Galeria`.

Foram verificados o estado do AVD parado, a sintaxe e os contratos/planos das ferramentas. Não foi iniciado o emulador da Galeria nem executada a suíte Android nesta manutenção de ferramentas.
