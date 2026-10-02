# Isolamento progressivo das funcionalidades — 02/10/2026

Base: commit `86ac6d39eddf65ab47a048c92eacd529e8116bf9`, release 0.8.62. Esta etapa não cria funcionalidades novas, não redesenha telas, não troca bibliotecas e não altera dados/preferências. Não inclui push ou release sem nova solicitação.

## Ordem de execução e critérios

| Etapa | Trabalho | Critério de conclusão | Estado |
| --- | --- | --- | --- |
| 1. Contrato de comportamento | Registrar funcionalidades, invariantes e evidências existentes; distinguir cobertura automatizada de validação manual. | Contrato explícito, preservação das asserções e mapeamento dos testes. | Registrado nesta etapa. |
| 2. Transições do visualizador | Separar a reserva, bloqueio, conclusão, fallback e identidade das trocas confirmadas da Activity. | Regras testáveis em JVM, limpeza única, proteção contra callback antigo, retomada do aleatório e regressões no emulador aprovadas. | Implementado e validado nesta etapa. |
| 3. Arbitragem dos gestos | Definir quem recebe cada sequência de toque no visualizador; depois aplicar à grade. | Navegação/zoom/OCR/cinema e seleção/pinça/refresh não competem pelo mesmo gesto. Sem alterar sensibilidades nesta extração. | Visualizador e grade implementados e validados nesta etapa. |
| 4. Ciclo de OCR | Isolar análise, detecção automática, requisição manual, relevância e cancelamento, sem acessar fila/player. | Resultado antigo não aparece na mídia nova; lifecycle e cópia preservados. | Ciclo do visualizador implementado e validado nesta etapa. |
| 5. Cinema e Motion Photos | Separar estado, início/encerramento e efeitos de sistema de cada recurso; manter sua integração por contratos. | Orientação, imersão, trilhas e retorno ao modo normal preservados; Motion Photo não muda a fila da galeria. | Controladores implementados e validados nesta etapa. |
| 6. Autoridade da reprodução | Centralizar pedidos de seek/play/pause vindos de timeline e controles; manter ciclo de vida e posse do player explícitos. | Nenhum componente secundário cria/libera o player; busca não ressuscita reprodução suspensa. | Implementada e validada nesta etapa. |
| 7. Portões de testes e entrega | Agrupar casos por área e preservar regressão ampla antes da distribuição. | Testes rápidos por alteração, integrações afetadas e confirmação completa antes de release. Falhas não explicadas impedem publicação. | Implementados e validados localmente. Execução do CI no GitHub pendente de push. |

Executar uma extração por vez. O objetivo é reduzir acoplamento e dar autoridade clara sobre estado/recursos, não apenas reduzir contagem de linhas ou mover funções para outro arquivo. Não declarar a Activity, a navegação ou a arquitetura inteira isolada ao concluir somente o controlador de transições.

## Contrato que não pode mudar

| Área | Comportamentos preservados | Evidência existente |
| --- | --- | --- |
| Navegação | Fotos/vídeos em ambos os eixos e sentidos; fila circular; mídia, página, título e player sincronizados após interrupção/retorno/reabertura. | `VideoViewerRegressionTest`, `ViewerStateRulesTest`, `SwipeGestureRulesTest`. |
| Aleatório/apresentação | Mesma fila e posição após rotação; avanço continua depois de cada troca; nenhuma liberação duplicada de player. | `DetailShuffleRotationInstrumentedTest`, `ViewerStateRulesTest`; repetição e apresentação também no checklist manual. |
| Reprodução | Play/pause, som, velocidade, repetição, memória temporária e retorno ao início quando concluído; pausa em segundo plano. | `DetailPlaybackInstrumentedTest`, `PlaybackMemoryInstrumentedTest`, `PlaybackResumeInstrumentedTest`, `VideoMenuInstrumentedTest`. |
| Cinema | Alternância por clique, player preservado, orientação e barras do sistema coerentes; brilho/volume e acesso às trilhas. | `CinemaModeInstrumentedTest`; dual áudio/legenda e codecs reais exigem confirmação manual adicional. |
| Timeline/Motion Photos | Barra simples e faixa sob demanda; navegação por toda a duração; foto/vídeo embutido preservados. | `VideoTimelineInstrumentedTest`, `MotionPhotoInstrumentedTest`, `ImageRotationInstrumentedTest`. |
| OCR/edição | Ícone automático, toque prolongado, cópia e texto do documento; cortar/girar/editor; original preservado nos fluxos de cópia. | `ImageTextRecognitionInstrumentedTest`, `ImageEditorInstrumentedTest`, `ImageRotationInstrumentedTest`, `VideoEditInstrumentedTest`. |
| Grade/seleção | Pinça, seleção por arraste sem refresh indevido, visualizar sem perder seleção, ordem e fast scroll. | `AlbumGridPinchInstrumentedTest`, `AlbumSelectionInstrumentedTest`, `AlbumMediaHeaderInstrumentedTest`, `AlbumFastScrollInstrumentedTest`. |
| Catálogo/arquivos | Exclusão/restauração/movimentação, atualização de origem/destino, favoritos, fixados e ocultos sem vazamento nas coleções. | `AlbumMutationInstrumentedTest`, `VirtualAlbumsInstrumentedTest`, `HiddenAlbumDialogInstrumentedTest`, `AutomaticHiddenAlbumsInstrumentedTest`, `CatalogMutationStateInstrumentedTest`. |
| Aparência/permissões | Mesmas ações, ícones, submenus, temas e níveis de acesso; nenhuma remoção silenciosa de opção. | `DetailHudInstrumentedTest`, `DialogActionAlignmentInstrumentedTest`, `ThemeCustomizationInstrumentedTest`, `ExternalMediaOpenInstrumentedTest` e checklist manual de permissões. |

