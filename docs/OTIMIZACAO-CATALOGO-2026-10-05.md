# Carregamento de álbuns e ocultos — 05/10/2026

## Correções

- O painel de ocultos abre com os álbuns já exibidos, sem esperar consultas aos dois catálogos ou recontagem. Álbuns anteriormente conhecidos são acrescentados depois, sem revelar pastas desconhecidas. Contagens continuam atualizadas em segundo plano, preservando checkbox, pin e revelação temporária; uma recontagem antiga não remove entradas acrescentadas posteriormente.
- A contagem do MediaStore consulta somente caminho/bucket, sem instanciar os metadados completos de cada mídia. Não percorre o filesystem de uma pasta comum quando já dispõe de sua contagem indexada.
- Atualizações pendentes retomam ao recuperar o foco após um submenu. A conclusão de uma varredura não apaga uma notificação de mudança mais recente. A tela de álbum guarda atualizações enquanto pausada e cancela callbacks ao sair; uma Activity antiga não marca o catálogo novamente após ser destruída.
- Ao mostrar um álbum oculto, a tela consulta o diretório solicitado, não todo o armazenamento. Pastas reveladas temporariamente e a pasta física aberta possuem observadores de arquivos, incluindo eventos que não chegam pelo MediaStore em áreas `.nomedia`.
- Um álbum físico desatualizado, novo ou oculto carrega seus arquivos diretamente. No modo paginado, substitui somente as linhas daquele álbum no catálogo; no modo agrupado/lista, prepara a consulta local. Catálogos válidos continuam reaproveitados, inclusive para a rolagem rápida de álbuns grandes. Abrir o destino após mover não espera uma varredura geral.
- A lista principal reconcilia rapidamente a mídia indexada e as pastas ocultas solicitadas. A descoberta geral de ocultos e a manutenção completa permanecem separadas do carregamento urgente. Catálogo legitimamente vazio não agenda manutenção repetida indefinidamente.
- Pedidos de manutenção reutilizam o UUID do trabalho realmente existente. Um pedido repetido não cancela uma varredura em andamento nem fica observando um UUID descartado por `KEEP`. Consultas ao WorkManager são feitas fora da thread da interface; pedidos simultâneos também podem reutilizar uma varredura que terminou enquanto esperavam o bloqueio.
- Durações de vídeos no filesystem são persistidas por caminho, tamanho e modificação, evitando reabrir vídeos inalterados a cada varredura. O cache tem limite de 4096 entradas e não guarda imagens nem muda qualidade ou escolha de thumbnails.
- A validade do catálogo usa versão **e geração por volume** no Android 11+, detectando inserções/exclusões ocorridas enquanto o app estava fechado. Em versões anteriores, mantém também um limite temporal. Referência: [MediaStore.getGeneration](https://developer.android.com/reference/android/provider/MediaStore#getGeneration(android.content.Context,%20java.lang.String)).
- Uma varredura antiga não pode sobrescrever uma atualização mais recente de álbum nem declarar o catálogo inteiro reconciliado. A assinatura de geração registrada corresponde à varredura, e não a uma alteração posterior durante a gravação.

## Preservação

Permanecem: ocultação natural/manual, privacidade de Recentes/Favoritos/Lixeira, revelação de 30 minutos contando em segundo plano e limitada ao processo, ordenação/capas, seleção, navegação, edição, OCR, Motion Photos e cinema. Nenhuma dependência, migração destrutiva, credencial, publicação ou alteração de versão foi acrescentada.

## Testes

Oito contratos de integração novos cobrem: painel antes da fila de metadados, álbum enquanto a varredura geral está bloqueada, reutilização/invalidação da duração persistida, retorno de foco, atualização de uma pasta `.nomedia` sem refresh, UUID compartilhado de manutenção, alteração com app fechado e rejeição de gravação antiga.

Os testes de capas e rolagem rápida que usam catálogos sintéticos foram ajustados para marcar corretamente a validade desses catálogos com a nova geração/modelo do catálogo. O teste do painel de ocultos passou a usar uma imagem indexada e pastas realmente ocultas no filesystem: o MediaStore sanitiza nomes de diretórios iniciados com ponto ao inserir. Mantém as verificações de não revelar uma pasta desconhecida, margens, OK, olho/reocultação e encerramento da revelação ao fechar.

Os testes do OCR esperam a conclusão do pedido real e a exibição da janela antes de clicar uma única vez em Copiar. O editor também tem uma espera limitada ao seu sinal de execução; ausência de texto, janela ou callback continua falhando. Não houve alteração no algoritmo/controlador OCR de produção.

As verificações funcionais não foram removidas ou afrouxadas. As tentativas falhadas/interrompidas permanecem nos relatórios, incluindo um dump da thread principal aguardando a renderização gráfica do emulador com GPU host. O AVD dedicado foi reiniciado sem apagar dados, com `-gpu software -cores 4`, mantendo as animações do app.

Validação final `20261005-101927-145-3abbe767`: **227 unitários, 100 instrumentados, zero falhas/ignorados**, lint com zero erros e 126 avisos existentes, além de 45 contratos das ferramentas. A rodada completa durou 466,1 s; a instrumentação, 406,3 s. Evidências ficam em `app/build/reports/validation/20261005-101927-145-3abbe767/`, com recibo `delivery.json`. A impressão digital dos inputs finais é `6808EC38D84353C6EDF6F426AEF5C77FBCAA4C164D1923E098AF0F48AA3F65CC`.

Os tempos do emulador não constituem um benchmark do aparelho físico. Os testes de bloqueio são determinísticos: seguram a dependência geral e exigem que a interface local funcione antes de soltá-la. Ainda é recomendável confirmar fluidez na biblioteca real do usuário.

## Preparação da release 0.8.64

Após definir `versionName` 0.8.64 e `versionCode` 8064, foi feita outra rodada completa: `20261005-105751-973-3d5b7be6`, com **227 unitários e 100 instrumentados aprovados, sem ignorados**, lint sem erros (126 avisos) e 45 contratos das ferramentas. A mesma compilação gerou o release otimizado assinado. A execução durou 609,8 s incluindo build release; a instrumentação, 422,1 s. A impressão digital final passou a `70D0D7891EECBEB0469474DF64C31DE8586ED65B75A0D4E0D78BD7E0CF6D5AD7`. A rodada anterior continua como evidência histórica da implementação. Confira [assinatura, versão e hash do APK](RELEASE-0.8.64.md).
