# Galeria Android

App de galeria Android nativo, totalmente em Kotlin, com foco em desempenho, navegação fluida e gestão real de mídias e pastas do aparelho.

Versão atual: **0.9.2** (código Android `9002`), com o [organizador circular de álbuns](docs/ORGANIZADOR-CIRCULAR.md). Consulte as [notas da release v0.9.2](docs/RELEASE-0.9.2.md).

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
- ML Kit (modelo local) para reconhecimento de texto em imagens
- Macrobenchmark, Perfetto e Baseline Profile para medição e otimização de startup e rolagem

## Níveis de acesso

O app funciona em dois níveis independentes:

- **Acesso a fotos e vídeos:** pode ser completo ou limitado às mídias escolhidas pelo usuário no Android 14 ou superior. A Galeria exibe e permite visualizar, reproduzir, compartilhar, favoritar, editar e copiar somente as mídias autorizadas. Alterações individuais usam a confirmação protegida do Android quando necessário.
- **Gerenciamento completo de arquivos:** habilita a varredura direta de mídias ocultas ou não indexadas, navegação e criação de pastas em locais arbitrários e operações em lote. Esse nível usa `MANAGE_EXTERNAL_STORAGE` e é solicitado somente quando o usuário inicia um recurso que depende dele.

Sem gerenciamento completo, deixam de funcionar a descoberta de mídias em pastas com `.nomedia`, a criação e navegação arbitrária de pastas e as ações de exclusão ou movimentação em lote. As funções normais da galeria permanecem disponíveis para o conjunto de mídias autorizado.

Na primeira abertura, a Galeria apresenta os dois níveis e recomenda o acesso padrão. O acesso completo só abre a configuração especial do Android após uma escolha explícita do usuário. A decisão pode ser alterada posteriormente na seção `Acesso` das configurações do app.

## Funções já disponíveis

