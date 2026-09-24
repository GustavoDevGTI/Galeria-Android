# Galeria Android 0.8.56

Esta versão ajusta a visibilidade das coleções, a seleção por arraste e a ordenação dos álbuns.

## Mudanças

- Recentes e Favoritos não exibem mídias de pastas ocultas.
- Itens da Lixeira provenientes de pastas ocultas ficam escondidos por padrão. O menu permite exibi-los ou ocultá-los; a opção também está no menu principal quando a Lixeira não aparece por conter apenas esses itens.
- Álbuns sem mídias não aparecem. Favoritos só aparece se houver ao menos uma mídia favorita visível.
- Fixar um álbum agora é diferente de favoritar uma mídia. Álbuns fixados pelo usuário vêm antes dos álbuns essenciais; um ícone indica os fixados.
- O toque prolongado seguido de arraste seleciona continuamente as mídias percorridas sem atualizar a pasta. Cada mídia selecionada tem um botão de visualização no próprio quadro, preservando a seleção ao voltar.
- A ordenação mostra uma seta na opção ativa. Ao escolher outra opção, começa em ordem crescente; ao tocar novamente na mesma opção, alterna para decrescente. Foram removidos os botões separados de direção e os indicadores circulares.

## Validação

- 74 testes unitários aprovados, lint e compilação debug aprovados.
- Dez testes instrumentados aprovados no emulador Android 16/API 36, cobrindo coleções virtuais, arraste e visualização sem perder a seleção, seletor de ordenação e opção de itens ocultos da Lixeira.
- APK release assinado com a mesma chave das versões anteriores. Não há publicação na Play Store.
