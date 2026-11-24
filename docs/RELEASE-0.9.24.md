# Galeria Android 0.9.24

Mesclar no lugar de Juntar, mesclagem mais rápida, mais resoluções para reduzir vídeos e fotos, editor mais compacto e Lixeira sempre visível.

- **Mesclar:** a função Juntar passou a se chamar Mesclar (Merge em inglês, Fusionar em espanhol). O vídeo mesclado recebe o nome do primeiro vídeo seguido de "Merged", como "Gustavo Merged.mp4". Se o nome já existir, ganha um número no fim.
- **Mesclagem mais rápida:**
  - as mídias são lidas em paralelo ao abrir o editor;
  - vídeos MP4 inteiros, sem cortes nem transição, na qualidade nativa e com o mesmo formato de vídeo e som são mesclados por cópia, sem recodificar;
  - quando não dá para copiar, o app volta sozinho à recodificação.
- **Mais resoluções:** a qualidade oferece Nativa e as resoluções de 2160p a 360p que ficam abaixo da original. Cada opção mostra o tamanho do vídeo e uma estimativa do arquivo. A taxa de bits acompanha a redução, nunca passa a original e não reserva mais espaço vazio no arquivo do que o vídeo precisa.
- **Reduzir um vídeo:** com um único vídeo no editor, escolher uma resolução menor grava uma cópia menor ao lado do original, com a resolução no nome.
- **Reduzir uma foto:** Redimensionar, no editor de fotos, lista a resolução atual e as resoluções usuais abaixo dela, com o tamanho estimado. "Largura personalizada…" mantém a escolha em pixels. A redução é feita em etapas, para a foto não perder nitidez.
- **Editor mais compacto:**
  - os círculos de transição entre os quadrados saíram, e os quadrados ficaram mais próximos;
  - um botão no topo escolhe a mesma transição para todas as passagens;
  - a lixeira no topo remove a mídia selecionada da mesclagem, que mantém pelo menos uma mídia.
- **Prévia estável:** mover vários quadrados seguidos não trava mais a prévia. Ela é refeita uma vez, quando as mudanças param, e se recupera sozinha de uma falha do reprodutor.
- **Lixeira:**
  - com a lixeira ativada, o álbum Lixeira continua visível mesmo vazio;
  - itens apagados de um álbum oculto aparecem na Lixeira enquanto o olho revela esse álbum;
  - quando o álbum volta a ficar oculto, só os itens dele somem da Lixeira, e os outros continuam.
- **Barra inferior:** ao levar um botão do Menu para a barra, os outros abrem espaço enquanto ele é arrastado. Ao soltar, cada botão desliza até o novo lugar, também ao devolvê-lo ao Menu.

## APK

- Arquivo Galeria-Android-versao-0.9.24.apk
- Pacote com.galeria.android
- Versão 0.9.24, código 9024
- Tamanho 45727998 bytes
- SHA-256 do arquivo e396a97734ddf896fdb27ec8bef36961d678f690ecafed7de89bd1cbce772196
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261124-150722-785-0cc5ee1c (`-Delivery`): **187 testes de interface**, **380 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (193 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Testes de interface novos:
  - **Mesclar:** vídeos inteiros e compatíveis mesclados por cópia, com o nome do primeiro vídeo, todos os quadros e o som de cada um, e ainda separáveis.
  - **Editor:** remover uma mídia escolhida por engano e várias trocas rápidas deixam a prévia pronta; a transição única é escolhida pelo botão do topo.
  - **Reduzir:** um vídeo sozinho sai na resolução escolhida e menor que o original; o menu só oferece resoluções menores. Na foto, Redimensionar só oferece tamanhos menores e salva o escolhido.
  - **Lixeira:** itens de um álbum oculto aparecem só enquanto o olho o revela.
- O smoke de OCR no APK otimizado (cópia assinada para testes) passou nos fundos claro e preto.
- O teste da velocidade do vídeo fecha o seletor e toca de novo quando o próprio Espresso detecta que um toque rápido virou toque longo no emulador ocupado. Um toque que o app ignorar continua reprovando.
- Unitários novos cobrem as opções de qualidade, a taxa de bits, a estimativa e o espaço reservado do arquivo, o nome "Merged", as condições da mesclagem por cópia, as resoluções da foto, a Lixeira vazia e a prévia da barra inferior.

Pendente de conferência em aparelho real:

- a animação dos botões entre o Menu e a barra;
- o ganho de tempo da mesclagem por cópia com vídeos grandes da câmera;
- vídeos 4K, fotos HEIC e fotos giradas pela EXIF na mesclagem.