- Leitura automática das mídias internas do aparelho
- Exibição por álbuns e por conteúdo
- Suporte a fotos, vídeos, GIFs, SVGs e imagens RAW
- Reprodução de vídeos com Media3 / ExoPlayer
- Menu próprio para vídeos com abrir em outro app, informações técnicas, cópia, movimentação, ocultação e repetição
- Modo cinema imersivo: barras de notificações e navegação ocultas, com acesso temporário por gesto na borda; retornar ao modo normal restaura as barras do sistema.
- Menu próprio para imagens com renomear, abrir em outro app, informações EXIF, mapa quando houver GPS e apresentação automática
- Integração com o seletor "Abrir com" do Android para abrir imagens, GIFs, formatos RAW e vídeos recebidos de outros aplicativos
- Miniaturas reais para vídeos na grade de mídias e nas capas de álbuns
- Player de vídeo em tela cheia com HUD superior e inferior translúcida sobreposta à mídia
- Controle de áudio no player para ativar ou silenciar o vídeo durante a navegação
- Velocidade por toque curto (1× → 1,5× → 2× → 1×); segurar abre o seletor completo, incluindo 0,5×
- Modo cinema opcional por vídeo, com rotação horizontal, transição suave e retorno imediato ao modo normal
- Gestos verticais de brilho e volume no modo cinema, além da escolha de trilhas de áudio e legendas incorporadas
- Preferência de modo cinema e de trilhas aplicável a todos os vídeos de um álbum
- Barra de ações unificada com ícones mais delicados e favoritos representados por coração em todas as telas
- Excluir em posição fixa à direita para fotos e vídeos, sem deslocamento quando OCR ou Motion Photo ficam disponíveis
- Barra inferior com Ordenar, Organizar, Ocultos e Configurações na página inicial; dentro dos álbuns, Aleatório substitui Ocultos e inicia a reprodução das mídias da pasta. Filtrar fica no menu de três pontos. A seleção de itens utiliza a barra de ações própria.
- Favorito por coração no topo do visualizador, próximo ao menu de opções
- Fotos em movimento JPEG/HEIC com vídeo MP4 incorporado: detecção ao abrir e reprodução sob demanda, sem alterar o original
- Reconhecimento de texto em fotos, capturas de tela e documentos fotografados, com seleção diretamente sobre a imagem, alças de ajuste, cópia do trecho escolhido e seleção integral
- Ícone de texto exibido automaticamente quando a foto aberta contém texto reconhecido; análise local adiada durante a troca rápida de fotos
- Barra simples de reprodução no visualizador de vídeo e em Motion Photos; um ícone ao lado abre/fecha a linha do tempo sob demanda
- Linha do tempo horizontal contínua com miniaturas por segundo em vídeos, Motion Photos e na prévia de corte; deslizar move a faixa sob o marcador central e atualiza o vídeo durante o gesto
- A faixa cresce com a duração do vídeo e carrega somente as miniaturas do trecho visível; a barra simples permite saltar por toda a duração e a faixa permite ajuste fino sem toque prolongado
- Miniaturas da linha do tempo extraídas progressivamente, com uma extração por vez, cache de memória de 12 MB e cache privado em disco limitado
- O trecho atual da linha do tempo é preparado em segundo plano enquanto o vídeo reproduz, mesmo com a faixa fechada; a abertura revela miniaturas prontas com uma transição curta, e a preparação é suspensa ao sair do app
- Configurações essenciais em primeiro plano; demais opções preservadas em seção expansível
- Navegação fluida entre mídias por gestos horizontais e verticais
- Arrastes curtos trocam a mídia, com proteção para toques simples e duplos
- Pré-carregamento das cinco mídias anteriores e posteriores em alta qualidade
- Reprodução aleatória dentro do álbum
- Favoritar, compartilhar, ocultar, copiar, mover e excluir arquivos
- Movimentação entre álbuns pelo caminho real da pasta, incluindo destinos em `DCIM`, `Pictures`, `Movies` e pastas de aplicativos
- Criação de novas pastas por submenus internos, com escolha entre armazenamento interno e cartões SD montados e navegação pelo local de destino
- Reordenação personalizada de mídias por arrastar e soltar
- Seleção múltipla de mídias e álbuns
- Barra inferior de seleção contínua em toda a largura, com quatro ações igualmente distribuídas e sem recortes laterais
- Toques no modo de seleção apenas marcam ou desmarcam mídias, sem abrir fotos ou reproduzir vídeos
- Filtro de mídia dentro dos álbuns
- Barra única de pesquisa no álbum, identificada por formato, lupa e texto com o nome da pasta
- Agrupamento por tipo, extensão e data
- Modo de visualização em grade e lista
- Pinça horizontal dentro dos álbuns para alternar entre 2 e 8 colunas, preservando a posição e a escolha nas próximas aberturas
- Ajuste global de espaçamento da grade
- Área de itens ocultos com gerenciamento de exibição/ocultação de pastas
- Confirmação de ocultos centralizada em barra compacta, inteiramente clicável e animada; contagem menor e atualizada durante inclusões, exclusões e movimentos, inclusive em pastas com `.nomedia`, sem recarregar a galeria inteira.
- Caches fragmentados em pastas de hash são classificados antes dos filtros de mídia; após reconhecidos, continuam ocultos mesmo quando crescem, aparecem parcialmente no catálogo ou ganham subpastas. Pastas comuns não são escondidas apenas por terem poucas imagens ou fotos de documentos.
- Olho no gerenciamento de ocultos revela um álbum na página inicial por até 30 minutos, inclusive com o app em segundo plano; ao fechar o app, a revelação acaba. O álbum continua oculto nas coleções agregadas.
- Fixação de pastas no gerenciamento de ocultos para manter pastas importantes no topo e forçar varredura dos ocultos quando necessário
- Submenu de ocultos limitado aos álbuns visíveis ou já exibidos anteriormente; novos álbuns ocultos só são procurados pelo botão `Carregar ocultos`
- Menus e submenus acompanham a cor do tema escolhido, mantendo contraste adequado em temas claros e escuros
- No seletor de tema, o cartão usa o fundo principal da galeria e a prévia mostra a cor secundária real dos submenus, com atualização em tempo real.
- Editor com tema preto e ferramentas por ícones: recorte por cantos/bordas, proporções, espelhamento, giro, filtros e redimensionamento em pixels
- Texto digitado diretamente sobre a imagem e pincel com seis combinações de espessura/tipo e espectro de cores; cada traço mantém seu estilo
- OCR acionável ao segurar uma foto e também dentro do editor; seleção acompanha o zoom e considera a imagem editada, sem alterar o arquivo original
- Exportação de imagem para PDF
- Definir imagem como papel de parede
- Rotação de imagem
- Configurações de tema, comportamento e visualização

