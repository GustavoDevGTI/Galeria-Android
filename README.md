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
- Filtro de mídia dentro dos álbuns
- Agrupamento por tipo, extensão e data
- Modo de visualização em grade e lista
- Ajuste global de espaçamento da grade
- Área de itens ocultos com gerenciamento de exibição/ocultação de pastas
- Fixação de pastas no gerenciamento de ocultos para manter pastas importantes no topo e forçar varredura dos ocultos quando necessário
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

A suíte atual possui 16 testes:

- 11 testes unitários locais para filtros de mídia, identificação de pastas ocultas, ordenação de álbuns e regras de gestos
- 3 testes instrumentados do Room para resumos de álbuns, isolamento dos catálogos e paginação com ordem personalizada
- 1 teste instrumentado de abertura da tela principal e disponibilidade da pesquisa
- 1 teste instrumentado que cria uma mídia, move para outro álbum e confirma o caminho final no MediaStore

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

### Validação da versão 0.8

- 11 testes unitários e 5 testes instrumentados aprovados
- Lint, builds `debug` e `benchmark` aprovados
- Macrobenchmark de um swipe aprovado com captura Perfetto
- Nenhum `MediaScanWorker` ou `serviceBind` executado durante o gesto medido
- Baseline Profile com 8.409 regras incluído no APK

O próximo critério de decisão é a validação em aparelho físico com bibliotecas reais. Caso Room, Paging 3, WorkManager ou o pipeline atual de imagens ainda causem perda perceptível de fluidez, o histórico do Git permite comparar com a versão anterior e reduzir o uso de bibliotecas de forma seletiva, preservando apenas as que entregarem benefício comprovado.

## APK gerado

Download direto da versão mais recente:

[Baixar Galeria Android - versão 0.8](https://github.com/GustavoDevGTI/Galeria-Android/raw/main/Galeria-Android-versao-0.8.apk)

Build padrão do Gradle:

```text
app\build\outputs\apk\debug\app-debug.apk
```

APK versionado mantido na raiz do projeto e versionado no GitHub:

```text
Galeria-Android-versao-0.8.apk
```

Sempre que o repositório for atualizado com uma nova versão testável, gere um novo APK com a versão no nome, atualize o link acima e inclua esse APK no commit/push.

## Estado atual

- Código principal migrado para Kotlin
- Grade baseada em RecyclerView
- Cache local com Room, carregamento progressivo com Paging 3 e varreduras com WorkManager
- Abertura da tela principal baseada em resumos de álbuns calculados pelo Room, sem carregar milhares de mídias na memória
- Catálogo invalidado pela versão do MediaStore, evitando varreduras completas quando nada mudou
- WorkManager inicializado sob demanda, sem custo de varredura ou inicialização pesada a cada abertura do app
- Varreduras de manutenção são adiadas e canceladas ao entrar em um álbum; atualizações manuais continuam imediatas
- Paging ajustado para lotes menores e pré-carregamento próximo à área visível do álbum
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
- Diálogos e submenus principais usam componentes temáticos próprios do app
- Gerenciamento de ocultos atualizado com lista rolável, botões fixos e suporte a pastas fixadas
- Player ajustado para manter o vídeo ocupando o máximo possível da tela sem ser reduzido pelos controles
- Player com controle de som integrado à barra inferior e estado preservado ao navegar entre vídeos
- Operações de mover e copiar usam o caminho real do álbum selecionado, com MediaStore e fallback direto para mídias ocultas
- Geração de thumbnails de vídeo corrigida com fallback para MediaMetadataRetriever
- App em evolução com foco em desempenho para bibliotecas grandes

## Próximos passos naturais

- Medir o desempenho em aparelhos com bibliotecas de mídia grandes
- Expandir o editor de imagem
- Evoluir organização personalizada de álbuns e mídias