Os nomes acima mapeiam evidências, não garantem que cada formato/aparelho/cenário esteja coberto. Testes verdes não substituem a confirmação de codecs reais e comportamento de fabricantes.

## Primeira extração: transições

`MediaTransitionController` é Kotlin puro, sem Activity, View, ExoPlayer, Handler ou Context. Recebe apenas um agendador substituível e callbacks de limpeza/conclusão.

- Estados: `IDLE`, `RESERVED`, `RUNNING`, `COMPLETING`.
- A reserva ocorre antes de alterar o índice e destacar o player anterior; impede nova troca simultânea.
- Animação e fallback usam a mesma conclusão com identidade própria: callback antigo nunca conclui uma troca mais recente.
- O fallback mantém os mesmos 100 ms de tolerância e as animações mantêm 165/245 ms.
- Pausa/salvamento concluem a troca; destruição/recarregamento liberam sem reconstruir UI.
- Limpeza cancela callbacks e libera recursos uma única vez. O bloqueio só é removido após a limpeza.
- Pré-carregamento e avanço aleatório são reagendados em `onSettled`, depois de liberar o bloqueio, para não perder o próximo avanço.
- Activity mantém o adaptador de páginas/player e a fila existente. A extração não move ainda índice, gestos, OCR ou cinema para esse controlador.
- Testes instrumentados mantêm os mesmos gestos/destinos/asserções; deixam de buscar o booleano privado removido e consultam o controlador. O teste de pausa continua removendo o fallback para verificar o lifecycle independentemente.

## Estratégia de testes desta etapa

1. Testes JVM determinísticos do controlador com agendador falso, sem sleeps/emulador.
2. Compilação, unitários e lint, sem afrouxar asserções.
3. Emulador: navegação/interrupções, aleatório/rotação, playback, cinema, timeline e Motion Photos.
4. Suíte completa nesta primeira alteração do núcleo como confirmação ampla. Nas próximas extrações, testes direcionados durante desenvolvimento e regressão completa no fechamento da etapa/release.

O emulador é usado com suas mídias de teste e sem limpar dados. A build é realizada numa cópia temporária local, a partir dos fontes na unidade Y:, para evitar I/O de rede. Não há comparação controlada de desempenho antes/depois da limpeza do computador.

### Reproduzir a validação

