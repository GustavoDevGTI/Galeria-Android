# Galeria Android 0.9.5

- **Cor do tema:** a roda de cores tem o centro branco e a borda preta; cada linha vai do tom claro ao escuro da mesma cor. O tema acompanha a posição escolhida de forma gradual, do quase branco ao quase preto, e a tela de Configurações atrás do diálogo mostra a prévia inteira. A barra de luminosidade saiu, e **Restaurar padrão** fica dentro do diálogo.
- **Ícone do aplicativo:** o ícone na tela inicial acompanha a família de cor do tema (padrão, rosa, âmbar, verde, azul ou violeta).
- **Miniatura flutuante:** com um vídeo tocando, inclusive no modo cinema, **Voltar** mantém o vídeo em uma miniatura sobre a galeria e outros aplicativos. A miniatura tem reproduzir/pausar, expandir e fechar. Foto ou vídeo pausado continuam fechando normalmente.
- **Tocar e segurar:** segurar uma mídia ou um álbum vibra uma vez, quando o retorno por toque está ativado no celular, e encolhe o item.
- **Menus e diálogos:** os menus de três pontos, **Ordenar** e as escolhas em lista deixaram de usar um botão por item; só a opção selecionada tem fundo. As ações dos diálogos são texto alinhado à direita.
- **Seleção:** o botão que cancela a seleção usa um **×** no lugar da segunda seta de voltar. Em **Exibir/ocultar pastas**, **Carregar ocultos** é uma ação de texto.

## APK

- Arquivo Galeria-Android-versao-0.9.5.apk
- Pacote com.galeria.android
- Versão 0.9.5, código 9005
- Tamanho 45227913 bytes
- SHA-256 do arquivo 481b8cbbf15de400deaaee250cbb8a61e2ac63f74d2d01d5ca3df4e44ac0d943
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A execução 20261112-120703-019-d72c84e7 passou com **259 testes unitários e 154 instrumentados** no Android 16 (API 36), sem falhas ou ignorados. Lint sem erros, com 145 avisos. Os 54 contratos das ferramentas passaram e o recibo de entrega foi verificado.

Os testes novos cobrem a graduação do tema nos dois lados da troca claro/escuro, as regras da miniatura e o fluxo real de entrar na miniatura, pausar e retomar por ela. O teste do menu de edição aciona o voltar do próprio editor e aguarda o visualizador reassumir a tela.

A montagem otimizada preservou os três registradores do OCR e seus construtores. Os testes de OCR no código otimizado e os codecs em aparelhos reais não foram repetidos nesta versão.

Identificador dos fontes e da configuração usados na validação EC4FEEDB0168A8DDC3BC49F04F57B4FAA32C49D6444202AFF5C5B6A6BC32DA5C.
