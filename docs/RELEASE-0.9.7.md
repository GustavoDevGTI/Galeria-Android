# Galeria Android 0.9.7

- **Voltar:** na tela de álbuns, **Voltar** desmarca a seleção e limpa a busca antes de sair; só a lista limpa fecha o aplicativo.
- **Miniatura flutuante:** ganhou os botões de mídia anterior e próxima, com reproduzir/pausar no centro.
- **Segurar para 2x:** na vertical, segurar o dedo em um vídeo tocando acelera para 2x. Arrastar com o dedo preso mantém os 2x e soltar volta à velocidade anterior.
- **Organizador:** o álbum que teve mídias movidas e ainda tem mídias na fila aparece na lista de origem com o selo **Em organização** e quantas já foram movidas. Ao tocar nele, escolha entre **Continuar organização** e **Reiniciar do zero**. Reiniciar limpa os destinos da roda e a reserva; as mídias já movidas ficam onde estão.
- **Seleção de mídias:** o contador mostra selecionadas/total, como **1/43**. O quadro que abre a mídia sem mudar a seleção aparece em todas as miniaturas, não só nas selecionadas.
- **Botões sobre as miniaturas:** a caixa de seleção, o quadro de ver a mídia, o alfinete e o olho dos álbuns ficaram menores.
- **Vibrações:** os botões com ícone e texto das barras inferiores passaram a vibrar; na 0.9.6 só os botões de ícone vibravam. O reconhecimento de texto vibra ao encontrar texto, ao não encontrar, a cada palavra selecionada e ao copiar. Também vibram as linhas das Configurações e os botões do organizador.

## APK

- Arquivo Galeria-Android-versao-0.9.7.apk
- Pacote com.galeria.android
- Versão 0.9.7, código 9007
- Tamanho 45208848 bytes
- SHA-256 do arquivo 79f8756ee8cbe0699aed7618d889f676c67a7efd879dd2dc49aa8f6c1224d911
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**Nenhum teste em emulador foi executado nesta versão**, por decisão do responsável pelo projeto; a conferência é feita em aparelho real. Não há recibo de entrega para a 0.9.7 e a regressão completa (`-Delivery`) não rodou.

O que rodou:

- Execução 20261113-092720-731-127caae8, sem emulador: 54 contratos das ferramentas e **266 testes unitários**, sem falhas ou ignorados.
- Lint sem erros, com 150 avisos, e montagem release otimizada. A montagem preservou os três registradores do OCR e seus construtores.

Os testes novos cobrem as regras do 2x ao segurar e o registro do álbum deixado pela metade no organizador. O teste instrumentado da seleção foi ajustado para o contador selecionadas/total, mas não foi executado.

Pendente de conferência em aparelho real: todas as mudanças desta versão, em especial a miniatura com anterior e próxima, o 2x ao segurar, o selo do organizador e a intensidade das vibrações.
