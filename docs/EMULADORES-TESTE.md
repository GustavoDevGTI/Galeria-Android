# Emulador de testes reutilizável — 06/11/2026

AVD padrão da Galeria: `Galeria_Codex_Test_36`, porta 5554 (`emulator-5554`). O antigo `Galeria_API_36` foi removido. Não criar AVDs por versão, data ou rodada; reutilizar o padrão e manter o isolamento em relação ao Downloader.

`./scripts/start-test-emulator.ps1` reutiliza a instância existente ou inicia o mesmo AVD sem carregar/salvar snapshots. Não cria variantes, não usa `-wipe-data` e conserva dados e aplicativos. Falha sem encerrar outros dispositivos se a porta estiver ocupada. A ausência do AVD exige restaurar o nome padrão pelo Device Manager.

`run-android-tests.ps1` prepara automaticamente esse AVD ao usar a porta padrão. Assim, `run-validation.ps1` usa a instância reutilizável na etapa instrumentada. `-List` e `-UnitOnly` não iniciam emuladores. Dispositivos explicitamente selecionados são preservados. O iniciador remove somente snapshots antigos do próprio AVD, antes de abrir uma instância parada; não faz limpeza de dados ou exclusão automática de outros AVDs.

A configuração atual desativa Quick Boot e snapshots automáticos. A limpeza removeu os snapshots anteriores da Galeria. Logs da última abertura ficam em `%LOCALAPPDATA%\Android\TestDevices\Galeria`.

Foram verificados o estado do AVD parado, a sintaxe e os contratos/planos das ferramentas. Não foi iniciado o emulador da Galeria nem executada a suíte Android nesta manutenção de ferramentas.

## Armazenamento local dos AVDs — correção posterior em 06/11/2026

A limpeza de snapshots deixou margem insuficiente na cota corporativa: os arquivos ram.img podem voltar a ser criados mesmo com -no-snapshot. Por isso, os dados dos dois AVDs foram movidos com o avdmanager instalado para `%LOCALAPPDATA%\Android\avd`, área excluída da cota de perfil. A política corporativa não foi alterada.

`ANDROID_AVD_HOME` do usuário aponta para esse local. O iniciador também seleciona explicitamente esse diretório quando encontra o registro do AVD, inclusive quando chamado por aplicativos abertos antes da mudança da variável. Os arquivos .ini em `%USERPROFILE%\.android\avd` permanecem como registros de compatibilidade e apontam para o novo local; não criar cópias dos discos nesse diretório antigo.

Preservar nomes e portas: `Galeria_Codex_Test_36` / 5554 e `Definitive_API_36` / 5560. Reutilizar os dispositivos existentes. Não usar -wipe-data nem criar versões por rodada para resolver falhas.

Verificações: hashes SHA-256 dos discos userdata e sdcard iguais antes/depois da movimentação; backing files QCOW2 resolvidos no novo diretório e sem corrupção indicada pelo qemu-img; ambos os AVDs iniciaram e retornaram sys.boot_completed=1. Os 54 contratos das ferramentas da Galeria passaram, e -List -UnitOnly não abriu outro dispositivo. Não foi executada a suíte funcional dos aplicativos nesta manutenção de armazenamento.

## Caches dos testes fora da cota — 06/11/2026

- AVDs e snapshots: `%LOCALAPPDATA%\Android\avd`.
- Logs dos iniciadores: `%LOCALAPPDATA%\Android\TestDevices`.
- Cache, distribuições e logs globais do Gradle: `%LOCALAPPDATA%\Gradle`, por `GRADLE_USER_HOME`. Os runners Windows definem a variável explicitamente para também atender aplicativos abertos antes dessa configuração.
- O antigo `%USERPROFILE%\.gradle` é somente uma junção de compatibilidade para a área local; não armazena outra cópia do cache.
- As áreas de compilação do Downloader (`DdbAndroid`, `Ddb` e `DefinitiveDownloaderDev`) já estão em `%LOCALAPPDATA%`; os relatórios da Galeria permanecem no projeto na rede Y:, também fora do perfil local.
- Não recriar discos AVD nem caches físicos em `%USERPROFILE%`. Não alterar a política de cota do domínio.
- A pasta `.dd-android-sockets` conserva apenas sockets transitórios de tamanho zero, necessários para contornar a falha de Java/AF_UNIX na área virtualizada do aplicativo. Não mover esses sockets para AppData sem validar a conexão loopback.
## Memória do AVD da Galeria — 12/11/2026

O processo do emulador acumula memória quando fica aberto por muitas horas; reiniciar só o Android (`adb reboot`) não a devolve. Antes de uma bateria `-Delivery`, prefira encerrar a instância (`adb -s emulator-5554 emu kill`) e abri-la de novo por `scripts/start-test-emulator.ps1`. Neste PC de 16 GB, não rode a bateria junto com o emulador do outro projeto.

Não troque a tela nativa do AVD para 720x1600 no `config.ini`: o recorte de câmera do perfil Pixel 6 mantém o tamanho antigo, a barra de status fica mais alta que a calculada pelo app e os testes de menu falham. A tela continua em 1080x2400 com a redução `wm size 720x1600` e `wm density 280` já gravada no aparelho.