## Estrutura importante

- `app/src/main/kotlin/com/galeria/android/MainActivity.kt`
  Tela principal de álbuns e renderização dos estados da interface
- `app/src/main/kotlin/com/galeria/android/MainMediaAccessCoordinator.kt`
  Escolha inicial, permissões de mídia e gerenciamento completo de arquivos
- `app/src/main/kotlin/com/galeria/android/AlbumCatalogController.kt`
  Carregamento, cache, filtros, ordenação e atualização do catálogo principal
- `app/src/main/kotlin/com/galeria/android/AlbumMediaActivity.kt`
  Interface, gestos, pesquisa, seleção e navegação dentro de cada álbum
- `app/src/main/kotlin/com/galeria/android/AlbumMediaCatalogController.kt`
  Cache, carga completa, paginação, ordenação e atualização das mídias do álbum
- `app/src/main/kotlin/com/galeria/android/AlbumSelectionActions.kt`
  Preparação e contagem das ações de compartilhar, favoritar, excluir e mover em lote
- `app/src/main/kotlin/com/galeria/android/DetailActivity.kt`
  Interface, gestos, zoom e player do visualizador
- `app/src/main/kotlin/com/galeria/android/DetailMediaQueueController.kt`
  Fila do visualizador, posição atual, ordem personalizada, modo aleatório e remoção
- `app/src/main/kotlin/com/galeria/android/DetailMediaActions.kt`
  Preparação e execução das ações de compartilhar, favoritar, renomear, excluir, copiar e mover
- `app/src/main/kotlin/com/galeria/android/DetailPlaybackController.kt`
  Ciclo de vida, posição, velocidade, áudio e repetição da reprodução
- `app/src/main/kotlin/com/galeria/android/DetailMetadataRepository.kt`
  Leitura assíncrona de metadados; formatação isolada em `DetailMetadataFormatter.kt`
- `app/src/main/kotlin/com/galeria/android/MediaStoreRepository.kt`
  Carregamento e indexação das mídias
- `app/src/main/kotlin/com/galeria/android/GalleryDatabase.kt`
  Cache local do catálogo, álbuns e ordens personalizadas
- `app/src/main/kotlin/com/galeria/android/MediaScanWorker.kt`
  Varreduras de mídia executadas em segundo plano
- `app/src/main/kotlin/com/galeria/android/MediaActions.kt`
  Operações de mover, copiar, excluir, ocultar e criar pasta
- `app/src/main/res/values/strings.xml`
  Textos, mensagens formatadas, plurais e listas traduzíveis das telas principal e de álbum

## Testes automatizados

A versão 0.9.2 traz a roda com lixeira fixa e até sete destinos configurados pela gaveta inferior, salvamento explícito, confirmação ao sair, zoom reto e player mínimo dentro da prévia. Passaram **250 testes unitários e 148 instrumentados**, sem falhas ou ignorados; lint sem erros (142 avisos). Os testes do organizador cobrem arquivos reais, lixeira, marcações sem escrita, falhas parciais, arrasto, rolagem e rotação. Consulte as [notas da release](docs/RELEASE-0.9.2.md) para a validação completa e o APK assinado.

Os testes locais ficam em `app/src/test/kotlin` e as integrações Android em `app/src/androidTest/kotlin`. Os comandos por área, critérios de entrega e evidências estão no [guia de validação](docs/TESTES-VALIDACAO.md).

A CI valida testes unitários, lint e os APKs de desenvolvimento em pushes e pull requests para `main`. A assinatura e a publicação release seguem o fluxo de entrega abaixo.

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

## Releases

