# Galeria Android 0.9.27

Corte pela área iluminada e prévia fluida na mesclagem, linha do tempo com zoom até quadro a quadro, Configurações mais claras e correções.

- **Corte na mesclagem:**
  - selecionar um vídeo ilumina o trecho, com uma alça em cada ponta;
  - arrastar as alças só marca a área a manter: o que fica fora escurece e nada é cortado ainda;
  - a tesoura, destacada enquanto há área marcada, exclui o que ficou fora;
  - sem área marcada, a tesoura divide no cursor; tocando num pedaço, a lixeira vira "Remover trecho" e apaga só ele;
  - dá para fazer vários cortes no mesmo vídeo, e os pedaços nunca se misturam com os de outros vídeos.
- **Editor sem travar:** a prévia usa um único reprodutor, e cada mudança só troca o que ele toca. Muitas ações seguidas não acumulam mais reprodutores, que deixavam a imagem parada enquanto o som seguia.
- **Linha do tempo do vídeo:**
  - o primeiro toque abre uma visão geral do vídeo inteiro com poucos quadros, que carrega rápido;
  - o segundo mostra um quadro por segundo, e o terceiro, quadro a quadro;
  - o quarto fecha a linha do tempo.
- **Configurações:**
  - as chaves ligadas usam a cor do tema cheia, e as desligadas um cinza bem visível, com contraste garantido em qualquer tema;
  - 8 opções mais complexas ganharam um "i" que abre a explicação detalhada.
- **Seleção de mídias:** o botão de expandir ficou menor e mais discreto. Nas mídias da borda direita, ele fica fora da faixa da barra de rolagem.
- **Correção:** o aviso do modo cinema não fica mais preso na tela depois da miniatura, de um gesto de brilho ou volume, ou de sair e voltar ao vídeo.

## APK

- Arquivo Galeria-Android-versao-0.9.27.apk
- Pacote com.galeria.android
- Versão 0.9.27, código 9027
- Tamanho 45709209 bytes
- SHA-256 do arquivo c9ff9fc129a4eb51eaa3a0a64abd3d32ace06edf76c746a5d118f64bb2711345
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261126-115145-589-aa9c45b1 (`-Delivery`): **193 testes de interface**, **386 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (196 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Testes de interface novos:
  - **Corte:** dividir, apagar o pedaço iluminado e manter só a área marcada.
  - **Editor:** depois de 12 trocas seguidas, a prévia continua tocando.
  - **Linha do tempo:** visão geral, um segundo e quadro a quadro, e o toque seguinte fecha.
  - **Configurações:** cada opção complexa tem o "i", o "i" abre a explicação, e as chaves têm contraste.
  - **Seleção:** o botão de expandir fica fora da faixa da barra de rolagem.
  - **Modo cinema:** o aviso some depois da miniatura e de várias trocas de modo.
- O teste de velocidade do visualizador falhava às vezes porque, com o emulador ocupado, os toques do teste viravam toques longos. Ele agora toca de uma vez, pelo caminho real do botão.
- O smoke de OCR no APK otimizado (cópia assinada para testes) passou nos fundos claro e preto.

Pendente de conferência em aparelho real:

- a fluidez do editor e da linha do tempo com vídeos longos da câmera;
- o quadro a quadro em vídeos de 60 fps, que avança cerca de um quadro a cada dois.
