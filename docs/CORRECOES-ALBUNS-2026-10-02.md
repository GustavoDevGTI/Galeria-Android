# Álbum vazio, lixeira e contagem de ocultos

- Mover todas as mídias de um álbum físico abre o destino e encerra a origem. Documentos, subpastas, `.nomedia` e arquivos em estado trashed/pending não mantêm um álbum sem mídias aberto. A decisão consulta a pasta acessível, não a grade filtrada/paginada; leitura desconhecida não é tratada como pasta vazia. Coleções agregadas que mantêm a mídia após mover não são redirecionadas como pastas físicas.
- Excluir o último item retorna à listagem de álbuns, inclusive quando a exclusão vem do visualizador. Filtros/buscas sem resultados, cancelamento e falha não bastam para considerar a origem vazia.
- “Usar lixeira” nas configurações essenciais é ativado por padrão. Desativar altera as próximas exclusões para definitivas, com confirmação correspondente; não remove conteúdo já na lixeira nem impede restaurá-lo.
- O painel de ocultos prefere metadados atualizados, consulta o índice de mídias e, com gerenciamento completo, conta arquivos das pastas ocultas conhecidas. Atualiza após notificação do MediaStore também enquanto aberto, sem revelar novas pastas, alterar checkbox/pin ou estender os 30 minutos de exibição temporária. Contagens inacessíveis não são presumidas zero. Não decodifica thumbnails.
- O atalho de visualizar na seleção usa cantos de ampliar, em vez do olho, mantendo a posição inferior direita e a seleção ao retornar. “Girar” usa uma imagem com seta de quarto de volta, distinta de atualizar, no submenu e no editor.

## Evidência

Compilação debug, **227/227 unitários, 92/92 instrumentados no AVD API 36, zero ignorados e lint sem erros (126 avisos anteriores)**. Rodada completa `app/build/reports/validation/20261002-162323-781-c4b64191/`, com 375,4 s de execução nativa e 434,6 s do comando completo. Hashes dos fontes e APKs/evidências também conferidos no repositório Y: pelo verificador de entrega. Os APKs debug anteriores foram preservados em `artifacts-before-mutations-20261002-162323`.

Foram preservadas as tentativas de compilação (`155940`: referência de enum; `160151`: API 24 indevida com mínimo 23, substituída por consulta compatível) e as rodadas nativas `160513`/`161544`, que falharam na escolha da janela pela automação. Os cliques/checagens envolvidos agora usam explicitamente a raiz do diálogo, sem enfraquecer as verificações, aumentar timeouts ou mudar o OCR do app. Preflight desses dois casos em `app/build/reports/mutation-preflight/`: 2/2 em 16,5 s; a rodada completa posterior também validou o diálogo de exclusão definitiva.

Sem atualização de dependências, incremento de versão, push ou publicação. Estes resultados não validam assinatura/APK release, todos os formatos/codecs nem aparelhos reais.
