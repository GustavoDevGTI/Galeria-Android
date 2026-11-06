# Validação diária, investigação e release

Esta automação não publica releases, não altera permissões do GitHub e não usa chaves de assinatura. Na porta padrão, os testes de interface reutilizam ou iniciam o AVD dedicado por `scripts/start-test-emulator.ps1`, sem snapshots e sem criar cópias. Dispositivos selecionados explicitamente continuam sendo usados como informados. O runner não apaga AVDs nem dados; consulte [a política dos emuladores](EMULADORES-TESTE.md). Prefira o AVD dedicado `Galeria_Codex_Test_36`: os testes usam fixtures próprias e podem modificar permissões/preferências de teste, restaurando-as conforme seus respectivos casos.

Antes de instalar os APKs e iniciar instrumentação, o runner guarda o dump da janela ativa e rejeita uma janela ANR (“não está respondendo”), seja do SystemUI ou de outro app. Isso evita desperdiçar a bateria inteira com entrada interceptada. A verificação é somente leitura; não fecha diálogos nem recupera/limpa o dispositivo automaticamente. Se um snapshot restaurado estiver comprometido, reinicie o AVD dedicado sem carregar esse snapshot e verifique sua saúde antes de repetir. Um ANR que surja depois do início continua sujeito às verificações e bloqueios da suíte, não é ignorado.

## Verificação do APK otimizado antes da release

### OCR no APK otimizado

Testes debug aprovados não comprovam que o OCR inicializa na release. A partir
da correção de 16/10/2026, `:app:assembleRelease` também executa
`:app:verifyReleaseOcrComponents`: verifica no resultado real do R8 que os três
registradores ML Kit e seus construtores sem argumentos foram preservados.
Uma ausência interrompe a montagem. Não desabilite a otimização para aprovar
essa checagem. A tarefa exige a configuração normal de assinatura release;
nenhuma credencial é mantida nos fontes.

Essa verificação estrutural não substitui o smoke de execução antes de publicar:

1. Instale o APK release recém-gerado no emulador dedicado, sem limpar dados.
   Se a assinatura for incompatível com o APK debug instalado, use uma cópia
   diagnóstica assinada para testes, mantendo DEX/assets/libs idênticos ao original.
   Nunca distribua essa cópia nem desinstale automaticamente a instalação existente.
2. Abra uma imagem real com texto legível; teste o toque prolongado e o submenu.
3. Confirme o ícone de carregamento contrastante, a seleção na própria foto,
   seleção individual/total e a ação Copiar. A foto deve continuar intacta.
4. Reabra o app para repetir a inicialização a frio e confira o log: não pode haver
   `Invalid component registrar`, `NoSuchMethodException` nem erro `GalleryOCR`.
5. Registre o hash do APK original, os logs e a captura da seleção em
   `app/build/reports/`. Não versione imagens particulares usadas no diagnóstico.

O smoke deve usar código otimizado; reinstalar o APK debug não valida a release.
O runner separado `OcrReleaseSmokeRunner` usa apenas APIs Android e a interface
pública, sem Kotlin/AndroidX nem nomes internos que o R8 modifica. Execute com
uma cópia release de assinatura compatível:

```powershell
./scripts/run-release-ocr-smoke.ps1 -Apk ./copia-release-para-testes.apk
./scripts/run-release-ocr-smoke.ps1 -Apk ./copia-release-para-testes.apk -Background Dark
```

Execute nos fundos claro (padrão) e preto (`-Background Dark`). Ele cria uma imagem própria, reconhece pelo toque prolongado com pequeno arraste, seleciona e copia
o texto, confere o clipboard e verifica que o arquivo não mudou. O script recusa
APK debuggable e dispositivos físicos, compila o runner com
`-PocrReleaseSmoke=true`, instala sem limpar dados e registra hashes/logs/resultado.
Ele não troca assinaturas nem publica. Preserve também o hash do APK original
e a conferência de DEX/assets/libs da cópia de teste. Antes de retomar a bateria
AndroidX, compile `:app:assembleDebugAndroidTest` sem essa propriedade para
restaurar o runner normal; `run-validation.ps1` já faz isso automaticamente.

A regressão de OCR também abre fotos de um álbum real de teste, incluindo a
troca de URI entre as coleções Images e Files do MediaStore após o carregamento.
Ela confere o gesto em fundos branco e preto, zoom, indicador de análise, cópia,
preservação do original e ausência de navegação indevida durante o toque mantido.

## Fluxo diário — PowerShell 7

O comando padrão **não executa mais toda a suíte Android**. Em cada mudança do aplicativo, rode a base essencial mais os casos focados no que foi alterado. Mantenha a bateria extensa para uma suspeita concreta na feature ou antes de uma release no GitHub. Finalizar uma mudança local ou fazer um push comum não exige regressão completa.

