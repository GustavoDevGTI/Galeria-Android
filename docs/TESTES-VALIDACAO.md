# Validação por área e preparação de entrega

Esta automação não publica releases, não altera permissões do GitHub e não usa chaves de assinatura. Os testes de interface são executados no dispositivo já aberto/conectado; o script não inicia nem apaga o emulador. Prefira o AVD dedicado `Galeria_Codex_Test_36`: os testes usam fixtures próprias e podem modificar permissões/preferências de teste, restaurando-as conforme seus respectivos casos.

Antes de instalar os APKs e iniciar instrumentação, o runner guarda o dump da janela ativa e rejeita uma janela ANR (“não está respondendo”), seja do SystemUI ou de outro app. Isso evita desperdiçar a bateria inteira com entrada interceptada. A verificação é somente leitura; não fecha diálogos nem recupera/limpa o dispositivo automaticamente. Se um snapshot restaurado estiver comprometido, reinicie o AVD dedicado sem carregar esse snapshot e verifique sua saúde antes de repetir. Um ANR que surja depois do início continua sujeito às verificações e bloqueios da suíte, não é ignorado.

## Uso diário — PowerShell 7

Execute na raiz do projeto, com Java 17, SDK Android e `adb` no PATH:

```powershell
# Regras de todos os controladores, sem emulador ou APK.
pwsh -NoProfile -File ./scripts/run-validation.ps1 -UnitOnly

# Lógica completa + lint/build + somente a interface e integrações de OCR.
pwsh -NoProfile -File ./scripts/run-validation.ps1 -Area Ocr

# Combine áreas quando uma alteração atravessar mais de um contrato.
./scripts/run-validation.ps1 -Area Viewer,Ocr

# Editor de fotos ou corte de vídeos, sem executar o outro editor.
./scripts/run-validation.ps1 -Area ImageEditing
./scripts/run-validation.ps1 -Area VideoEditing

# Diagnóstico bruto continua disponível; falha também bloqueia aprovação antiga.
./scripts/run-android-tests.ps1 -Class 'com.galeria.android.CinemaModeInstrumentedTest' -Repeat 3
```

O caminho por área executa todos os unitários, pois as regras são rápidas e não precisam do emulador. Só reduz os instrumentados. Gradle reaproveita tarefas atualizadas; logs extensos ficam nos relatórios, enquanto o terminal mostra os resultados e erros relevantes. Não há retry automático para transformar uma falha em aprovação.

| Área | Interface e integrações selecionadas | Casos na base atual |
| --- | --- | --- |
| Viewer | Navegação/zoom/OCR atrasado, aleatório/rotação, HUD e abertura externa | 10 |
| Playback | Play/pause, memória, retorno, menu e quatro integrações de timeline | 9 |
| Cinema | Cinema/trilhas/barras, opção do álbum, retorno e duas integrações de timeline | 10 |
| Timeline | Scrubbing/barra/cache, retorno e Motion Photos | 9 |
| Ocr | Detecção/cópia/editor e pedido atrasado após troca de mídia | 6 |
| ImageEditing | Cortar/girar/editor, menu, OCR no editor e Motion Photos | 10 |
| VideoEditing | Corte de vídeo, ações e preservação das trilhas | 3 |
| Editing | União de ImageEditing e VideoEditing | 13 |
| Albums | Pinça, seleção, fast scroll, ordenação, mutações e coleções | 18 |
| Catalog | Ocultos, catálogo, banco/migração, arquivos, capas e thumbnails | 28 |
| Appearance | Feedback, submenus, configurações, tema, HUD, metadados, cabeçalho e tela principal | 15 |
| All | Descoberta de todos os casos de interface, sem filtro por área | 92 |

Os grupos podem se sobrepor. Ao combinar áreas, uma classe inteira substitui seleções de seus métodos, evitando executar o mesmo caso duas vezes. Os totais são calculados dos fontes atuais e conferidos com o resultado instrumentado; não ficam fixos nessa tabela. Para novos testes parametrizados ou outra linguagem/estrutura de fontes, adapte e valide a descoberta antes de usá-la: atualmente ela conta `@Test` em arquivos Kotlin da suíte existente, sem parametrização.

