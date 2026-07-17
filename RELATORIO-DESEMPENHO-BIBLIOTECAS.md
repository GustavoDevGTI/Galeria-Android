# Relatorio de desempenho: antes e depois das bibliotecas

Data do teste: 16/07/2026

## Objetivo

Comparar o comportamento do aplicativo em tres estados do projeto:

1. **Antes das bibliotecas** - commit `ece2e82`, ultima build sem Coil, Room, WorkManager e Paging 3
2. **Primeira integracao** - commit `99caa62`, primeira build com Coil 3, Room, WorkManager, Paging 3 e biblioteca de zoom
3. **Atual otimizada** - codigo atual do workspace, com consultas resumidas no Room, varredura adiada, Paging limitado, miniaturas dimensionadas e caches reduzidos

## Ambiente e metodologia

- Emulador: `Galeria_Test_API_35`
- Android: API 35
- Resolucao: 1080 x 1920, densidade 420 dpi
- Build: `debug`, compilada separadamente a partir de cada estado do projeto
- Dataset: mesmo album sintetico com 120 imagens validas em todas as rodadas
- Instalacao limpa e mesmas permissoes antes de cada teste
- Animacoes do sistema desativadas durante o benchmark
- Sete partidas frias por build para calcular a mediana
- Oito gestos identicos de rolagem no album e oito passagens no visualizador
- Memoria coletada por `dumpsys meminfo`
- Frames coletados por `dumpsys gfxinfo`
- Abertura de Activity coletada pelo `ActivityTaskManager`

Cada build foi testada isoladamente. Nao houve reaproveitamento do banco, cache ou instalacao da build anterior.

## Resultados

| Metrica | Antes das bibliotecas | Primeira integracao | Atual otimizada |
|---|---:|---:|---:|
| Primeira abertura apos instalacao | 3.978 ms | 7.820 ms | 5.792 ms |
| Partida fria, mediana de 7 execucoes | 3.107 ms | 5.076 ms | 3.288 ms |
| Partida fria, media de 7 execucoes | 3.131 ms | 4.833 ms | 3.257 ms |
| Faixa observada nas 7 partidas | 2.467-3.902 ms | 4.045-5.299 ms | 2.528-4.017 ms |
| Memoria na tela principal | 55,7 MiB | 91,4 MiB | 100,0 MiB |
| Abertura do album | 833 ms | 1.250 ms | 1.036 ms |
| Memoria dentro do album | 110,7 MiB | 129,7 MiB | 135,7 MiB |
| Frames lentos no primeiro scroll | 88,18% | 87,96% | 98,21% |
| Percentil 90 do primeiro scroll | 150 ms | 150 ms | 550 ms |
| Memoria apos navegar no visualizador | 133,9 MiB | 145,3 MiB | 103,6 MiB |
| Frames lentos na passagem de midias | 80,95% | 83,87% | 98,15% |
| Percentil 90 na passagem de midias | 89 ms | 89 ms | 117 ms |
| Tamanho do APK de teste | 4,88 MiB | 8,86 MiB | 9,01 MiB |
| Crash ou ANR durante o roteiro | Nenhum | Nenhum | Nenhum |

### Partidas frias individuais

- Antes das bibliotecas: `3518, 2882, 3107, 3902, 3518, 2467, 2524 ms`
- Primeira integracao: `5204, 4563, 4045, 4454, 5076, 5188, 5299 ms`
- Atual otimizada: `3288, 4017, 3059, 3781, 3420, 2704, 2528 ms`

## Comparacao direta

### Primeira integracao contra a build antiga

- A primeira abertura ficou **96,6% mais lenta**
- A mediana das partidas frias ficou **63,4% mais lenta**
- A memoria da tela principal aumentou **64,2%**
- A abertura do album ficou **50,1% mais lenta**
- A memoria do visualizador aumentou **8,6%**
- O APK aumentou **81,6%**
- A rolagem do album permaneceu praticamente igual, variando apenas `-0,22` ponto percentual de frames lentos
- A passagem de midias piorou `2,92` pontos percentuais

Conclusao deste marco: a primeira integracao trouxe uma base mais escalavel, mas foi uma regressao de desempenho perceptivel. As bibliotecas foram adicionadas antes de o fluxo de inicializacao, as consultas e os limites de cache estarem devidamente ajustados.

### Build atual contra a primeira integracao

- A primeira abertura melhorou **25,9%**
- A mediana das partidas frias melhorou **35,2%**
- A abertura do album melhorou **17,1%**
- A memoria do visualizador caiu **28,7%**
- O tamanho do APK aumentou apenas **1,6%**
- A memoria da tela principal ainda aumentou **9,3%** nesta rodada
- O primeiro scroll piorou `10,25` pontos percentuais
- A passagem de midias piorou `14,28` pontos percentuais

Conclusao deste marco: as otimizacoes recuperaram quase todo o tempo das partidas repetidas e reduziram bastante a memoria depois de navegar pelas imagens. Entretanto, o custo foi deslocado para o carregamento e a renderizacao durante os gestos.

### Build atual otimizada contra a build antiga sem bibliotecas

- A primeira abertura ficou **45,6% mais lenta**
- A mediana das partidas frias ficou **5,8% mais lenta**
- A media das partidas frias ficou **4,0% mais lenta**
- A memoria da tela principal aumentou **79,5%**
- A abertura do album ficou **24,4% mais lenta**
- A memoria dentro do album aumentou **22,6%**
- A memoria apos navegar no visualizador caiu **22,6%**
- O primeiro scroll piorou `10,03` pontos percentuais de frames lentos
- O percentil 90 do primeiro scroll aumentou **266,7%**, de `150 ms` para `550 ms`
- A passagem de midias piorou `17,20` pontos percentuais de frames lentos
- O percentil 90 da passagem aumentou **31,5%**, de `89 ms` para `117 ms`
- O APK aumentou **84,6%**, de `4,88 MiB` para `9,01 MiB`
- Nenhuma das duas builds apresentou crash ou ANR durante o roteiro