A base diária mantém todos os testes unitários (250 na versão atual), pois são rápidos e dispensam emulador, os contratos das ferramentas, lint/build debug e estas **quatro integrações**:

| Caso essencial | Proteção |
| --- | --- |
| `MainActivitySmokeTest#mainScreenOpensWithSearchAvailable` | Abertura da galeria e pesquisa |
| `GalleryBrowseBarInstrumentedTest#mainBarHasFourEqualActionsAndOverflowKeepsTheRemainingFeatures` | Navegação principal e menu |
| `OrganizerInstrumentedTest#pushIsOnlyAPlanUntilSaveAndUndoDoesNotTouchTheFile` | Marcação, desfazer e salvamento de um arquivo real |
| `DetailPlaybackInstrumentedTest#quickTapsChangeThePlayerAndHoldingOpensTheExistingSelectorWithoutCycling` | Player real e controles de reprodução |

```powershell
# Base diária: unitários + lint/build + 4 integrações essenciais.
./scripts/run-validation.ps1

# Base + pacote curto da área afetada.
./scripts/run-validation.ps1 -Area Albums
./scripts/run-validation.ps1 -Area Viewer,Playback

# Base + o caso exato alterado (classe inteira também é aceita).
./scripts/run-validation.ps1 -Test 'OrganizerInstrumentedTest#photoPreviewRemainsStraightAndClosesWithoutChangingTheQueue'

# Combine pacote curto e verificações adicionais da mudança.
./scripts/run-validation.ps1 -Area Albums -Test 'OrganizerInstrumentedTest#trashIsRecoverableWhenEnabledAndPermanentOnlyAfterSavingWhenDisabled'

# Liste o plano e a contagem sem build, instalação ou instrumentação.
./scripts/run-validation.ps1 -Area Albums -List

# Iteração intermediária somente na lógica, sem emulador (não é a base completa).
./scripts/run-validation.ps1 -UnitOnly
```

Os pacotes curtos são pontos de partida: acrescente o teste do comportamento efetivamente alterado. As áreas podem ser combinadas; uma classe completa absorve seus métodos individuais e as duplicatas são removidas. `-Test` aceita `Classe`, `Classe#metodo` e o prefixo `com.galeria.android.`. Nomes inválidos ou inexistentes interrompem a execução; a quantidade esperada é calculada a partir dos fontes.

| Área | Diário: base + foco | Extenso: somente a área |
| --- | ---: | ---: |
| Essential | 4 | 4 |
| Viewer | 8 | 13 |
| Playback | 5 | 10 |
| Cinema | 6 | 10 |
| Timeline | 6 | 12 |
| Ocr | 6 | 10 |
| ImageEditing | 6 | 11 |
| VideoEditing | 6 | 3 |
| Editing | 8 | 14 |
| Albums | 8 | 47 |
| Catalog | 7 | 47 |
| Appearance | 7 | 27 |

Contagens atuais, sujeitas a novos testes; `-List` é a referência executável. Todos os unitários continuam rodando nos dois perfis. Os totais diários incluem a base; os extensos incluem apenas a feature investigada e por isso podem ser menores em áreas pequenas.

## Investigação de bug

```powershell
# Suspeita concreta no OCR: todas as integrações dessa feature.
./scripts/run-validation.ps1 -Extended -Area Ocr

# Suspeita transversal: suíte completa explicitamente solicitada.
./scripts/run-validation.ps1 -Extended -Area All

# Diagnóstico isolado com APKs já compilados.
./scripts/run-android-tests.ps1 -Class 'com.galeria.android.DetailHudInstrumentedTest' -ExpectedTests 1
```

`-Area` sem `-Extended` usa o pacote curto mais a base; com `-Extended`, usa a bateria completa da área. O comando `-Area All` sozinho é recusado para evitar regressão completa acidental. `-Extended` exige a indicação da área ou dos testes sob suspeita. Após uma falha, investigue e repita o foco pertinente; uma nova execução de toda a suíte não é obrigatória para cada correção local.

Para mudanças somente na documentação não é necessário executar Android. Para mudanças nestes scripts, rode `scripts/test-validation-tools.ps1` e confira os planos com `-List`. Isso testa seleção, deduplicação, recusas, parser e integridade dos recibos sem iniciar o emulador. Não considere contratos sintéticos como testes do aplicativo.

Gradle reaproveita tarefas atualizadas. Logs completos ficam nos relatórios; o terminal mostra o resumo e os erros relevantes. Não há retry automático. Se o aparelho estiver indisponível, registre o que não rodou. Nenhum teste extenso foi apagado: permanece na investigação e na validação completa de release.

## Antes de uma publicação autorizada

Finalize código, testes, configuração e versão **antes** de validar:

```powershell
./scripts/run-validation.ps1 -Delivery
./scripts/assert-delivery-validation.ps1
```

