# Auditoria técnica — Galeria Android

Data: 14/09/2026. Escopo: galeria local, navegação, reprodução e gerenciamento de mídias. Esta é uma revisão do estado local encontrado, incluindo alterações anteriores ainda não commitadas; não é um comparativo exclusivo contra o APK 0.8.50.

## Resultado e limites

Foram aplicadas atualizações selecionadas de dependências e correções pequenas com benefício verificável. Não houve migração para Compose/XML, reescrita de Activities, mudança de permissões, alteração da política de exclusão/movimentação, aumento de `minSdk`, serviço remoto ou publicação.

Preservados: SDK de compilação/alvo 37, Android mínimo 23, Java 17, Gradle 9.6.1, esquema Room versão 2, versão do app 0.8.50/8050 e APK histórico na raiz. A versão interna do cache passou de 2 para 3 para exigir uma atualização do catálogo; não é uma migração destrutiva do banco.

Os testes descritos abaixo reduzem o risco, mas não garantem ausência de defeitos em todo aparelho, versão do Android, codec ou biblioteca de mídia. Os pontos de maior impacto foram documentados para uma etapa própria com testes de falha, antes de alterar o fluxo que já funciona.

## Atualizações aplicadas

| Componente | Antes desta auditoria | Depois | Motivo |
| --- | --- | --- | --- |
| Android Gradle Plugin | 9.3.0 | 9.3.2 | Manutenção na mesma linha; correção de falha do lint com Java 17 |
| Kotlin | 2.4.0 | 2.4.10 | Patch de correções, sem salto para a linha 2.4.20 |
| Coil / Coil Video | 3.5.0 | 3.6.2 | Correções de renderização de alvos com R8 e cancelamento de requisições deduplicadas |
| Room: plugin, runtime, paging, compiler e testing | 2.8.4 | 2.8.5 | Manutenção, com teste explícito da migração existente |
| Paging runtime | 3.5.0 | 3.5.1 | Correção de posição de âncora em paginação |
| AndroidX Test core/runner/rules | 1.6.1 | 1.7.0 | Alinhamento da infraestrutura de testes |
| AndroidX Test JUnit / Espresso do app | 1.2.1 / 3.6.1 | 1.3.0 / 3.7.0 | Alinhamento com as versões já usadas pelo módulo benchmark |
| kotlinx.serialization core transitivo | 1.7.3 | mínimo 1.8.1 | Compatibilidade binária com o JSON 1.8.1 utilizado por Room MigrationTestHelper |

O alinhamento de serialization foi necessário porque a resolução consistente do Android mantinha o core do app em 1.7.3 enquanto os testes carregavam JSON 1.8.1. O teste de migração detectou `AbstractMethodError`; uma constraint documentada no app corrige a mistura sem adicionar JSON ao código de produção.

Não foram adotadas todas as versões mais novas indiscriminadamente. Media3, ZoomImage, AppCompat, WorkManager, Lifecycle e bibliotecas de benchmark ficaram nas versões encontradas. Mudanças no player, zoom, temas e medições precisam de cobertura específica em aparelho físico. Room 3 também não foi introduzido.

