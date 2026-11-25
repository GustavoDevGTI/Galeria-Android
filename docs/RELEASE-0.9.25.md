# Galeria Android 0.9.25

Correção da Lixeira com pastas ocultas.

- **Lixeira e pastas ocultas:** o que é apagado de um álbum oculto aparece na Lixeira enquanto esse álbum está exposto na grade, de qualquer das duas formas:
  - revelado pelo olho, por 30 minutos;
  - pasta oculta (com `.nomedia` ou com ponto no nome) marcada como visível, com as pastas ocultas mostradas.
- Quando o álbum volta a ficar oculto, só os itens dele saem da Lixeira, e os outros continuam.
- Itens de álbuns ocultados pelo usuário e de pastas seguras continuam fora da Lixeira.

## APK

- Arquivo Galeria-Android-versao-0.9.25.apk
- Pacote com.galeria.android
- Versão 0.9.25, código 9025
- Tamanho 45727978 bytes
- SHA-256 do arquivo 23bf7232841b81c937ecca5af24844a6c8e27dc939a9ad6a918e97251cd1daae
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261124-213104-543-f60dd562 (`-Delivery`): **188 testes de interface**, **380 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (193 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Teste de interface novo: apaga fotos de verdade de uma pasta `.nomedia` exposta, de uma pasta com ponto e de uma pasta oculta pelo usuário, e confere a Lixeira com as pastas ocultas mostradas, escondidas e com o olho em uma só.
- O teste de troca de mídias do visualizador espera o Android terminar de registrar os vídeos recém-criados antes de ler o catálogo. Numa rodada anterior ele falhou porque leu durante esse registro, e o app descartou a leitura como deve.
- O smoke de OCR no APK otimizado (cópia assinada para testes) passou nos fundos claro e preto.

Pendente de conferência em aparelho real:

- a Lixeira com a pasta oculta usada no aparelho, em especial uma subpasta dentro de uma pasta `.nomedia`.