### Como escolher sem perder cobertura

- Mudança apenas em uma regra/controlador puro: `-UnitOnly`.
- Mudança no editor de fotos: `-Area ImageEditing`; no corte de vídeo: `-Area VideoEditing`.
- Mudança no reconhecimento/preparação de texto, inclusive no editor: acrescente `Ocr`. A seleção ImageEditing cobre o acesso ao OCR pelo editor, não todos os casos de orientação e detecção automática.
- Mudança em extração/cache de frames: `Timeline`; se modificar também a thumbnail principal/capa, acrescente `Catalog`.
- Mudança nas Views, tema, alinhamento ou submenus: acrescente `Appearance`. Aparência não é irrelevante; só não precisa acompanhar alterações exclusivamente na lógica de reprodução.
- Mudança compartilhada entre várias áreas: combine as áreas afetadas. Antes de publicação ou após uma mudança transversal, execute `-Delivery`.

Três testes instrumentados foram migrados, não eliminados: os dois cálculos de invalidação do catálogo estão em `CatalogFingerprintRulesTest`, e a escolha automática/manual da capa está em `AlbumCoverSelectionTest`. A base passou de 222 unitários + 92 instrumentados para **225 + 89**, mantendo os mesmos 314 casos do app. Banco, SharedPreferences, MediaStore e interface continuam com integração Android. Os testes do estado dirty do catálogo foram mantidos porque verificam também a persistência real, não apenas aritmética.

As correções seguintes de mutações/ocultos acrescentam dois casos de regras de pasta vazia e três integrações: configuração de exclusão definitiva sem apagar lixo anterior, retorno à lista após excluir o último item e contagem atualizada no painel de ocultos após uma mudança real no MediaStore. A base atual é **227 unitários + 92 instrumentados**. O teste existente de mover tudo também mantém documento/subpasta na origem para verificar que somente as mídias daquele álbum determinam o redirecionamento. Nenhuma cobertura anterior foi removida para acomodar esses casos.

O teste isolado do gesto da timeline usa uma Activity vazia da variante **debug**, sem abrir o visualizador/player. Essa Activity não é compilada no release. O teste de trilhas usa dois áudios e duas legendas em arquivos privados, verifica a seleção ativa, desativação/reativação da legenda e restauração num novo ExoPlayer. Não depende de inserir/remover mídia no catálogo. Isso verifica seleção real de trilhas, não a qualidade sonora ou todos os codecs de fabricantes.

Os testes de OCR no visualizador aguardam o pedido do controlador, incluindo o início automático agendado e o callback final, antes de clicar uma única vez em Copiar. Há uma barreira da fila para o caso de resultados antigos após navegação; ela não executa outro reconhecimento de imagem vazia. O limite de 60 s é um teto para detectar travamento, não uma espera fixa nem um retry. O reconhecimento real, o ícone, o texto copiado e a rejeição de resultados de outras mídias continuam obrigatórios. Os helpers vivem somente em androidTest e não alteram a fila/modelo/controlador do app.

## Antes de uma publicação autorizada

Finalize código, testes, configuração e versão **antes** de validar:

```powershell
./scripts/run-validation.ps1 -Delivery
./scripts/assert-delivery-validation.ps1
```

`-Delivery` não aceita área parcial nem `-UnitOnly`. Compila debug e APK de testes, executa todos os unitários, lint e todos os instrumentados. Instala os APKs dessa própria execução: não aceita `-SkipInstall` como fonte de aprovação. A checagem final confirma os fontes/configurações/scripts e os hashes dos APKs/relatórios; só então grava `app/build/reports/validation/delivery.json`.

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

O workflow Android CI mantém Java 17, cache Gradle, pushes/PRs em `main` e artefatos temporários por sete dias; permite disparo manual. Executa também os contratos das ferramentas, confere relatórios unitários não vazios/sem skips e compila/publica como artefato o APK de testes. **O CI não roda o emulador nesta configuração** e, portanto, não produz aprovação de entrega. A suíte completa local continua obrigatória no fluxo adotado.

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