Com Java 17 configurado, na cópia local com os mesmos fontes:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:directUnitTests :app:lintDebug -I scripts/direct-unit-tests.gradle --no-daemon --max-workers=2 --console=plain
.\scripts\run-android-tests.ps1 -All -Device emulator-5554
```

`directUnitTests` usa as mesmas classes compiladas e classpath de execução dos testes JUnit; é o fallback local já existente para a comunicação dos workers de teste no Windows. Não desativa testes ou asserções. O runner instrumentado conserva o relatório completo e produz um resumo curto; para diagnosticar uma área, permite `-Class` e repetição sem reinstalação por `-SkipInstall`, desde que os APKs instalados correspondam aos fontes atuais.

## Resultado

- Compilação debug e APK de testes: aprovadas.
- Unitários: **117/117 aprovados** via JUnit direto, incluindo 11 novos testes determinísticos do controlador. Execução do JUnit em 0,386 s (não inclui compilação/Gradle).
- Lint debug: **zero erros e 125 avisos**, mesma quantidade de avisos da base 0.8.62.
- Bateria direcionada inicial no emulador API 36: **18/18 aprovados em 114,3 s**. Essa execução precedeu o reforço do teste de ciclos sucessivos do aleatório.
- APK de testes recompilado após esse reforço; suíte completa no AVD `Galeria_Codex_Test_36`: **87/87 aprovados em 295,9 s**, zero falhas e zero testes ignorados. Inclui ciclos sucessivos do aleatório após rotação, gestos após reabertura, pausa durante transição, cinema, OCR, edição, seleção, timeline, Motion Photos, ocultos e operações de arquivos.
- Os cinco arquivos Kotlin novos/alterados tiveram hashes comparados entre o repositório Y: e a cópia local usada na build; todos coincidiram.
- Nenhum teste foi removido/desativado e nenhuma asserção de comportamento foi relaxada. Não houve mudança de versão, push ou release.

Relatórios completos preservados em `app/build/reports/architecture-2026-10-02-b5bc4730/` (diretório de build, não versionado): `20261002-093817-469-run-1.txt` (suíte completa), `20261002-093426-200-run-1.txt` (direcionada), `lint-results-debug.html` e `lint-results-debug.xml`.

A navegação inteira e a Activity ainda não estão isoladas; a primeira entrega separou somente o controle das transições confirmadas. Os resultados são de build debug em emulador, não de APK release ou aparelho físico.

## Segunda extração: gestos

- `ViewerSwipeGestureController` passa a ser dono da origem do toque, eixo/direção fixados durante o arraste, distância, translação e decisão de confirmação. A Activity mantém páginas, índice, fila, animações e tradução de eventos Android.
- `ImageGestureArbiter` é criado por imagem e decide a entrega ao zoom ou à galeria, candidatura a clique, concessão do toque prolongado de OCR e prioridade ao soltar/cancelar. O agendamento Android e os efeitos sobre a View continuam no adaptador.
- Preservados os limiares existentes: zona morta, empate diagonal horizontal, confirmação em `1,35 × touchSlop`, movimento estritamente maior que `touchSlop` para cancelar o candidato de texto, e o timeout de toque prolongado do Android. A diferença já existente entre `>=` na navegação e `>` no candidato de texto não foi alterada silenciosamente.
- Preservados o retorno do gesto ao zoom com múltiplos dedos/foto ampliada, cancelamento único da sequência do zoom ao iniciar navegação e ordem de limpeza após os efeitos de `ACTION_UP`.
- Gestos de brilho/volume continuam sob `VideoCinemaGestureController`, sem mudanças. Toque simples/duplo, HUD, seek, animações e sensibilidade não foram redesenhados.
- `AlbumGridGestureController` centraliza a prioridade entre pinça, seleção, reordenação e rolagem normal, os intervalos percorridos pela seleção e o acúmulo da escala horizontal. RecyclerView, adapter, auto-scroll, animação, posição da grade e operações de reordenação permanecem na Activity.
- Ao levantar um dos dedos da pinça, o restante da sequência continua pertencendo à pinça até `UP/CANCEL`, sem cair no pull-to-refresh. O passo de escala `1,08`, filtro de fatores `0,5..2`, limites de colunas e inclusão dos itens intermediários na seleção não mudaram.
- A concessão do toque prolongado consulta a identidade da mídia somente se o gesto ainda for candidato: preserva o curto-circuito original e evita ler a fila para um toque cancelado ou já entregue à navegação.
- Novo teste instrumentado cobre zoom real por toque duplo, arraste sobre foto ampliada, segundo dedo durante uma prévia de navegação e retorno ao arraste normal. As asserções existentes continuam ativas.

### Validação da segunda extração

- JVM final: **144/144 testes aprovados**, incluindo 15 novos casos do visualizador e 12 da grade (27 nesta extração). Execução JUnit em 0,484 s, excluindo compilação/Gradle.
- Compilação debug/APK de testes final e lint: aprovados; **zero erros e 125 avisos**.
- Visualizador, antes de extrair a grade: **18/18 testes instrumentados aprovados em 162,3 s**.
- Após extrair a grade: **88/88 testes da suíte ampla aprovados em 362,5 s**, zero ignorados. Essa rodada usou o APK anterior à última preservação do curto-circuito da consulta de identidade no toque prolongado.
- Depois desse último ajuste: APK recompilado e hashes dos sete arquivos desta extração comparados com o repositório, todos idênticos. **20/20 testes direcionados do APK final aprovados em 150,5 s**, zero falhas e zero ignorados: navegação/zoom, HUD, OCR, cinema, aleatório, pinça e seleção contínua. Não atribuir a rodada ampla anterior a esse APK final.
- Durante a criação dos testes novos, duas expectativas foram corrigidas: `-0.0f` mantém exatamente o resultado do cálculo anterior da translação; o teste de zoom compara a URI efetivamente aberta antes/depois do gesto, pois MediaStore pode representar a mesma mídia pelas coleções `images` e `file`. Não houve alteração do comportamento de produção para satisfazer essas expectativas, nem remoção/afrouxamento de asserções dos testes preexistentes.

Relatórios preservados em `app/build/reports/gestures-2026-10-02-b5bc4730/` (não versionado): `20261002-101142-131-run-1.txt` (20 testes do APK final), `20261002-100453-105-run-1.txt` (suíte ampla), `20261002-095800-784-run-1.txt` (visualizador inicial), `20261002-095605-352-run-1.txt` (falha de URI do teste novo, corrigida) e lint final HTML/XML.

Nenhuma versão publicada nesta etapa, nenhuma função removida e nenhuma preferência migrada. Próximo passo: isolar o ciclo de OCR. Cinema/Motion Photos, autoridade de reprodução e automação adicional dos portões de testes continuam pendentes; os adaptadores Android e suas operações ainda não estão totalmente isolados.

## Terceira extração: ciclo de OCR do visualizador

- `ViewerTextRecognitionController` é Kotlin puro e passa a possuir o pedido ativo, detecção automática, leitura manual, deduplicação, caches limitados e relevância. Não recebe fila, player, View, URI Android ou Context; a Activity fornece um snapshot da imagem e adapta os efeitos existentes.
- Mantidos o atraso automático de 650 ms, caches LRU separados de 16 entradas e a chave que incorpora revisão, tamanho e data. A leitura automática leve não substitui a leitura manual detalhada; erros não são guardados em cache e uma leitura manual vazia pode ser repetida.
- Cada pedido tem identidade própria. Um resultado obsoleto não mostra texto, não alimenta o cache e não limpa um pedido novo, inclusive ao voltar à mesma foto. Conclusões duplicadas são ignoradas.
- Ao confirmar uma navegação, o pedido anterior é invalidado antes da troca de fila/player, fechando a janela entre a confirmação do gesto e a conclusão da animação. Uma prévia de gesto cancelada não dispara essa invalidação. Pausa e destruição também invalidam os pedidos.
- A verificação executada no worker consulta apenas relevância/lifecycle, sem ler a fila ou os caches fora da thread principal. A identidade da imagem é verificada no início e na entrega, na thread principal.
- O cancelamento é lógico/cooperativo: não interrompe à força uma chamada ML Kit já em execução. O motor existente verifica a relevância antes das próximas fases e da entrega.
- `ImageTextRecognition`, `OcrRules`, algoritmo/modelo, qualidade da imagem, OCR do editor, toque prolongado, ícone e diálogo de cópia continuam inalterados. Esta extração não isola ainda todo o processamento Android nem o OCR do editor.
- Novo teste instrumentado segura a fila real de OCR com uma barreira limitada, inicia a leitura pelo toque prolongado, troca de foto, pausa/retoma o visualizador e libera o worker. Verifica que texto/ícone antigos não aparecem; ao voltar, uma nova leitura e a cópia do documento funcionam. Não usa delays arbitrários para simular a corrida nem introduz hooks de teste no código de produção.

### Validação da terceira extração

- JVM: **164/164 testes aprovados**, incluindo 20 novos casos determinísticos de relevância, retorno à mesma foto, edição/revisão, pause/resume, cache, deduplicação, falhas e entrega única. Execução JUnit direta em 0,428 s; confirmação pelo comando padrão `testDebugUnitTest`: 164 testes, zero falhas/erros/ignorados, soma dos tempos JUnit de 0,291 s. Tempos excluem compilação/Gradle.
- Compilação debug/APK de testes e lint: aprovados; **zero erros e 126 avisos**. Há um aviso de estilo `UseKtx` adicional em `DetailActivity.kt:155`, sugerindo `String.toUri` no adaptador que mantém o uso existente de `Uri.parse`; nenhum aviso foi suprimido.
- Novo teste isolado no AVD `Galeria_Codex_Test_36`: **1/1 aprovado em 14,7 s**. A primeira suíte ampla terminou com **88/89 aprovados em 359,1 s**, zero ignorados: o caso `cinemaButtonChangesModeWithoutReplacingPlayer` não encontrou o popup após a rotação. O mesmo teste passou três vezes isoladamente (9,8/9,3/9,1 s), antes de modificar o teste; isso não apaga a falha ampla.
- O teste de cinema aguardava orientação/foco, mas podia interagir antes de terminar o layout/animação. Foi adicionada espera limitada por transição concluída, superfícies medidas sem layout pendente e alpha final; nenhuma asserção, ação de menu, verificação de player/trilhas/barras ou regra de produção foi removida. Após recompilar/reinstalar o APK de testes, **5/5 casos de cinema passaram em 27 s**. A nova suíte completa do APK final passou: **89/89 aprovados em 317 s**, zero falhas e zero ignorados. O resultado é consistente com a hipótese de sincronização do teste; não é uma prova de ausência de todos os problemas possíveis de popup.
- Os cinco arquivos Kotlin novos/alterados nesta extração tiveram hashes comparados entre o repositório Y: e a cópia local usada na build; todos coincidiram.

O Java apresentou novamente falha local de socket (`PipeImpl`/`UnixDomainSockets`, `Invalid argument: connect`) ao recompilar os testes. A execução foi recuperada com `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\Public'`, somente no processo de build e seus filhos. Isso também permitiu executar `testDebugUnitTest` sem o fallback direto; não houve alteração global, de bibliotecas ou do projeto para contornar esse erro. A propriedade define o diretório temporário de sockets conforme a [documentação Java 17](https://docs.oracle.com/en/java/javase/17/core/java-networking.html).

