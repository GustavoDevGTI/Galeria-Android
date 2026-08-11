# Galeria Android

App de galeria Android nativo, totalmente em Kotlin, com foco em desempenho, navegação fluida e gestão real de mídias e pastas do aparelho.

## Stack atual

- Kotlin nativo
- Android SDK 37
- RecyclerView para grades e listas
- Coil 3 para thumbnails, imagens em alta qualidade e pré-carregamento
- ZoomImage para zoom fluido em imagens grandes
- Room para cache local do catálogo e da organização
- Paging 3 para carregamento progressivo das grades
- WorkManager para varreduras pesadas em segundo plano
- MediaStore para leitura de mídias do aparelho
- AndroidX Media3 / ExoPlayer para reprodução de vídeo
- Macrobenchmark, Perfetto e Baseline Profile para medição e otimização de startup e rolagem

## Funções já disponíveis

- Leitura automática das mídias internas do aparelho
- Exibição por álbuns e por conteúdo
- Suporte a fotos, vídeos, GIFs, SVGs e imagens RAW
- Reprodução de vídeos com Media3 / ExoPlayer
- Menu próprio para vídeos com abrir em outro app, informações técnicas, cópia, movimentação, ocultação e repetição
- Menu próprio para imagens com renomear, abrir em outro app, informações EXIF, mapa quando houver GPS e apresentação automática
- Integração com o seletor "Abrir com" do Android para abrir imagens, GIFs, formatos RAW e vídeos recebidos de outros aplicativos
- Miniaturas reais para vídeos na grade de mídias e nas capas de álbuns
- Player de vídeo em tela cheia com HUD superior e inferior translúcida sobreposta à mídia
- Controle de áudio no player para ativar ou silenciar o vídeo durante a navegação
- Navegação fluida entre mídias por gestos horizontais e verticais
- Arrastes curtos trocam a mídia, com proteção para toques simples e duplos
- Pré-carregamento das cinco mídias anteriores e posteriores em alta qualidade
- Reprodução aleatória dentro do álbum
- Favoritar, compartilhar, ocultar, copiar, mover e excluir arquivos
- Movimentação entre álbuns pelo caminho real da pasta, incluindo destinos em `DCIM`, `Pictures`, `Movies` e pastas de aplicativos
- Criação de novas pastas
- Reordenação personalizada de mídias por arrastar e soltar
- Seleção múltipla de mídias e álbuns
- Toques no modo de seleção apenas marcam ou desmarcam mídias, sem abrir fotos ou reproduzir vídeos
- Filtro de mídia dentro dos álbuns
- Barra única de pesquisa no álbum, identificada por formato, lupa e texto com o nome da pasta
- Agrupamento por tipo, extensão e data
- Modo de visualização em grade e lista
- Pinça horizontal dentro dos álbuns para alternar entre 2 e 8 colunas, preservando a posição e a escolha nas próximas aberturas
- Ajuste global de espaçamento da grade
- Área de itens ocultos com gerenciamento de exibição/ocultação de pastas
- Fixação de pastas no gerenciamento de ocultos para manter pastas importantes no topo e forçar varredura dos ocultos quando necessário
- Submenu de ocultos limitado aos álbuns visíveis ou já exibidos anteriormente; novos álbuns ocultos só são procurados pelo botão `Carregar ocultos`
- Submenus padronizados com o tema escolhido, mantendo contraste adequado em temas claros e escuros
- Editor básico de imagem com recorte e pincel
- Exportação de imagem para PDF
- Definir imagem como papel de parede
- Rotação de imagem
- Configurações de tema, comportamento e visualização

## Estrutura importante

- `app/src/main/kotlin/com/galeria/android/MainActivity.kt`
  Tela principal de álbuns
- `app/src/main/kotlin/com/galeria/android/AlbumMediaActivity.kt`
  Conteúdo interno de cada álbum
- `app/src/main/kotlin/com/galeria/android/DetailActivity.kt`
  Visualizador de foto e player de vídeo
- `app/src/main/kotlin/com/galeria/android/MediaStoreRepository.kt`
  Carregamento e indexação das mídias
- `app/src/main/kotlin/com/galeria/android/GalleryDatabase.kt`
  Cache local do catálogo, álbuns e ordens personalizadas
- `app/src/main/kotlin/com/galeria/android/MediaScanWorker.kt`
  Varreduras de mídia executadas em segundo plano
- `app/src/main/kotlin/com/galeria/android/MediaActions.kt`
  Operações de mover, copiar, excluir, ocultar e criar pasta

## Testes automatizados

A suíte atual possui 40 testes:

