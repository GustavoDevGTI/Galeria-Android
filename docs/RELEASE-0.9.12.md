# Galeria Android 0.9.12

O idioma passa a seguir o celular, e o código foi reorganizado sem mudar a aparência do aplicativo.

- **Idioma do celular:** a Galeria usa o idioma principal do aparelho: português, inglês ou espanhol. Quando o celular está em outro idioma, ela aparece em inglês. A opção "Idioma" saiu das Configurações. A troca de idioma dentro do app impedia o visualizador de acompanhar a rotação quando o idioma escolhido era diferente do celular: o modo cinema não virava para paisagem e o layout ficava com as medidas da abertura.
- **Tradução completa:** textos que estavam fixos em português agora aparecem nos três idiomas. São eles o menu ⋮ do visualizador, os botões do editor de imagem e de vídeo, as mensagens de erro do corte de vídeo, os nomes das trilhas de áudio, o título "Ocultos" e o álbum "Todos os arquivos".
- **Android 9 ou anterior:** abrir uma pasta mostra só as mídias dela. Antes, uma pasta como "Cam" também trazia as fotos de "Camera".
- **Telas de resultado:** renomear, girar, criar PDF e voltar do visualizador para o álbum usam a forma atual do Android de receber o resultado de outra tela.
- **Organização interna:** os nomes das preferências ficam reunidos num só lugar, e o visualizador, a tela principal e a de álbum foram divididos em arquivos menores. As preferências salvas continuam as mesmas.

## APK

- Arquivo Galeria-Android-versao-0.9.12.apk
- Pacote com.galeria.android
- Versão 0.9.12, código 9012
- Tamanho 45370161 bytes
- SHA-256 do arquivo 365501447e2a5e50d177e371733ed1439cc0e34a008f8d1ae6034100a624657c
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261116-153201-674-7982ea1f (`-Delivery`): **154 testes de interface**, **297 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (142 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- O emulador revelou o problema de rotação causado pela troca de idioma dentro do app, que esta versão remove.
- Um teste unitário novo confere que português, inglês e espanhol têm os mesmos textos.

Pendente de conferência em aparelho real: o celular em inglês ou espanhol, a rotação no modo cinema e mover fotos entre pastas num Android 9 ou anterior.