Relatórios desta extração preservados em `app/build/reports/ocr-2026-10-02-b5bc4730/` (não versionado): novo teste isolado (`20261002-103302-980-run-1.txt`), primeira suíte ampla com falha (`20261002-103401-352-run-1.txt`), três repetições de diagnóstico (`20261002-104024-605-run-1/2/3.txt`), cinema após a espera corrigida (`20261002-104434-736-run-1.txt`), suíte completa final aprovada (`20261002-104503-392-run-1.txt`), lint HTML/XML e relatórios JUnit HTML/XML. O AVD headless criado para esta validação foi encerrado após confirmar sua identidade.

Não houve remoção/desativação de teste, relaxamento de asserções preexistentes, mudança de versão, push ou release. Próxima extração: cinema e Motion Photos. Autoridade de reprodução e automação adicional dos portões continuam pendentes. Os resultados são de build debug no emulador, não de APK release/aparelho físico.

## Quarta extração: cinema e Motion Photos

- `ViewerCinemaController` possui o modo ativo, preferência restaurada para a primeira mídia, orientação anterior e identidade/timers da transição. É Kotlin puro e não recebe fila, player, Context ou Views. O adaptador da Activity aplica orientação/barras, vincula as trilhas pelo controlador existente e executa as mesmas animações/ícones.
- Preservados o atraso de rotação de 170 ms, fallback de 520 ms, bloqueio de novo clique durante a transição, preferência do álbum e prevalência do estado salvo na recriação. Abrir foto desativa cinema e restaura a orientação anterior; barras são reafirmadas ao recuperar foco/retomar, sem alterar o HUD.
- Conclusão e encerramento invalidam a identidade antes dos efeitos e cancelam os timers. Callback obsoleto não gira a tela nem conclui uma transição nova. Pausa/salvamento/troca de mídia podem concluir uma transição pendente; destruição cancela sem reconstruir a UI.
- Corrigido um risco observado no código anterior: o ajuste de brilho/volume removia os timers da transição, mas não liberava `cinemaTransitionRunning`. Agora o controlador conclui a transição antes do efeito do gesto, evitando deixar o botão de cinema bloqueado. Sensibilidade, lado de brilho/volume, `VideoCinemaGestureController`, seleção de áudio/legenda e `CinemaModePreferences` não mudaram.
- `ViewerMotionPhotoController` possui seleção/detecção da foto atual, identidade do pedido e cache de resultados, inclusive resultado negativo. O adaptador mantém os formatos JPEG/HEIC/HEIF, chave de revisão/tamanho e executor existente. Resultado de foto anterior, revisão antiga ou sessão pausada/fechada não revela a ação na foto nova; o clique também verifica a identidade antes de abrir o vídeo embutido.
- Mantido o cache de detecção em memória durante a Activity, sem introduzir persistência nem mudar limites do extrator. Um resultado rejeitado não alimenta esse cache. Ao retomar, a ação é atualizada usando o cache aceito ou uma nova detecção. O worker consulta relevância sem acessar a fila da galeria.
- `MotionPhotoPlaybackSession` possui a extração única, entrega única, player do vídeo embutido e ciclo start/stop/close. Criar/vincular/preparar, timeline e efeitos Android são adaptados na `MotionPhotoActivity`. Fechar impede criação a partir de resultado atrasado e libera o player uma única vez, após desvincular a timeline. O player da galeria não é entregue a essa sessão.
- Preservado o comportamento anterior da Motion Photo: uma extração concluída enquanto ativa inicia reprodução; em segundo plano prepara sem tocar; retornar a uma sessão já pausada apenas retoma atualizações, não força play. Os comandos do botão play/pause e da timeline continuam existentes; centralizá-los será a etapa 6.
- `MotionPhotoSupport`, detecção/extração/cache de vídeo, metadados embutidos e codecs não foram alterados. Não houve redesenho, função nova, migração de preferências, mudança de versão ou bibliotecas. Estas extrações não tornam todos os efeitos Android ou a Activity inteira isolados.