- 26 testes unitários locais para filtros de mídia, abertura externa, identificação e histórico de pastas ocultas, colunas da grade, identidade de URIs do MediaStore, ordenação de álbuns, regras de gestos, estado e menus do visualizador
- 3 testes instrumentados do Room para resumos de álbuns, isolamento dos catálogos e paginação com ordem personalizada
- 1 teste instrumentado de abertura da tela principal e disponibilidade da pesquisa
- 1 teste instrumentado que cria uma mídia, move para outro álbum e confirma o caminho final no MediaStore
- 1 teste instrumentado que preserva a mídia e o fluxo aleatório após rotação
- 1 teste instrumentado que garante título e pesquisa na mesma barra do álbum
- 1 teste instrumentado que valida as ações e a repetição no menu de vídeo
- 1 teste instrumentado que valida as ações, informações, renomeação e apresentação no menu de imagem
- 1 teste instrumentado que confirma a Galeria no seletor "Abrir com" e abre uma URI externa
- 1 teste instrumentado que garante seleção múltipla sem abrir ou reproduzir a mídia tocada
- 1 teste instrumentado que garante que o submenu não carregue álbuns ocultos nunca exibidos antes da ação `Carregar ocultos`
- 1 teste instrumentado que executa a pinça horizontal nas duas direções e confirma a alteração do número de colunas
- 1 teste instrumentado que arrasta o fast scroll lateral em um álbum com 260 mídias e confirma o salto até o último item

Executar apenas os testes unitários rápidos:

```text
gradlew.bat :app:testDebugUnitTest
```

Executar os testes instrumentados com um emulador conectado:

```text
gradlew.bat :app:connectedDebugAndroidTest
```

Executar a validação completa antes de gerar um APK:

```text
gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
```

Os testes locais ficam em `app/src/test/kotlin` e os testes executados no Android ficam em `app/src/androidTest/kotlin`.

### Testes de desempenho

O módulo `benchmark` mede a partida fria e a rolagem dentro de um álbum usando a variante `benchmark`, sem o custo da instrumentação `debug`.

Executar todos os macrobenchmarks:

```text
gradlew.bat :benchmark:connectedBenchmarkAndroidTest
```

Capturar somente um swipe com trace Perfetto:

```text
gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.galeria.android.benchmark.GalleryMacrobenchmark#singleSwipePerfetto"
```

Regenerar o Baseline Profile após alterar os fluxos principais:

```text
gradlew.bat :benchmark:connectedBenchmarkAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.galeria.android.benchmark.BaselineProfileGenerator#generate"
```

O perfil gerado e incluído no APK fica em `app/src/main/baseline-prof.txt`. Medições finais devem ser feitas em aparelho físico; o emulador é útil para detectar regressões e inspecionar traces, mas não representa o desempenho absoluto do celular.

### Validação da versão 0.8.33

- 26 testes unitários aprovados e 14 testes instrumentados validados
- Teste de interface aprovado para exibir e arrastar o fast scroll lateral otimizado em um álbum com 260 mídias até o último item
- Atualizações intermediárias do fast scroll consolidadas após 72 ms e miniaturas de prévia limitadas a 128 px, priorizando a região final escolhida
- Teste de gesto aprovado para reduzir e aumentar as colunas da grade com uma pinça horizontal real, incluindo o término correto da nova animação
- Testes de menus aprovados após isolar cada `PopupWindow` e executar a ação somente depois da confirmação de fechamento
- Teste de regressão aprovado para reconhecer como a mesma mídia as URIs equivalentes das coleções `Images` e `Files` do MediaStore
- Teste de regressão aprovado para listar inicialmente apenas álbuns visíveis ou já exibidos no submenu de ocultos
- Teste de regressão aprovado para manter a grade no modo de seleção e permitir escolher várias mídias para mover
- Teste de integração aprovado para registrar e abrir imagens, GIFs e vídeos enviados por outros aplicativos
- Teste de interface aprovado para o menu de imagem, incluindo informações, renomeação e avanço da apresentação
- Teste de regressão aprovado para separar as ações de vídeo das ferramentas exclusivas de imagem
- Teste de interface aprovado para título e pesquisa compartilharem a mesma barra do álbum
- Teste de regressão aprovado para preservar a mídia e o fluxo aleatório após rotação da tela
- Lint e builds `debug`, `release` e `benchmark` aprovados
- Macrobenchmark de um swipe aprovado com captura Perfetto
- Nenhum `MediaScanWorker` ou `serviceBind` executado durante o gesto medido
- Baseline Profile com 8.409 regras incluído no APK

O próximo critério de decisão é a validação em aparelho físico com bibliotecas reais. Caso Room, Paging 3, WorkManager ou o pipeline atual de imagens ainda causem perda perceptível de fluidez, o histórico do Git permite comparar com a versão anterior e reduzir o uso de bibliotecas de forma seletiva, preservando apenas as que entregarem benefício comprovado.