`-Delivery` seleciona automaticamente todas as áreas, mesmo com o novo padrão diário. Não aceita área parcial, `-Test`, `-Extended` nem `-UnitOnly`. Compila debug e APK de testes, executa todos os unitários, lint e todos os instrumentados. Instala os APKs dessa própria execução: não aceita `-SkipInstall` como fonte de aprovação. A checagem final confirma os fontes/configurações/scripts e os hashes dos APKs/relatórios; só então grava `app/build/reports/validation/delivery.json`.

Depois disso, continue o fluxo de release assinado existente **somente quando solicitado**, verificando assinatura, versão, certificado e integridade do APK. A evidência automatizada é da build **debug**: não significa que um APK release foi compilado, instalado, testado ou aprovado. Codecs, dual áudio/legendas reais, Motion Photos de fabricantes e comportamento de aparelhos continuam exigindo confirmação manual complementar.

O portão é uma checagem local do fluxo de entrega, não uma proteção inviolável do repositório: não impede alguém de executar `gh release` manualmente nem configura branch protection. Nenhuma publicação automática foi criada.

## O que bloqueia uma aprovação

- Falha de compilação, unitário, lint, `adb` ou instrumentação.
- Teste ignorado, assumption não cumprida, zero testes ou resumo incompleto/inconsistente.
- Menos de 225 unitários ou 89 instrumentados na validação completa. Os pisos foram atualizados exclusivamente pela migração autorizada dos três casos para a JVM. Remover/migrar outros testes exige revisão da cobertura e atualização consciente desses pisos, não afrouxamento para conseguir aprovação.
- Diferença entre a quantidade selecionada e a quantidade executada.
- Alteração dos fontes/entradas durante ou depois da execução.
- APK/relatório ausente ou diferente do que foi aprovado.
- Tentativa completa posterior falhada, interrompida ou ainda em andamento.
- Falha registrada pelo runner ou por `run-validation` depois da aprovação, inclusive em testes por área. Investigue; uma repetição direcionada verde não reabilita a entrega. É necessária uma nova validação completa.

Resultados de comandos externos a esses scripts não são automaticamente observados. Quando outra verificação descobrir uma falha, trate-a como bloqueadora e investigue antes de repetir `-Delivery`; um selo não substitui esse julgamento.

A impressão digital inclui `app/src`, schemas, bibliotecas locais, fontes de benchmark, scripts, Gradle/wrapper/configurações e workflows. Não inclui `app/build`, relatórios, Git, README/documentação, `local.properties` ou segredos. Não assina evidências criptograficamente: hashes servem para detectar resultados desatualizados/acidentalmente modificados. Alterações na estrutura de build também exigem revisar as entradas cobertas pelo fingerprint.

## Evidências e CI

Cada execução guarda seus logs, XMLs e `result.json` em `app/build/reports/validation/<data-id>/`. Falhas mantêm `failure.json`, sem apagar relatórios anteriores. `delivery-attempt.json` distingue aprovação, execução pendente e falha; `last-failure.json` bloqueia uma aprovação anterior. Esses arquivos são outputs locais ignorados pelo Git, sem credenciais.

O workflow Android CI mantém Java 17, cache Gradle, pushes/PRs em `main` e artefatos temporários por sete dias; permite disparo manual. Executa também os contratos das ferramentas, confere relatórios unitários não vazios/sem skips e compila/publica como artefato o APK de testes. **O CI não roda o emulador nesta configuração** e, portanto, não produz aprovação de entrega. A suíte completa local é obrigatória antes de uma release no GitHub; no desenvolvimento, use base diária mais foco.

A seleção por classe/método usa a CLI do [AndroidJUnitRunner](https://developer.android.com/studio/test/command-line). O parser diferencia sucesso, erro, ignore e assumption pelos códigos do [runner AndroidX](https://github.com/android/android-test/blob/main/runner/android_junit_runner/java/androidx/test/internal/runner/listener/InstrumentationResultPrinter.java). O disparo manual segue a [sintaxe oficial de workflows GitHub](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).

### Particularidade deste Windows

Se ocorrer o erro local de socket Java registrado no plano de isolamento, mantenha o ajuste **somente no processo de build**, como usado na validação:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\Public'
./scripts/run-validation.ps1 -Delivery -GradleOptions @(
    '--no-daemon', '--max-workers=2', '--console=plain',
    '-Dorg.gradle.jvmargs=-Xms64m -Xmx1536m -Dfile.encoding=UTF-8',
    '-Dorg.gradle.internal.instrumentation.agent=false'
)
```

Não foi alterada configuração global nem o mecanismo de testes padrão para contornar esse problema. A cópia local de build usada para evitar I/O de rede só pode compartilhar uma evidência com o repositório se **todos** os inputs e outputs referenciados forem idênticos; a checagem rejeita diferenças.