### Validação da quarta extração

- **198/198 unitários aprovados** por `testDebugUnitTest`, zero falhas/erros/ignorados. Inclui 34 novos casos: 13 de cinema, 11 de detecção Motion Photo e 10 da sessão embutida. Soma dos tempos JUnit em 0,286 s, excluindo Gradle/compilação.
- Debug e APK de testes compilados; lint aprovado com **zero erros e 126 avisos**, sem aumento em relação à etapa de OCR. Durante o desenvolvimento, `cinemaController.finish()` gerou um falso positivo `ChromeOsOnConfigurationChanged` (o lint interpretou como `Activity.finish()`); o método foi renomeado para `settle()`, sem suprimir a verificação. Um erro de assinatura no novo gesto do teste também foi corrigido antes da build final.
- Primeira rodada direcionada: **6/7 aprovados em 52 s**, zero ignorados; todos os seis testes de cinema passaram, incluindo a nova interrupção por pausa durante a animação. A ampliação do teste Motion Photo falhou ao tentar tocar no centro geométrico de “Voltar” e retornar à foto. A tentativa com espera de transição continuou falhando (**0/1 em 20,5 s**).
- Diagnóstico registrou centro do botão em y=84 px e status bar até y=85 px, com padding superior de 110 px. O teste passou a injetar o toque na área interna visível do texto, fora do padding de sistema, e aguardar de forma limitada o retorno ao visualizador/foco. Não substituiu o toque por `performClick`, não alterou a UI de produção e mantém a exigência de voltar à mesma mídia/índice da fila.
- Após essa correção: **1/1 teste Motion Photo aprovado em 14,7 s**. O caso continua cobrindo extração sem modificar a foto, reprodução e seek; acrescenta retorno à mesma foto/fila, pausa real no `onStop`, player preservado e ausência de autoplay forçado ao voltar.
- APK de testes recompilado após retirar o log de diagnóstico. Suíte completa final: **90/90 aprovados em 322,7 s**, zero falhas e zero ignorados, no AVD API 36 `Galeria_Codex_Test_36`. Inclui os novos contratos de pausa/retorno, os casos preexistentes de cinema/áudio/legenda e regressões de navegação, timeline, OCR, edição, grade, ocultos e operações de arquivos. Há um aviso de API obsoleta no construtor `GeneralClickAction` do teste, não um erro de compilação; nenhuma dependência foi atualizada nesta etapa.
- Nove fontes Kotlin desta extração comparados por hash entre Y: e a cópia local usada para a build; todos coincidem. Mantida a configuração de sockets Java somente no processo, como registrado na etapa anterior.