## Histórico da linha 0.8

A linha começou em `v0.8.0`. Cada commit posterior recebe um patch sequencial `v0.8.N`, sem reescrever o histórico. A versão atual é **0.8.33**: são **34 commits** na linha 0.8, ou **33 atualizações** depois do lançamento inicial.

O `versionName` acompanha a tag sem o prefixo `v`. O `versionCode` usa `major * 1.000.000 + minor * 1.000 + patch`; portanto, a versão 0.8.33 usa o código `8033`.

| Versão | Data | Alteração |
| --- | --- | --- |
| [`v0.8.0`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.0) | 17/06/2026 | Lançamento da versão 0.8 |
| [`v0.8.1`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.1) | 17/06/2026 | Otimização de desempenho e transições de vídeo |
| [`v0.8.2`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.2) | 18/06/2026 | Gestos do visualizador e álbuns ocultos |
| [`v0.8.3`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.3) | 19/06/2026 | Miniaturas e atualização de mídias |
| [`v0.8.4`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.4) | 30/06/2026 | Diálogos temáticos e pastas ocultas |
| [`v0.8.5`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.5) | 30/06/2026 | Link para download do APK mais recente |
| [`v0.8.6`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.6) | 30/06/2026 | Download de APK com nome versionado |
| [`v0.8.7`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.7) | 14/07/2026 | Miniaturas de vídeo e controles sobrepostos |
| [`v0.8.8`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.8) | 14/07/2026 | Atualização de pastas ocultas |
| [`v0.8.9`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.9) | 14/07/2026 | Controles do diálogo de pastas ocultas |
| [`v0.8.10`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.10) | 14/07/2026 | Layout do diálogo de pastas ocultas |
| [`v0.8.11`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.11) | 14/07/2026 | Visualizador de mídia e grade do álbum |
| [`v0.8.12`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.12) | 15/07/2026 | Transições suaves no visualizador de imagens |
| [`v0.8.13`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.13) | 15/07/2026 | Pré-carregamento de imagens próximas |
| [`v0.8.14`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.14) | 15/07/2026 | Fluidez do pré-carregamento do visualizador |
| [`v0.8.15`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.15) | 15/07/2026 | Carregamento e gestos da galeria |
| [`v0.8.16`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.16) | 16/07/2026 | Fluidez e benchmarks da versão 0.8 |
| [`v0.8.17`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.17) | 17/07/2026 | Movimentação de mídias e controle de som |
| [`v0.8.18`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.18) | 17/07/2026 | Relatório comparativo de desempenho |
| [`v0.8.19`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.19) | 17/07/2026 | Comparação da build otimizada |
| [`v0.8.20`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.20) | 21/07/2026 | Preservação do fluxo aleatório após rotação |
| [`v0.8.21`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.21) | 21/07/2026 | Controle patch por commit e histórico completo da linha 0.8 |
| [`v0.8.22`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.22) | 21/07/2026 | Título e pesquisa unificados na barra do álbum |
| [`v0.8.23`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.23) | 21/07/2026 | Identificação visual reforçada na pesquisa do álbum |
| [`v0.8.24`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.24) | 21/07/2026 | Menu específico para reprodução de vídeos |
| [`v0.8.25`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.25) | 21/07/2026 | Menu específico para visualização de imagens |
| [`v0.8.26`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.26) | 22/07/2026 | Abertura de imagens e vídeos pelo seletor de aplicativos do Android |
| [`v0.8.27`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.27) | 22/07/2026 | Seleção múltipla sem abrir ou reproduzir a mídia tocada |
| [`v0.8.28`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.28) | 22/07/2026 | Carregamento controlado de álbuns ocultos |
| [`v0.8.29`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.29) | 22/07/2026 | Controle de colunas da grade por pinça horizontal |
| [`v0.8.30`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.30) | 23/07/2026 | Animação fluida na grade e estabilidade dos submenus |
| [`v0.8.31`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.31) | 03/08/2026 | Rolagem completa em álbuns extensos |
| [`v0.8.32`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.32) | 03/08/2026 | Fast scroll lateral e arrastável dentro dos álbuns |
| [`v0.8.33`](https://github.com/GustavoDevGTI/Galeria-Android/tree/v0.8.33) | 11/08/2026 | Atualização mais rápida durante o fast scroll |

## Relatório comparativo de desempenho

O relatório técnico compara três marcos do projeto usando o mesmo ambiente e roteiro de testes:

1. Versão anterior à adoção das bibliotecas
2. Primeira integração com Coil 3, Room, WorkManager, Paging 3 e biblioteca de zoom
3. Versão com as bibliotecas e o pipeline otimizados

[Consultar o relatório completo de desempenho](RELATORIO-DESEMPENHO-BIBLIOTECAS.md)

## APK gerado

Download direto da versão mais recente:

[Baixar Galeria Android - versão 0.8.33](https://github.com/GustavoDevGTI/Galeria-Android/raw/main/Galeria-Android-versao-0.8.33.apk)

Build padrão do Gradle:

```text
app\build\outputs\apk\release\app-release.apk
```

APK versionado mantido na raiz do projeto e versionado no GitHub:

```text
Galeria-Android-versao-0.8.33.apk
```

O APK 0.8.33 usa a chave permanente criada na correção de rotação. Quem instalou um APK 0.8 anterior assinado pela antiga chave de depuração precisa desinstalá-lo uma única vez antes desta instalação. As próximas atualizações assinadas pela nova chave serão compatíveis entre si.

### Fluxo obrigatório de entrega

Sempre que o repositório receber uma atualização testável:

1. Execute testes unitários, instrumentados, lint e build de release.
2. Gere o APK release assinado e copie-o para a raiz usando a versão atual no nome.
3. Atualize neste README a validação, o estado do projeto e o link do APK quando a versão mudar.
4. Inclua código, testes, README e APK no mesmo commit e faça push para o repositório.

A assinatura release usa as variáveis locais `GALERIA_KEYSTORE_FILE`, `GALERIA_STORE_PASSWORD`, `GALERIA_KEY_ALIAS` e `GALERIA_KEY_PASSWORD`. A chave e as credenciais nunca devem ser adicionadas ao Git.

## Estado atual

- Código principal migrado para Kotlin
- Grade baseada em RecyclerView
- Cache local com Room, carregamento progressivo com Paging 3 e varreduras com WorkManager
- Abertura da tela principal baseada em resumos de álbuns calculados pelo Room, sem carregar milhares de mídias na memória
- Catálogo invalidado pela versão do MediaStore, evitando varreduras completas quando nada mudou
- WorkManager inicializado sob demanda, sem custo de varredura ou inicialização pesada a cada abertura do app
- Varreduras de manutenção são adiadas e canceladas ao entrar em um álbum; atualizações manuais continuam imediatas
- Paging ajustado para lotes menores e pré-carregamento próximo à área visível do álbum
- Fast scroll lateral permanece visível em álbuns extensos, consolida movimentos intermediários e usa prévias leves para priorizar rapidamente a região escolhida
- Entregas do Paging e atualizações de álbuns são adiadas durante gestos e aplicadas quando a grade fica ociosa
- Adapters usam IDs estáveis, `DiffUtil` e payloads para evitar redesenhos completos em seleção e atualização de capas
- Miniaturas decodificadas no tamanho real da grade, com chaves de cache separadas por resolução
- Carregamentos duplicados do primeiro `onResume` removidos das telas de álbuns e mídias
- Imagens e thumbnails carregados pelo Coil 3, sem transição de baixa para alta resolução
- Preview de tela e imagem nativa usam chaves separadas; a imagem visível só é substituída quando a resolução final está pronta
- Pré-carregamento do visualizador é limitado a duas decodificações simultâneas e cancela itens fora da janela de cinco mídias
- Baseline Profile com os fluxos de abertura, lista de álbuns e grade de mídias incluído nas builds
- Visualização de imagens grandes com zoom dedicado e carregamento em alta qualidade
- Gestos usam coordenadas absolutas e um único controlador, eliminando a tremedeira durante a transição
- Pinça horizontal ajusta a grade interna entre 2 e 8 colunas, salva a preferência, mantém a mídia sob os dedos como referência e usa uma transição curta de escala e opacidade
- Diálogos e submenus principais usam componentes temáticos próprios do app
- Gerenciamento de ocultos atualizado com lista rolável, botões fixos e suporte a pastas fixadas
- Abertura do submenu de ocultos sem varredura automática: somente álbuns visíveis ou já exibidos entram na lista até o uso de `Carregar ocultos`
- Player ajustado para manter o vídeo ocupando o máximo possível da tela sem ser reduzido pelos controles
- Player com controle de som integrado à barra inferior e estado preservado ao navegar entre vídeos
- Visualização aleatória preserva a mídia atual, a ordem do fluxo, o tempo restante das imagens e a posição do vídeo após girar a tela
- Operações de mover e copiar usam o caminho real do álbum selecionado, com MediaStore e fallback direto para mídias ocultas
- Geração de thumbnails de vídeo corrigida com fallback para MediaMetadataRetriever
- App em evolução com foco em desempenho para bibliotecas grandes

## Próximos passos naturais

- Medir o desempenho em aparelhos com bibliotecas de mídia grandes
- Expandir o editor de imagem
- Evoluir organização personalizada de álbuns e mídias
