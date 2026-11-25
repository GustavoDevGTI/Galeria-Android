# Galeria Android 0.9.26

Miniatura de vídeo com botão e opção, tempo de cada foto na mesclagem, resoluções mais limpas e notificações em evidência.

- **Modo miniatura:**
  - um botão na barra do vídeo, ao lado da linha do tempo, põe o vídeo em janela flutuante na hora, tocando ou pausado;
  - a nova opção "Miniatura automática", em Configurações › Vídeos, vem ligada como antes. Desligada, voltar com o vídeo tocando fecha o visualizador.
- **Tempo das fotos na mesclagem:** cada foto tem o próprio tempo, 3 s por padrão, de 1 a 10 s. Tocar no quadrado de uma foto mostra o − e o + dela. Mesclagens antigas continuam separáveis.
- **Resoluções pelo nome:** a qualidade da mesclagem e o Redimensionar das fotos mostram só o nome ("720p"), com o tamanho estimado do arquivo, sem largura×altura.
- **Notificações em evidência:**
  - a da mesclagem saiu do grupo das silenciosas e passou a um canal normal;
  - o título é "Galery" e o texto diz o que está acontecendo; o progresso continua sem som;
  - o aviso do relógio de 48 horas também leva "Galery" no título.
- **Correção:** mesclar logo depois de uma mesclagem que terminou em segundo plano não mostra mais "Vídeo salvo" fechando o editor. O editor novo abre direto.

## APK

- Arquivo Galeria-Android-versao-0.9.26.apk
- Pacote com.galeria.android
- Versão 0.9.26, código 9026
- Tamanho 45730791 bytes
- SHA-256 do arquivo 09dc52aeb8d58bd83a5eeb0cbdc4c282c7b90a591abece533222b9e48e12c44f
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261124-222659-752-6f6d72c7 (`-Delivery`): **190 testes de interface**, **382 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (194 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Testes de interface novos:
  - **Miniatura:** com a opção desligada, voltar fecha o vídeo, e o botão abre a miniatura com o vídeo pausado.
  - **Mesclagem:** o tempo aparece ao tocar na foto, muda só para ela e fica gravado no vídeo; o editor novo abre mesmo com o resultado de uma mesclagem anterior pendente; o canal da notificação é o normal, e o silencioso antigo sai.
  - **Resoluções:** os menus mostram só o nome e a estimativa.
- O teste de velocidade do visualizador registra se cada toque chegou ao botão e se a barra estava visível, para identificar a causa da falha intermitente se ela voltar.
- O smoke de OCR no APK otimizado (cópia assinada para testes) passou nos fundos claro e preto.

Pendente de conferência em aparelho real:

- a posição da notificação entre as demais, que varia com o fabricante;
- o botão de miniatura em aparelhos que limitam janelas flutuantes.
