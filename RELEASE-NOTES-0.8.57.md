# Galeria Android 0.8.57

Esta versão melhora as capas dos álbuns, as miniaturas de vídeos e a leitura das informações das mídias.

## Mudanças

- A capa automática de um álbum passa a seguir a primeira mídia da ordenação atual desse álbum.
- No menu do álbum, **Escolher capa** permite selecionar uma mídia manualmente. **Usar capa automática** restaura o comportamento padrão. Se a mídia escolhida deixar de pertencer ao álbum, a capa volta a ser automática.
- Vídeos cuja abertura é quase preta procuram um quadro visível mais adiante para a miniatura. Vídeos com uma abertura visível mantêm o quadro inicial. O ponto escolhido é estável entre aberturas.
- As informações de fotos e vídeos agora são separadas em grupos, com nomes de propriedades em negrito. A data aparece logo após o nome.

## Validação

- 77 testes unitários aprovados, lint e compilações debug e release aprovados.
- 12 testes instrumentados direcionados passaram no emulador, incluindo capas, miniaturas, metadados, abertura da galeria e modo cinema.
- A suíte instrumentada completa não foi concluída porque o sistema do emulador travou durante a execução longa; os testes do modo cinema foram repetidos isoladamente e passaram.
- APK release assinado com a mesma chave das versões anteriores. Não há publicação na Play Store.