Conclusao deste comparativo: a versao atual com bibliotecas otimizadas praticamente recuperou o desempenho das partidas frias repetidas e reduziu de forma relevante a memoria do visualizador. Em contrapartida, ainda apresenta regressao na primeira abertura, na abertura de albuns, no consumo de memoria das telas de grade e, principalmente, na fluidez do primeiro scroll e da passagem entre midias. A nova arquitetura entrega uma base mais escalavel e recursos de cache, paginacao e pre-carregamento, mas ainda nao supera a versao sem bibliotecas em fluidez geral no dataset testado.

## Testes automatizados da build atual

Foram executados com sucesso:

- 11 testes unitarios: regras de albuns, filtros de midia e classificacao de gestos
- 5 testes instrumentados: operacoes do Room, abertura basica da tela principal e movimentacao real entre albuns pelo MediaStore
- Android Lint para a variante `debug`
- Compilacao completa do APK

Resultado: **16 testes aprovados, zero falhas, zero erros, lint aprovado e build aprovada**.

As builds historicas nao possuiam essa suite. Para elas foram executados compilacao e o mesmo roteiro funcional externo, sem crashes ou ANRs.

## Interpretacao tecnica

As bibliotecas nao sao, isoladamente, o problema. Elas resolveram necessidades reais:

- Room fornece persistencia e uma base adequada para organizacao e consultas incrementais
- Paging 3 impede que toda a lista de midias precise permanecer materializada ao mesmo tempo
- Coil melhora cache, decodificacao e suporte a formatos
- WorkManager permite tirar varreduras pesadas do fluxo interativo
- A biblioteca de zoom evita manter uma implementacao manual complexa

O problema atual esta na integracao entre esses componentes durante o gesto. O benchmark indica trabalho excessivo na thread de interface enquanto novas paginas, miniaturas ou imagens de alta resolucao sao entregues. O resultado coincide com o stuttering observado visualmente.

O Paging tambem ainda nao demonstrou sua principal vantagem neste teste de 120 itens. Sua vantagem tende a aparecer em albuns com milhares de arquivos, mas isso nao justifica frames de 150 a 550 ms durante a rolagem.

## Conclusao

**A primeira versao com bibliotecas foi pior que a versao anterior em fluidez, startup, memoria inicial e tamanho. A versao atual corrigiu boa parte do startup e melhorou de forma relevante a memoria do visualizador, mas ainda esta pior na rolagem e na passagem entre midias.**

Portanto, nao recomendo remover Room, Paging, Coil ou WorkManager e voltar para a arquitetura antiga. A base nova e mais apropriada para uma galeria grande, mas a implementacao ainda precisa de uma rodada especifica de profiling e correcao do pipeline visual antes de ser considerada mais fluida que a build antiga.

## Rodada de profiling e correcoes

Os cinco itens tecnicos recomendados foram implementados:

1. Modulo `benchmark` criado com testes de partida fria, rolagem de album e captura de um swipe
2. Baseline Profile gerado com 8.409 regras cobrindo abertura, lista de albuns e grade de midias
3. Trace Perfetto capturado e consultado com Trace Processor
4. Atualizacoes do Paging e dos adapters adiadas durante gestos, com `DiffUtil`, IDs estaveis e payloads no lugar de invalidacoes completas
5. Preview e imagem nativa separados no visualizador, com troca atomica apenas depois da carga final
6. Decodificacoes adjacentes limitadas a duas requisicoes simultaneas e canceladas fora da janela de cinco itens

O primeiro trace dentro do album encontrou `RV Prefetch` de `70,99 ms`, criacao de celula de `19,48 ms`, bind de `19,29 ms` e inicializacao do WorkManager durante o gesto. O trace final nao registrou `MediaScanWorker` nem `serviceBind` entre `ACTION_DOWN` e `ACTION_UP`. Nesse gesto, os prefetches ficaram em ate `3,77 ms`, os binds em ate `0,93 ms` e somente dois blocos de scroll ultrapassaram um frame: `15,38 ms` e `23,58 ms`.

O profiling tambem revelou uma falha de lifecycle: fechar o album enquanto uma consulta do WorkManager aguardava resultado lancava `InterruptedException` e encerrava o processo. O encerramento agora e cooperativo e foi validado por uma nova execucao do Macrobenchmark.

As metricas globais de frames ainda oscilaram fortemente no emulador, pois GPU emulada, JIT e servicos do sistema dominaram algumas iteracoes. Por isso, os tempos absolutos nao devem ser tratados como resultado final. A evidencia confiavel desta rodada e a remocao do WorkManager durante o gesto e a reducao dos blocos de criacao, bind e prefetch na thread principal.

## Proximas acoes recomendadas

1. Testar em aparelho fisico com albuns de 500, 2.000 e 8.000 itens
2. Repetir cada Macrobenchmark em pelo menos cinco iteracoes no aparelho fisico
3. Manter os traces como criterio de regressao antes de novas mudancas no pipeline visual

## Limitacoes do relatorio

- Os numeros foram coletados em emulador e build `debug`; servem principalmente para comparacao relativa
- `gfxinfo` considera frame acima do prazo como lento e sofre influencia do desempenho do host
- O dataset usa 120 copias de uma imagem valida para manter o custo reproduzivel; formatos e resolucoes variados devem ser avaliados em uma segunda bateria
- A memoria varia conforme coleta de lixo e estado dos caches; por isso os valores devem ser lidos como tendencia, nao como limite absoluto
