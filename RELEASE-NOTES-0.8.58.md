# Galeria Android 0.8.58

Esta versão permite revelar temporariamente um álbum oculto na página inicial sem torná-lo público nas outras coleções.

## Mudanças

- O ícone de olho no gerenciamento de pastas ocultas exibe um álbum na página inicial por até 30 minutos. Tocar novamente no olho o oculta imediatamente.
- O prazo continua correndo em segundo plano. Ao fechar o aplicativo, a revelação é descartada.
- A mídia desse álbum permanece fora de Recentes, Favoritos e Todos os arquivos; itens ocultos da Lixeira seguem protegidos pelo controle próprio da Lixeira.
- Olho, pin e seleção recebem a mesma animação breve de toque.
- Testes de interface de menus e reprodução foram estabilizados para aguardar as transições assíncronas sem repetir o toque.

## Validação

- 80 testes unitários, lint e compilação debug aprovados.
- 56 testes instrumentados aprovados no emulador Android 16/API 36, em três lotes de 26, 15 e 15 testes. Uma execução contínua anterior foi interrompida por queda do sistema do emulador; após reiniciá-lo, todos os casos passaram em lotes.
- O prazo de 30 minutos foi verificado com relógio controlado; não houve espera real de 30 minutos no emulador.
- APK release assinado com a mesma chave da versão anterior, instalado e aberto no emulador para verificação inicial. Não há publicação na Play Store.
