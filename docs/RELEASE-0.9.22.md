# Galeria Android 0.9.22

Escolha do quadro da foto em movimento.

- **Escolher quadro:** o botão Editar de uma foto em movimento abre direto a tela "Escolher quadro", com a foto e uma faixa com os quadros do vídeo na largura toda. Arrastar o dedo pela faixa mostra o quadro na hora; ao soltar, aparece o quadro exato.
- **Salvar substitui a foto:** o quadro escolhido passa a ser a foto, no mesmo arquivo, que continua sendo foto em movimento. O vídeo e o fim do arquivo, onde ficam os dados da câmera, permanecem idênticos. O quadro mantém a data, o local, o modelo da câmera e os demais dados da foto original, e o momento escolhido fica registrado no arquivo.
- **Original:** a foto da câmera, em resolução cheia, fica guardada dentro do próprio arquivo. O botão Original devolve exatamente o arquivo da câmera, e dá para escolher outro quadro a qualquer momento.
- **Mais edições:** o ícone de lápis no topo da tela abre o editor de sempre (recortar, girar, desenhar), que salva uma cópia como antes.
- **Gravação segura:** antes de gravar por cima, o app guarda o arquivo atual numa cópia privada e a devolve se a gravação falhar no meio.
- **Limitação:** o quadro escolhido tem a resolução do vídeo gravado pela câmera, normalmente menor que a da foto. A foto original em resolução cheia continua guardada no arquivo.

## APK

- Arquivo Galeria-Android-versao-0.9.22.apk
- Pacote com.galeria.android
- Versão 0.9.22, código 9022
- Tamanho 45457983 bytes
- SHA-256 do arquivo 73e6cb74b20a28d060da94aee46f3572a6ca654518d58b8b5ae1ae348e85fe87
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261123-114955-433-6b6e5ef7 (`-Delivery`): **163 testes de interface**, **333 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (158 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Teste de interface novo: numa foto em movimento da galeria, Editar abre a escolha do quadro; salvar um quadro posterior mantém a foto original e o vídeo intactos dentro do arquivo, grava um JPEG com o tamanho do vídeo e continua sendo detectado como foto em movimento; Original devolve o arquivo da câmera byte a byte.
- Unitários novos cobrem o fim da imagem JPEG (segmentos, preenchimento e marcadores de reinício), a separação entre foto original e vídeo, a escolha repetida de quadros sem perder a original, a volta ao arquivo da câmera e fotos sem XMP.
- No emulador, uma foto em movimento de teste aberta pelo álbum mostrou o quadro escolhido no visualizador depois de salvar, ainda como foto em movimento; o Original devolveu o arquivo idêntico ao inicial.

Pendente de conferência em aparelho real: fotos em movimento gravadas por câmeras Samsung e Google, e se a galeria da Samsung e o Google Fotos reconhecem o arquivo depois de escolher outro quadro.
