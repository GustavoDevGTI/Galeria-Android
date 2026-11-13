# Galeria Android 0.9.8

- **Miniatura flutuante:** segue o formato do vídeo como ele aparece no visualizador. Vídeo em pé tem miniatura em pé e vídeo deitado, miniatura deitada, inclusive vídeos gravados em pé no celular, que vêm salvos deitados com marca de rotação. Ao trocar de mídia pela miniatura, o formato é recalculado.
- **Segurar para 2x:** depois que o 2x começa, arrastar o dedo em qualquer direção mantém a velocidade até soltar. Um pequeno deslize antes do 2x começar não cancela mais. O selo "2x" do topo saiu: a velocidade aparece só no rótulo do canto inferior direito, e os controles aparecem enquanto o dedo está segurando.
- **Organizador:** as marcações ficam guardadas por álbum a cada movimento. **Sair sem salvar** não descarta mais nada: os arquivos ficam onde estão e as marcações continuam guardadas. O selo **Em organização** aparece para álbuns com marcações sem salvar, mídias já movidas ou reserva. **Continuar organização** reaplica as marcações; **Reiniciar do zero** limpa roda, reserva e marcações sem desfazer o que foi salvo.
- **Contador de seleção:** as telas de álbuns e de mídias usam o mesmo estilo de texto e o mesmo formato selecionados/total, como **1/12**.

A posição dos botões da miniatura é definida pelo Android, que os coloca numa fileira embaixo e reserva o centro para expandir; o aplicativo não consegue centralizar o reproduzir/pausar.

## APK

- Arquivo Galeria-Android-versao-0.9.8.apk
- Pacote com.galeria.android
- Versão 0.9.8, código 9008
- Tamanho 45208441 bytes
- SHA-256 do arquivo fe73802b767b7e4d0cc26a3e9d33390f8ef8a7bc0757f109dfcd795c3e76e9f2
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

**Nenhum teste em emulador foi executado nesta versão**, por decisão do responsável pelo projeto; a conferência é feita em aparelho real. Não há recibo de entrega para a 0.9.8 e a regressão completa (`-Delivery`) não rodou.

O que rodou:

- Execução 20261113-110930-323-962bb85f, sem emulador: 54 contratos das ferramentas e **269 testes unitários**, sem falhas ou ignorados.
- Lint sem erros, com 150 avisos, e montagem release otimizada. A montagem preservou os três registradores do OCR e seus construtores.

Os testes novos cobrem o formato do vídeo com rotação e pixels não quadrados, a regra de álbum em organização e a reaplicação das marcações guardadas. Os testes instrumentados do organizador foram ajustados para a nova saída sem salvar, mas não foram executados.

Pendente de conferência em aparelho real: o formato da miniatura com vídeos em pé, o 2x ao segurar e arrastar, continuar uma organização deixada sem salvar e o contador nas duas telas.