Relatórios preservados em `app/build/reports/cinema-motion-2026-10-02-b5bc4730/` (não versionado): rodada direcionada inicial (`20261002-110501-535-run-1.txt`), tentativa com espera (`20261002-111126-121-run-1.txt`), Motion Photo após corrigir o ponto de toque (`20261002-111528-785-run-1.txt`), suíte completa final (`20261002-112338-170-run-1.txt`), lint HTML/XML e unitários HTML/XML. O AVD headless usado nesta etapa foi encerrado após conferir sua identidade. Nenhum teste removido/desativado ou asserção preexistente afrouxada.

Próximo passo: autoridade de reprodução (etapa 6). Não há push ou release solicitado nesta rodada. Resultados de APK debug no emulador, não de APK release ou aparelho físico. Validação de dual áudio/legendas em arquivos reais de diferentes codecs e aparelhos continua sendo uma confirmação manual complementar, não garantida pela suíte do emulador.

## Quinta extração: autoridade de reprodução

- `PlaybackCommandController` é Kotlin puro e possui o canal de comandos da sessão atual, identidade de cada arraste, intenção de retomada e suspensão por lifecycle. Não recebe Activity, fila, View ou ExoPlayer e não cria/libera players. O agendador é substituível nos testes, sem sleeps.
- `ExoPlaybackCommands` adapta esse contrato ao ExoPlayer/Handler na thread principal. Cada dono de player possui sua autoridade: galeria, Motion Photo e prévia do editor não compartilham canais. A posse e a liberação do recurso permanecem nos respectivos controladores/Activities, depois de revogar o canal.
- `VideoTimelineBinding` deixa de executar seek/play/pause diretamente. Barra simples e faixa visual enviam pedidos pelo canal explicitamente fornecido pelo dono do player; o adaptador lê posição/duração para a UI. Não há player ou autoridade criada pela timeline.
- Preservados coalescimento de seeks em 60 ms, atualizações visuais em 100 ms, escala da barra, precisão da posição final e preview até um milissegundo antes do término. A busca final/cancelada ainda ocorre com `SeekParameters.EXACT`, restaurando depois os parâmetros anteriores, mesmo se já fossem EXACT; não impõe um parâmetro padrão novo.
- Durante um arraste, a pausa é temporária e não apaga a intenção original de reprodução. Play/pause explícito, seek explícito, troca de sessão e lifecycle invalidam o arraste e cancelam seu trabalho pendente. Soltar o gesto antigo ou executar um callback cancelado não altera a sessão nova nem desfaz uma pausa explícita. Até as leituras de um canal expirado são rejeitadas antes de tocar no player antigo.
- A galeria suspende a autoridade antes de cancelar a UI da timeline, evitando que a conclusão do gesto possa reativar a reprodução. O cancelamento por lifecycle retorna à posição anterior ao arraste. A galeria mantém sua política de retomar somente quando havia intenção de tocar; editor e Motion Photos retomam atualizações sem forçar autoplay, como antes.
- Restauração inicial de posição aguarda uma sessão ativa, caso o player alcance READY em segundo plano; não perde silenciosamente a posição restaurada. Memória de 12 horas, conclusão do vídeo, som, velocidade, repetição, trilhas, cinema, fila, exportação de corte e extração de Motion Photos não foram redesenhados.
- A centralização não isola ainda toda a Activity, o ExoPlayer ou o processamento de codecs. Os adaptadores continuam responsáveis pelos efeitos Android; o controle das trilhas/velocidade/volume continua no componente que já o possuía. Nenhuma função, ícone ou configuração foi removida.

### Validação da quinta extração

- `testDebugUnitTest`: **222/222 aprovados**, zero falhas/erros/ignorados, incluindo 24 novos casos determinísticos de comandos, arraste, cancelamento, callbacks antigos, ciclo de vida, retomada, canal expirado e parâmetros de seek. Soma dos tempos JUnit de 0,344 s, excluindo Gradle/compilação.
- Compilação debug e APK de testes aprovados; lint final aprovado com **zero erros e 126 avisos**, mesma quantidade da etapa anterior. Mantida a configuração de sockets somente no processo Java.
- Primeira bateria direcionada: **20/21 aprovados em 173 s**, zero ignorados. Os dois testes novos de conflito, timeline, retorno ao segundo plano, memória de posição, som/velocidade, Motion Photo e corte passaram. O caso de cinema falhou ao não encontrar o popup após tocar em Mais opções na rotação.
- O mesmo caso passou três vezes isoladamente, antes de alterar o teste (**12,2 / 10,3 / 12,1 s**). Isso não anula a falha direcionada nem prova que a causa seja apenas sincronização. O teste agora aguarda também a entrada Android sem camada de transição bloqueadora e exige que um único toque injetado entregue DOWN/UP ao botão e abra uma janela PopupDecorView em até cinco segundos. Não há repetição automática do clique, `performClick` substituindo esse toque ou asserção de trilhas/player removida; a falha fica mais rápida e informa eventos/posição do botão.
- Suíte completa do APK final: **92/92 aprovados em 341,3 s**, zero falhas e zero ignorados, no AVD API 36 `Galeria_Codex_Test_36`. Inclui os dois novos casos de disputa entre clique/arraste/lifecycle e todas as verificações preexistentes. O caso de cinema passou com a sincronização reforçada, um único toque e as verificações de trilhas, player e barras preservadas. O resultado é compatível com falha intermitente de sincronização da entrada após a rotação; não prova sozinho a causa exata nem ausência de problemas em outros aparelhos.
- Dez fontes Kotlin desta etapa (sete de produção, um unitário e dois instrumentados) comparados por hash entre Y: e a cópia local de build; todos idênticos. Nenhum teste foi removido/desativado ou asserção preexistente relaxada.