A árvore atual mantém o código e a documentação vigentes. APKs e notas de versões anteriores ficam nas [releases e tags do GitHub](https://github.com/GustavoDevGTI/Galeria-Android/releases), sem cópias de binários no repositório.

O `versionName` acompanha a tag sem o prefixo `v`. O `versionCode` usa `major * 1.000.000 + minor * 1.000 + patch`; a versão 0.9.2 usa o código `9002`.

## APK gerado

Download direto da versão mais recente:

[Baixar Galeria Android - versão 0.9.2](https://github.com/GustavoDevGTI/Galeria-Android/releases/download/v0.9.2/Galeria-Android-versao-0.9.2.apk)

Build padrão do Gradle:

```text
app\build\outputs\apk\release\app-release.apk
```

APK de distribuição publicado somente como anexo no GitHub Releases:

```text
Galeria-Android-versao-0.9.2.apk
```

O APK 0.9.2 usa a mesma chave permanente das versões recentes. Atualize por cima da versão release instalada para preservar as preferências. Se houver conflito com uma instalação debug, não desinstale sem antes preservar os dados e preferências necessários.

### Fluxo obrigatório de entrega

Antes de uma entrega/publicação autorizada:

1. Finalize código e versão; execute `./scripts/run-validation.ps1 -Delivery` e `./scripts/assert-delivery-validation.ps1`. Depois gere/verifique o release assinado. A aprovação automatizada é de debug e não substitui a validação do APK release/aparelhos reais.
2. Gere o APK release assinado e publique-o como anexo no GitHub Releases usando a versão atual no nome, sem adicionar novos binários ao histórico Git.
3. Atualize neste README a validação, o estado do projeto e o link do APK quando a versão mudar.
4. Inclua código, testes e README no commit, faça push e associe o release ao mesmo commit. Registre explicitamente qualquer validação pendente.

A assinatura release usa as variáveis locais `GALERIA_KEYSTORE_FILE`, `GALERIA_STORE_PASSWORD`, `GALERIA_KEY_ALIAS` e `GALERIA_KEY_PASSWORD`. A chave e as credenciais nunca devem ser adicionadas ao Git.

### Testes locais rápidos (Windows)

O fluxo recomendado agora usa PowerShell 7:

```powershell
./scripts/run-validation.ps1 -UnitOnly
./scripts/run-validation.ps1 -Area Ocr
./scripts/run-validation.ps1 -Area Viewer,Playback
```

Lógica é validada sem emulador; as áreas selecionam interface e integrações relevantes. Para fechar a entrega, `-Delivery` sempre exige regressão completa. A checagem invalida evidências depois de mudanças nos fontes/APKs ou de novas falhas. Não publica automaticamente, não usa chaves e não altera proteção de branches.

[Áreas, comandos, evidências e limites do portão de entrega](docs/TESTES-VALIDACAO.md).

O runner anterior continua disponível para diagnóstico específico:

Compile uma vez após alterar código ou testes: `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`.
Com o emulador aberto, execute somente as classes afetadas, reutilizando os mesmos APKs:

```powershell
./scripts/run-android-tests.ps1 -Class 'com.galeria.android.HiddenAlbumDialogInstrumentedTest,com.galeria.android.ImageMenuInstrumentedTest' -Repeat 3
```

O script instala os APKs uma vez, imprime apenas o resultado e o primeiro erro de cada execução e retorna código diferente de zero se algum teste falhar. Use `-SkipInstall` para repetições sem recompilação nem reinstalação; use `-All` para a suíte instrumentada completa. A sequência de duas classes acima também detecta interferência de estado entre testes. Antes de entregar uma alteração, execute testes unitários, lint e a suíte de interface completa; os testes direcionados servem para o ciclo de diagnóstico, não substituem a validação final.

Se o Java no Windows falhar com `Unable to establish loopback connection` antes de executar os testes unitários, use `./gradlew.bat -I scripts/direct-unit-tests.gradle :app:directUnitTests`. Essa alternativa executa as mesmas classes JUnit com o mesmo classpath sem o protocolo de comunicação do worker de testes; falhas de asserção continuam interrompendo a validação. O CI permanece usando `testDebugUnitTest`, com seus relatórios padrão.

## Estado atual

- Código principal migrado para Kotlin
- Grade baseada em RecyclerView
- Grade de pastas com espaçamento vertical compacto e nomes preservados em até duas linhas
- Espaçamento entre álbuns ajustável pelo menu de três pontos, com prévia imediata e preferência salva separadamente da grade de mídias
- Barra do visualizador com cinco posições iguais: fotos usam Editar, Texto, Informações, Compartilhar e Excluir; vídeos usam Editar, Som, Cinema, Compartilhar e Excluir. Motion Photo fica sobre a imagem, sem deslocar os atalhos
- Cache local com Room, carregamento progressivo com Paging 3 e varreduras com WorkManager
- Abertura da tela principal baseada em resumos de álbuns calculados pelo Room, sem carregar milhares de mídias na memória
- Catálogo invalidado pela versão do MediaStore, evitando varreduras completas quando nada mudou
- WorkManager inicializado sob demanda, sem custo de varredura ou inicialização pesada a cada abertura do app
- Varreduras de manutenção são adiadas e canceladas ao entrar em um álbum; atualizações manuais continuam imediatas
- Paging mantido para a coleção geral; dentro dos álbuns, os metadados leves vêm diretamente do Room para permitir navegação aleatória sem placeholders
- Fast scroll lateral permanece visível em álbuns extensos, consolida movimentos intermediários e usa prévias leves para priorizar rapidamente a região escolhida
- Entregas do Paging e atualizações de álbuns são adiadas durante gestos e aplicadas quando a grade fica ociosa
- Adapters usam IDs estáveis, `DiffUtil` e payloads para evitar redesenhos completos em seleção e atualização de capas
- Miniaturas decodificadas no tamanho real da grade, com chaves de cache separadas por resolução
- Carregamentos duplicados do primeiro `onResume` removidos das telas de álbuns e mídias
- Imagens e thumbnails carregados pelo Coil 3, sem transição de baixa para alta resolução
- Capas automáticas de álbuns seguem a primeira mídia da ordenação interna; uma capa escolhida manualmente pode ser removida pelo menu para voltar ao comportamento automático
- Miniaturas de vídeos com abertura praticamente preta procuram um quadro posterior estável; vídeos que começam com imagem visível mantêm o quadro inicial
- Informações de mídia agrupadas por arquivo, características técnicas e localização, com nome e data no início e rótulos em negrito
- Preview de tela e imagem nativa usam chaves separadas; a imagem visível só é substituída quando a resolução final está pronta
- Pré-carregamento do visualizador é limitado a duas decodificações simultâneas e cancela itens fora da janela de cinco mídias
- Baseline Profile com os fluxos de abertura, lista de álbuns e grade de mídias incluído nas builds
- Visualização de imagens grandes com zoom dedicado e carregamento em alta qualidade
- Gestos usam coordenadas absolutas e um único controlador, eliminando a tremedeira durante a transição
- Pinça horizontal ajusta a grade interna entre 2 e 8 colunas, salva a preferência, mantém a mídia sob os dedos como referência e usa uma transição curta de escala e opacidade
- Diálogos e submenus principais usam componentes temáticos próprios do app
- Submenus de escolha aplicam a opção imediatamente, sem confirmação redundante; escolhas de acesso inicial ficam alinhadas lado a lado
- Gerenciamento de ocultos atualizado com lista rolável, botões fixos e suporte a pastas fixadas
- Coleções virtuais de Recentes e Favoritos agregam mídias sem duplicar os arquivos originais
- Recentes e Favoritos não revelam mídias de pastas ocultas; Favoritos só aparece quando contém pelo menos uma mídia
- Lixeira com restauração e exclusão definitiva usa o MediaStore no Android 11+ e armazenamento de recuperação próprio nas versões anteriores
- Itens excluídos de pastas ocultas ficam ocultos também na Lixeira, com alternância explícita no menu da Lixeira e no menu principal quando não houver itens visíveis
- Recentes fica sempre em primeiro, seguido dos álbuns pinados pelo usuário. Câmera, Favoritos, Downloads e a pasta ativa de Capturas de tela aparecem depois, ordenados pela atividade mais recente; os demais mantêm a ordenação escolhida.
- Ao favoritar uma mídia, Favoritos ganha destaque pela data da adição, mesmo se a foto for antiga. Entre pastas de capturas duplicadas, só a que contém as capturas mais recentes recebe destaque automático; a pasta legada continua como álbum comum, sem exclusão ou movimentação de arquivos. A identificação prioriza a data de captura disponível no MediaStore, com fallback às datas disponíveis quando ausente.
- Álbuns podem ser fixados ou desafixados pela seleção; favoritar continua sendo uma ação sobre mídias individuais.
- Seleção contínua por arraste mantém a atualização por puxar desativada até o dedo sair da tela; cada mídia selecionada tem seu próprio botão de visualização sem limpar a seleção
- A ordenação usa setas na opção ativa: o primeiro toque escolhe a ordem crescente e um novo toque inverte a direção
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
