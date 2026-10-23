# Barra de navegação dos álbuns e posição de Excluir

## Comportamento solicitado

- Excluir ocupa uma área fixa de 56 dp na extremidade direita da barra do visualizador, tanto para fotos como vídeos. O aparecimento de ações de OCR ou Motion Photo não desloca seu centro. Confirmação, Lixeira e exclusão definitiva continuam com as regras existentes.
- Barra inferior compartilhada na lista de álbuns e dentro dos álbuns: Ordenar, Filtrar, Ocultos e Configurações, conforme escolha explícita do usuário. Ícones de traço fino, texto curto, distribuição uniforme, cores do tema e feedback de clique reutilizam os componentes existentes.
- Ordenar e Filtrar atuam sobre a tela atual. Ocultos abre o painel existente de exibir/ocultar pastas; Configurações abre a tela existente.
- Na seleção, a barra de navegação dá lugar às ações de seleção existentes; ao sair da seleção, ela retorna. Ao abrir uma mídia, somente a barra própria do visualizador aparece.
- O menu principal conserva organização de pastas, criar pasta, visibilidade de ocultos na Lixeira e Atualizar. Dentro do álbum, permanecem agrupamento, visualização, criar pasta, aleatório, capa, espaçamento e modo cinema, conforme o tipo de coleção. Ordenar/Filtrar foram redistribuídos, não removidos.

## Isolamento e ciclo de vida

`GalleryBrowseBar` concentra somente os quatro atalhos e sua aparência; não carrega catálogo nem assume operações de mídia. Dentro de um álbum, Ocultos abre uma instância do host existente com `EXTRA_HIDDEN_PANEL`. Fechar o painel encerra apenas esse host e retorna ao álbum original. Esse host não limpa a sessão de revelação temporária, preservando o comportamento normal da instância principal. Ao retornar de Configurações, as cores da tela do álbum e da barra são reaplicadas somente quando o tema mudou.

Nenhuma nova dependência, atualização de banco ou alteração nas regras de exclusão. As mudanças anteriores no botão de velocidade continuam preservadas. Sem incremento de versão, commit, push ou release nesta etapa.

## Validação

A rodada inicial `20261013-150849-037-71f63520` aprovou 237 unitários e 50 testes instrumentados, sem falhas/ignorados; lint sem erros e 131 avisos. Os quatro casos novos verificam atalhos, menus restantes, retorno de Ocultos ao mesmo álbum sem encerrar a revelação temporária, substituição da barra na seleção, tema ao retornar de Configurações e posição de Excluir em foto/vídeo, inclusive com OCR visível. As capturas do álbum e do visualizador foram revisadas visualmente.

Na revisão final foram ajustados o construtor da View para ferramentas, a notificação de troca de tema e a espera da captura após dispensar o popup. A execução final `20261013-151751-414-7d673bb8`, combinando Appearance, Viewer, Playback, Cinema e Albums, aprovou **237 unitários e 75 testes instrumentados**, sem falhas/ignorados. Lint: zero erros e 129 avisos, sem os dois avisos introduzidos durante a primeira revisão. Tempo total: 634,2 segundos, dos quais 472,1 segundos de instrumentação. Também passaram seleção contínua, alteração de colunas, operações de mover/excluir, navegação entre mídias e coleções virtuais. As capturas finais da página inicial, álbum e visualizador foram conferidas visualmente.

Esta validação cobre as áreas afetadas, não é uma aprovação completa de entrega. Os testes instrumentados usam debug no emulador, não substituindo confirmação do APK release em celular físico. Antes de publicar, será necessária a regressão completa sobre os metadados e fontes finais da versão.

## Publicação solicitada posteriormente

Após o pedido de push/release, a versão foi incrementada para 0.8.69 (8069). A primeira tentativa completa foi interrompida com ANR do SystemUI e crash no fechamento de uma janela de carregamento já removida. O fechamento foi corrigido e recebeu um teste de regressão, incluindo nova operação e fechamento antes do primeiro desenho. Depois das verificações focadas, a execução completa `20261013-155743-916-b246ef9d` aprovou 237 unitários e 124 instrumentados, sem falhas/ignorados, com lint sem erros e 129 avisos. O APK release foi recompilado após a correção e teve assinatura permanente, versão, manifesto e integridade conferidos. Consulte [as notas da 0.8.69](https://github.com/GustavoDevGTI/Galeria-Android/releases/tag/v0.8.69), com histórico das tentativas e limites da validação debug.