Relatórios preservados em `app/build/reports/playback-2026-10-02-b5bc4730/` (não versionado): direcionada inicial com falha (`20261002-114557-979-run-1.txt`), três repetições isoladas anteriores ao ajuste do teste (`20261002-115002-588-run-1/2/3.txt`), suíte completa final (`20261002-115333-799-run-1.txt`), lint HTML/XML e unitários HTML/XML. O AVD headless usado nesta validação foi encerrado após conferir sua identidade.

Não houve mudança de versão, bibliotecas, push ou release. Resultados de APK debug no emulador, não de APK release ou aparelho físico; codecs/formatos de fabricantes continuam exigindo confirmação complementar. Próxima etapa planejada: portões de testes por área e regressão ampla antes da distribuição.

## Sexta entrega: portões de testes e evidências

- `run-validation.ps1` fornece lógica sem emulador, testes de interface por área/combinação e fechamento completo por `-Delivery`. Todos os unitários permanecem ativos; filtros reduzem apenas os instrumentados e incluem integrações conhecidas. A descoberta de All não depende de manter uma lista manual de classes completa.
- Logs de Gradle ficam no relatório da execução; o terminal recebe resumo e erros relevantes. Instalação ocorre uma vez por rodada; o runner anterior e suas repetições explícitas continuam disponíveis. Não há retry automático, limpeza de dados ou inicialização automática de aparelho.
- O runner passa a rejeitar ignores/assumptions, zero testes, interrupção, resumo inconsistente e quantidade diferente da selecionada. Os códigos seguem o AndroidJUnitRunner oficial. Nenhuma asserção/teste foi removida para obter aprovação.
- Um resultado por área ou unitários não libera entrega. A aprovação completa registra fingerprint dos inputs e hashes dos APKs debug/teste e relatórios; `assert-delivery-validation.ps1` revalida as evidências. Mudanças de fontes, artefatos ou falhas posteriores invalidam a aprovação; tentativa posterior em andamento/falhada também bloqueia aprovação antiga. Os outputs e segredos não entram no fingerprint.
- Pisos de segurança da base: 222 unitários e 92 instrumentados, sem skips. Novos testes aumentam a seleção automaticamente; remoção/migração exige decisão e revisão explícita, não redução silenciosa dos pisos. A descoberta atual é para os testes Kotlin não parametrizados existentes.
- CI mantém Java/cache/retention e checks anteriores; acrescenta contratos do tooling, verificação dos XMLs e compilação/artefato do APK instrumentado. Foi habilitado disparo manual. O CI **não executa o emulador** nesta configuração e não substitui a aprovação local completa. Não houve publicação, credencial nova ou alteração de branch protection.
- [Guia de comandos, áreas e limites](TESTES-VALIDACAO.md). O portão local não bloqueia tecnicamente um comando manual de publicação fora desse fluxo, não valida sozinho release assinado e não substitui testes complementares de codecs/aparelhos.

### Validação da sexta entrega

- 38 contratos das ferramentas aprovados com fixtures sintéticos isolados, sem emulador; incluem rejeição de evidências alteradas, execução parcial, ignores, abortos, erro de lint, APK alterado e falhas posteriores. Os fixtures não são testes do aplicativo nem uma aprovação real de entrega.
- Caminho `-UnitOnly`: 222/222 unitários aprovados, zero skips; execução completa do comando em 17,4 s com build aquecida. Essa rodada usou a revisão com 37 contratos, antes de acrescentar o caso de XML que não é lint.
- Caminho `-Area Ocr`: build/unitários/lint aprovados, 6/6 instrumentados em 64,4 s, comando completo em 95 s. Confirma seleção por área, passagem dos argumentos ao runner e gravação da evidência parcial. A checagem rejeitou explicitamente a evidência UnitOnly como aprovação de entrega.
- Fingerprints completos do repositório Y: e da cópia local de build coincidiram antes da execução final. Nenhum Kotlin, comportamento visual, versão, dependência ou preferência do aplicativo foi modificado nesta entrega.
- Caminho `-Delivery` final: **222/222 unitários, 92/92 instrumentados, zero erros de lint e 126 avisos existentes**, zero falhas/ignorados. Interface em 333,9 s; comando completo em 356,3 s com build aquecida. Os 38 contratos do tooling também passaram nesta execução final.
- `assert-delivery-validation.ps1` aprovou a evidência na cópia local e novamente no repositório Y:, após conferir fingerprint e preservar APKs/relatórios idênticos. Debug/test APKs anteriores em Y:, quando presentes, foram copiados para backup de build antes da substituição; nenhum release histórico foi modificado.
- Todos os scripts PowerShell passam pelo parser, e `git diff --check` passou. As etapas de CI foram verificadas contra a configuração e documentação oficial; a execução do workflow no GitHub **não foi realizada** nesta rodada, pois não houve push. Não confundir validação local com um job remoto aprovado.