Fontes oficiais consultadas para seleção: [AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes), [Kotlin](https://kotlinlang.org/docs/releases.html), [Coil](https://coil-kt.github.io/coil/changelog/), [Room 2.8.5](https://developer.android.com/jetpack/androidx/releases/room#2.8.5), [Paging 3.5.1](https://developer.android.com/jetpack/androidx/releases/paging#3.5.1), [AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test) e [serialization 1.8.1](https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.8.1).

## Correções verificáveis

### 1. Catálogo após renomear ou mover

Em `GalleryDatabase.kt`, `catalogFingerprint` não considerava nome, MIME, caminho e álbum. Uma mídia renomeada ou movida podia manter URI, tamanho e data; o cache interpretava o conteúdo como inalterado e pulava a atualização das linhas no Room.

A identificação agora inclui todos os campos persistidos do item. Dois testes novos falharam antes da correção e passaram depois: renomeação sem alterar tamanho/data e movimentação sem alterar URI/conteúdo. São testes do predicado de invalidação; o teste já existente de movimentação verifica também o caminho real no MediaStore.

### 2. Barra de tempo ao retornar ao vídeo

`DetailActivity.onPause` removia a atualização periódica da barra, mas `onResume` não a reiniciava. O vídeo retomava sem avanço contínuo da timeline.

`onResume` agora remove eventuais duplicatas e reinicia a atualização para vídeos. Um teste novo usa MP4 real, verifica decodificação e reprodução, envia a Activity para segundo plano e exige três valores distintos de progresso após o retorno. Esse teste falhou antes e passou depois. A amostra e a licença de origem ficam somente no APK de testes.

### 3. Encerramento de operações de interface

`DetailMediaActions` e `AlbumSelectionActions` agora rejeitam novas cargas após `close()` e deixam de entregar resultados à Activity encerrada/destruída. Isso protege a apresentação assíncrona de destinos de cópia/movimentação.

`DetailPlaybackController.releaseCurrent` desvincula o player do estado ativo antes de liberar o recurso; callbacks de encerramento do player antigo deixam de ser tratados como eventos do player atual. São proteções por inspeção do ciclo de vida, acompanhadas da suíte de interface; não há alegação de reprodução de todas as condições de corrida.

### 4. Build e entrega

- Checksum SHA-256 oficial da distribuição do Gradle adicionado ao wrapper.
- CI passa a guardar relatórios também em execuções bem-sucedidas e disponibilizar APK debug apenas após aprovação. Retenção: 7 dias. Falhas não são ignoradas.
- Padrões de arquivos de assinatura (`*.jks`, `*.keystore`, `keystore.properties`) adicionados ao `.gitignore`; nenhuma chave ou senha foi incluída.
- README atualizado para descrever a suíte e separar o APK histórico das alterações locais.
- A CI não foi disparada remotamente nesta auditoria. Validação local do comando equivalente não comprova a configuração do runner hospedado; confirmar a primeira execução após o envio autorizado.

## Validação executada

Ambiente: Windows, Temurin 17.0.19, emulador `Galeria_API_36`, Android 16 / API 36.

| Verificação | Resultado |
| --- | --- |
| `:app:testDebugUnitTest` | 51 testes, 0 falhas, 0 ignorados |
| `:app:connectedDebugAndroidTest` | 23 testes, 0 falhas, 0 ignorados, na mesma execução |
| `:app:lintDebug` | 0 erros, 87 avisos; mesma contagem inicial |
| `:app:assembleDebug` | Aprovado |
| `:app:assembleRelease` | Aprovado, incluindo R8 e assinatura local configurada |
| `:benchmark:connectedBenchmarkAndroidTest` | 4 casos aprovados na execução de 14/09 (3 macrobenchmarks e 1 gerador de perfil) |

Os 4 testes instrumentados adicionados são os dois de fingerprint, o de migração Room 1 → 2 e o de retomada de vídeo. Os 51 testes unitários e os outros 19 instrumentados já existiam no estado local encontrado.

O teste de migração usa banco exclusivo de teste, insere mídia e ordem personalizada no esquema 1 e valida o esquema 2 utilizando a migração de produção. Não limpa o banco normal do app. O teste de vídeo restaura as preferências que modifica e remove apenas sua própria mídia de teste.

Avisos do lint: 63 `UseKtx`, 9 `DiscouragedApi`, 9 `InternalInsetResource`, 2 `NotifyDataSetChanged`, 1 `ScopedStorage`, 1 `UseCompatLoadingForDrawables`, 1 `UseSwitchCompatOrMaterialCode` e 1 `ViewConstructor`. Não foram suprimidos para aparentar melhoria. O aviso `ScopedStorage` não foi tratado nesta etapa, pois o modelo de acesso já estava definido pelo projeto.

O ambiente local apresentou inicialmente erro de comunicação por socket no Java/Windows. A execução usou somente nesta sessão:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=C:\Windows\Temp'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:connectedDebugAndroidTest --console=plain
.\gradlew.bat :benchmark:connectedBenchmarkAndroidTest --console=plain
```

Não foi adicionada configuração específica de Windows à CI Linux.

Relatórios locais: `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, `app/build/reports/lint-results-debug.html`. APKs novos: `app/build/outputs/apk/debug/app-debug.apk` e `app/build/outputs/apk/release/app-release.apk`.

## Melhorias pendentes, em ordem de prioridade

Os itens abaixo são achados de leitura do código, não defeitos todos reproduzidos no emulador. A proposta para cada um é incremental e preserva os fluxos normais.

### Alta — Integridade em falhas de escrita e restauração

Evidência: `MediaActions.copyToHidden`, `restoreHiddenFile` e `ImageEditActivity.saveBitmapToGallery`. Retornos antecipados para streams nulos podem deixar arquivos vazios ou entradas pendentes no MediaStore. Na restauração, a publicação do destino não tem seu resultado verificado antes da exclusão do original; no editor, o resultado de `Bitmap.compress` não é conferido.

Próxima mudança proposta: tratar copiar → fechar → validar → publicar como uma operação, limpar somente o destino recém-criado quando falhar e apagar a origem somente depois de confirmar o destino. Testar storage cheio, stream nulo, exceção durante cópia, permissão revogada e falha ao publicar. Não reescrever conjuntamente os fluxos de exclusão e movimentação.

### Alta — Semântica da opção de lixeira

Evidência: `SettingsActivity` oferece `move_to_trash`, mas as ações principais de `DetailMediaActions`, `AlbumSelectionActions` e `MainActivity` chamam `requestPermanentDelete`. A preferência é consultada por outro caminho em `MediaActions`.

Próxima mudança proposta: confirmar o comportamento desejado de “Excluir”, centralizar a escolha entre lixeira e exclusão permanente e testar confirmações, cancelamentos e lote em cada nível de acesso. Não foi alterado silenciosamente nesta auditoria, porque modifica uma ação destrutiva percebida pelo usuário.

### Alta — Catálogo e mudanças de autorização

Evidência: `GalleryCatalogStore.hasFreshCatalog` e os caches de `MediaStoreRepository` distinguem gerenciamento completo de arquivos, mas não registram todo o conjunto de permissões/seletividade de fotos e vídeos. `AlbumCatalogController.load` pode apresentar resumos persistidos antes da nova consulta.

Risco a reproduzir: ao reduzir o acesso, metadados/miniaturas antigos podem continuar visíveis temporariamente ou ser reaproveitados. Isso não significa que o Android permita abrir um arquivo cuja autorização foi revogada.

Próxima mudança proposta: invalidar e filtrar o cache antes de exibi-lo após mudança de autorização, com testes de acesso completo → parcial → negado, reseleção e retorno das configurações. É um ajuste da coerência do cache, não uma nova reformulação do pedido de permissões.

### Média — Catálogo após mudanças fora do app

Evidência: `GalleryCatalogStore.hasFreshCatalog` ignora a idade do catálogo quando as versões do MediaStore são iguais. `getVersion` não é um contador de cada edição de mídia. O observador ajuda enquanto o processo está ativo, mas não cobre sozinho edições enquanto o app está fechado.

Próxima mudança proposta: observar a geração por volume real no Android 11+ e manter um limite de idade como fallback. Testar inclusão, remoção e renomeação externas com processo morto e montagem/desmontagem de cartão SD. Referência: [MediaStore.getGeneration](https://developer.android.com/reference/android/provider/MediaStore#getGeneration(android.content.Context,%20java.lang.String)).

### Média — Validação de nomes e caminhos

Evidência: `MediaActions.uniqueFile` recebe nomes vindos da mídia; `destinationRelativePath` remove a barra inicial antes de tentar reconhecer a raiz absoluta do armazenamento e não rejeita segmentos `.`/`..`.

Próxima mudança proposta: separar nome de arquivo de caminho, validar segmentos e garantir que o destino canônico esteja dentro da pasta escolhida. Testar nomes externos malformados, caminhos absolutos, Unicode e colisões de nomes. Não se afirma exploração comprovada; a barreira de validação é que precisa ficar explícita.

### Média — Visualização de ocultos e compartilhamento

Evidência: `FileDetailActivity` usa `BitmapFactory.decodeFile` diretamente na montagem da interface, sem redução prévia; pausa vídeos sem rotina correspondente de retomada. Intents de compartilhar/abrir em outro app podem receber `file://`, sem FileProvider configurado.

Próxima mudança proposta: decodificação dimensionada e assíncrona, teste de imagem muito grande e vídeo em segundo plano; compartilhamento por `content://` com provedor restrito às pastas necessárias. Validar cada fluxo antes de unificar visualizadores.

### Média — Identidade de itens nas grades

Evidência: `AlbumRecyclerAdapter.getItemId` e `MediaRecyclerAdapter.getItemId` convertem `String.hashCode()` de 32 bits para Long. A conversão não elimina colisões; chaves diferentes podem ter o mesmo ID estável.

Próxima mudança proposta: IDs realmente únicos por chave/volume, preservando identidade ao filtrar e reordenar. Testar chaves com hash colidente, paginação, seleção, mudança de capa e biblioteca grande. Não trocar todos os adapters nesta etapa.

### Média — Cancelamento de varreduras

Evidência: `MediaScanWorker` retorna `retry()` em `SecurityException`; a varredura recursiva de `MediaStoreRepository` não recebe sinal cooperativo de cancelamento. Revogação permanente de permissão pode provocar tentativas inúteis, e cancelar o WorkManager não garante interrupção imediata da leitura síncrona.

Próxima mudança proposta: distinguir falta de autorização de falha transitória, propagar cancelamento e impedir gravação de um catálogo parcialmente varrido. Testar cancelamento durante leitura e concorrência com atualização manual.

### Média — Insets, acessibilidade e editor

Evidência: 9 avisos de recursos internos de insets e 9 APIs desencorajadas; textos ainda aparecem diretamente em Kotlin fora das telas já internacionalizadas. O editor renderiza o estado de uma View no executor de salvamento.

Próxima mudança proposta: tratar insets numa tela por vez usando APIs públicas, validar gestos/barras/rotação; completar strings e percursos TalkBack; capturar estado imutável do editor na thread principal antes de renderizar em background. Não alterar tema ou geometria de todas as telas de uma vez.

### Baixa — Arquitetura e manutenção

`DetailActivity`, `MainActivity` e `AlbumMediaActivity` continuam extensas. A UI programática não exige migração apenas por ser programática. As extrações anteriores de catálogo, fila, ações e reprodução já permitem testar partes isoladamente.

Próxima mudança proposta: extrair apenas uma responsabilidade pura quando houver manutenção real naquele trecho; criar testes de comportamento antes. Evitar migração global para Compose, introdução de framework de injeção de dependências ou redução artificial de linhas que apenas esconda acoplamento.

Os 63 avisos `UseKtx` são sugestões de estilo, não justificam uma alteração ampla neste momento. Reavaliar também a política de backup de metadados/ocultos antes de alterar `allowBackup`, e a distribuição de APKs em Releases antes de remover qualquer binário versionado.

## Antes de distribuir uma próxima versão

1. Validar em aparelho físico com biblioteca real: vídeo longo, codecs diferentes, RAW/GIF, orientação, zoom, áudio e uso de memória.
2. Cobrir Android 23/28, 30, 34 e 37, além do API 36 usado aqui, com foco em permissões e MediaStore.
3. Testar cartão SD, armazenamento cheio, revogação de acesso, cancelamento e falhas durante operações destrutivas.
4. Fazer smoke test do APK release minificado em aparelho/instalação de teste. `assembleRelease` aprovado não equivale a execução funcional do release; a variante benchmark não usa minificação.
5. O incremento e a publicação foram autorizados posteriormente e preparados como v0.8.51. Não houve envio à Play Store.

Não há comparação de desempenho antes/depois suficiente para prometer ganho de velocidade. Emulador e macrobenchmarks sem limiares não substituem medições comparativas em aparelho físico.
