# Destinos de Mover/Copiar com revelação temporária

## Diagnóstico

Na 0.8.66, `AlbumSelectionActions.loadMoveTargets` e `DetailMediaActions.loadTargets` chamavam `loadAlbums(includeHidden = true)` quando a tela incluía acesso ao filesystem oculto. Essa chamada usava `queryOverviewMedia` com `showNaturallyHidden = true`: lia a lista completa de álbuns conhecidos no catálogo oculto e reconciliava cada diretório antes de filtrar a lista exposta.

Assim, abrir Mover podia reler a origem e outros ocultos sem relação com a operação. Vídeos novos/alterados nessas pastas podiam ainda exigir extração de duração. O problema foi identificado pelo caminho do código; não foi medido na biblioteca do telefone do usuário.

## Correção delimitada

- Novo caminho compartilhado `MediaStoreRepository.loadTargetAlbums`, usado pelos dois seletores (incluindo Copiar no visualizador).
- Reutiliza uma consulta ao MediaStore para os álbuns indexados. Entre as chaves expostas, reconcilia apenas destinos físicos ausentes do índice ou naturalmente ocultos/com `.nomedia`.
- Não enumera o catálogo completo de ocultos e não relê pelo filesystem a pasta de origem/excluída, álbuns virtuais ou pastas não expostas.
- Mantém a classificação automática sobre a base indexada completa, para não perder evidências de pastas de cache fragmentadas por hash.
- Ordenação, exclusões, filtragem de ocultos e seleção continuam usando `AlbumTargetRules`; indicador circular e proteção contra pedidos duplicados permanecem.
- Não modifica arquivos, preferências de ocultação ou o prazo de revelação temporária. Não introduz cache de destinos desatualizado: um destino oculto solicitado é consultado novamente.

## Teste de regressão

O novo caso instrumentado cria origem oculta com vídeo, destino oculto exposto, destino indexado e pasta oculta não exposta com vídeo. Armazena um catálogo completo e depois adiciona outro arquivo ao destino. Exercita ambos os seletores, exige a mesma ordem exposta e a nova contagem, e verifica pelo cache de extração de duração que os vídeos da origem/não exposto não foram abertos. Também exige que a consulta sem acesso ao filesystem não inclua o destino não indexado.

Os arquivos mínimos usados nesse teste são sondas de leitura de metadados, não amostras para validar reprodução/codec. Não substituem uma medição de desempenho no telefone com muitos arquivos.

## Validação concluída

- Compilação debug e APK de testes aprovados; 229/229 unitários, sem ignorados.
- Lint: zero erros, 129 avisos; 45 contratos das ferramentas de validação aprovados.
- 49/49 testes das áreas Albums e Catalog no emulador Android 16/API 36, incluindo o novo caso nos dois seletores; zero falhas/ignorados, 91 segundos de instrumentação.
- Mais 3/3 testes de indicador de carregamento e rejeição de pedidos duplicados, sobre os mesmos APKs, sem reinstalação ou alteração de fontes entre execuções.
- Evidências: `app/build/reports/validation/20261013-093041-290-f41b93ea/result.json` e subpasta `loading-feedback`.

Sem incremento de versão, commit, push ou release nesta etapa. Aprovação por áreas, não uma nova aprovação de entrega: antes de publicar, definir a versão final e executar novamente `run-validation.ps1 -Delivery` (inventário atual: 115 instrumentados). A aprovação completa da 0.8.66 não cobre automaticamente estas alterações.

## Preparação da publicação autorizada

Após autorização, versão/código passaram para `0.8.67`/`8067`. A regressão completa final `20261013-100723-332-a870e465` aprovou os 229 unitários e 115 instrumentados sem falhas/ignorados; lint sem erros (129 avisos), 45 contratos das ferramentas aprovados. As tentativas rejeitadas, ajustes de sincronização dos testes e o watchdog do Android emulado estão registrados nas [notas da 0.8.67](RELEASE-0.8.67.md). Nenhuma regra de seleção/timeout do app foi alterada para acomodar esses testes.