Evidências em `app/build/reports/validation/`: `20261002-144303-182-4347367c` (UnitOnly), `20261002-144559-229-54083a85` (Ocr), `20261002-144841-445-f58721a1` (Delivery final, nativo `20261002-144901-536-run-1.txt`) e `delivery.json`/`delivery-attempt.json`. Cada pasta mantém log do Gradle, XMLs e resultado; o backup dos APKs derivados fica em `artifacts-before-20261002-144841-445-f58721a1`. O AVD headless criado para a rodada foi encerrado após conferir sua identidade.

A sequência de sete etapas está concluída no escopo definido. A Activity e os efeitos Android ainda não estão totalmente isolados, e testes verdes não garantem todos os codecs/aparelhos. Não houve push, release, versão nova ou mudança nas funcionalidades nesta entrega de automação.

## Revisão da suíte após a análise de relevância

- Migração autorizada de três casos, preservando as verificações: dois de fingerprint do catálogo para `CatalogFingerprintRulesTest` e um de capa para `AlbumCoverSelectionTest`. Os adaptadores do app usam as mesmas regras puras, sem mudar algoritmo de hash, critérios de capa, preferências ou UI. A base fica em 225 unitários + 89 instrumentados: os mesmos 314 casos do app, sem exclusão de cobertura crítica.
- Os casos Android de estado dirty/persistência continuam instrumentados. A Activity vazia de teste da View da timeline só existe em debug, nunca em release. O teste de trilhas deixa de criar mídia no catálogo e passa a verificar dois áudios e duas legendas no ExoPlayer, desligamento/reativação da legenda e seleção restaurada num player novo. As amostras ficam em arquivos privados de teste e são removidas ao terminar.
- A comparação de miniaturas agora verifica a sequência cronológica, não somente o conjunto de timestamps, e confirma reutilização de cada quadro no cache. Não foi alterada a prioridade progressiva de decodificação do app.
- Grupos menores: Playback 12→9, Cinema 14→10, Timeline 12→9, Editing 17→13, Albums 17→15 e Catalog 28→25. ImageEditing (10) e VideoEditing (3) permitem separar os editores. Appearance recebe o teste existente do cabeçalho (14→15); nenhuma verificação visual foi eliminada. Combinações deduplicam classe/método. Pisos locais/CI atualizados somente pela migração, sem aceitar ignores, falhas ou entrega parcial.
- 42 contratos sintéticos das ferramentas aprovados, incluindo quatro novos casos de agrupamento. Preflight das três integrações modificadas (trilhas reais, sequência/cache, gesto isolado) passou em 11,5 s no emulador. Compilação, 225 unitários e lint passaram (zero erros, mesmos 126 avisos).
- A primeira tentativa ampla executou 89 casos e falhou em três de OCR (478,1 s). A rodada havia sido iniciada com dois núcleos/1536 MB, abaixo da configuração original do AVD (quatro núcleos/2 GB). Os testes tinham esperas fixas de 15–20 s e chamadas duplicadas; o log mostrou um arquivo de fixture sendo apagado antes da fila terminar sua leitura. Não foi aplicada alteração no modelo/fila/gestos do OCR do app.
- Os testes agora aguardam o pedido agendado/ativo do controlador e a entrega do callback antes do clique único em Copiar, com teto de segurança de 60 s. Uma barreira esvazia pedidos antigos sem reconhecer outra imagem vazia; desaparece também a espera fixa de 1 s desse caso. As verificações de conteúdo, ícone, isolamento e cópia são mantidas. A área Ocr passou 6/6 em 172,1 s ainda com dois núcleos (comando completo 230,3 s); esse tempo não é evidência de melhoria geral de velocidade. O AVD foi reiniciado usando sua configuração original, sem alteração global, antes da validação final.

Comandos e critérios atualizados em [TESTES-VALIDACAO.md](TESTES-VALIDACAO.md). A evidência parcial de OCR não substitui a validação completa posterior e não desbloqueia por si só uma entrega após a falha ampla.

### Validação final da revisão

- `-Delivery` aprovado com **225/225 unitários, 89/89 instrumentados, zero falhas/ignorados, zero erros de lint e 126 avisos existentes**, além dos 42 contratos sintéticos. Execução nativa em 348,4 s; comando completo em 384,6 s. A suíte completa não ficou mais rápida nesta medição que a rodada anterior de 333,9 s: não atribuir a redução de casos por área a um ganho medido no tempo da bateria inteira.
- Evidência completa: `app/build/reports/validation/20261002-153526-040-9b84448e/`, incluindo `20261002-153558-368-run-1.txt`. Os relatórios da tentativa falhada `20261002-151612-847-29b85c0f` e da área Ocr aprovada `20261002-152915-078-83a8da96` também são preservados para diagnóstico. A nova evidência completa, não uma repetição parcial, substitui a aprovação antiga. A checagem dos hashes/fontes/APKs aprovou a evidência também no repositório Y:, após transferência dos outputs idênticos; os APKs debug anteriores ficam no backup `artifacts-before-test-review-20261002-153526`.
- Sem atualização de dependências, versão, push ou release nesta revisão. A evidência cobre debug no AVD API 36; não substitui teste de APK release, áudio percebido, legendas incorporadas/formatos de fabricantes ou aparelhos reais.
