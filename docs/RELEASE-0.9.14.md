# Galeria Android 0.9.14

Criador de pastas refeito, "Nova pasta" dentro do Mover e barra lateral de álbuns.

- **Criador de pastas:** "Criar nova pasta" abre uma única janela centralizada, no padrão das outras janelas do app. Ela mostra a escolha entre armazenamento interno e cartão SD lado a lado, o caminho atual com um botão para voltar uma pasta, as pastas daquele local, o campo do nome e os botões Cancelar e Criar. Enquanto o nome é digitado, a lista encolhe para os botões continuarem visíveis, e a tecla de confirmar do teclado também cria a pasta.
- **Nova pasta no Mover:** o painel Mover do visualizador, da seleção dentro de um álbum e da seleção de álbuns na tela principal ganhou "Nova pasta" na primeira linha. Ela abre o mesmo criador e, ao criar, as mídias vão direto para a pasta nova. Aberto pelo Mover, o criador começa em Pictures e oferece só o armazenamento interno, onde o Mover grava; criar direto na raiz do armazenamento pede para escolher uma pasta como DCIM ou Pictures. O painel abre mesmo quando não há outro álbum de destino.
- **Barra lateral de álbuns:** dentro de um álbum, arrastar o dedo para a direita abre por cima da grade um painel com todos os álbuns, um por linha, no mesmo visual do painel Mover, com a quantidade de cada um e o álbum atual destacado. A ordem é a da tela principal, com os fixos no topo. Tocar num álbum abre ele no lugar do atual, e voltar leva à tela principal; arrastar para a esquerda, tocar fora ou voltar fecha o painel. O arrasto precisa começar longe da borda esquerda, que é o gesto de voltar do Android, e não abre durante a seleção.
- **Correção:** mover para uma pasta informada pelo caminho completo do armazenamento gravava a mídia num caminho duplicado. O caminho agora é convertido para o formato relativo antes de mover.

## APK

- Arquivo Galeria-Android-versao-0.9.14.apk
- Pacote com.galeria.android
- Versão 0.9.14, código 9014
- Tamanho 45393659 bytes
- SHA-256 do arquivo 0adac088d499e7031d6384defdad46552067add3470828b4895e63f3743ee7ea
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261117-115109-818-aeb79cdb (`-Delivery`): **155 testes de interface**, **306 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (147 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Teste de interface novo da barra lateral: arrastar para a direita abre os álbuns, rolar na vertical não abre, e tocar num álbum abre ele no lugar do atual.
- O teste do criador de pastas confere a janela única (armazenamento, caminho, nome e botões) e a navegação para dentro de uma pasta.
- Unitários novos cobrem o gesto da barra lateral e a conversão do caminho de destino.
- No emulador, criar uma pasta pelo Mover e mover uma imagem para ela gravou em `Pictures/<nome>/`, como esperado.

Pendente de conferência em aparelho real: o criador de pastas com cartão SD montado, o Mover para uma pasta nova pelo visualizador e a barra lateral num álbum com muitos itens.
