# Galeria Android 0.9.21

Foto em movimento integrada à própria foto.

- **Toca na própria foto:** o botão no rodapé toca o vídeo da foto em movimento por cima da imagem, no mesmo lugar e com o mesmo zoom, inclusive se você ampliar ou mover a foto enquanto ele toca. Quando o vídeo tem proporção diferente da foto, ele fica centralizado sobre ela e a foto aparece em volta.
- **Parar e terminar:** enquanto toca, o botão vira pausa e para o vídeo com um toque. Ao terminar, o vídeo esmaece de volta para a foto. Trocar de foto ou sair do visualizador também para a reprodução.
- **Som:** segue a chave de som do visualizador.
- **Tela separada removida:** a tela que abria só para tocar a foto em movimento saiu do app.
- **Detecção rápida:** o app lê onde o vídeo está nos metadados que a câmera grava na foto (formato Motion Photo e MicroVideo, do Google e de aparelhos Samsung recentes) e no índice que a Samsung grava no fim do arquivo. A leitura do arquivo de trás para frente fica só como reserva, para fotos sem esses dados.
- **Fotos vizinhas:** a próxima, a anterior e a seguinte são verificadas antes de você chegar nelas, numa tarefa de baixa prioridade que não atrasa a foto aberta.
- **Correção:** abrindo uma foto em movimento pelo álbum, o botão podia não aparecer. O visualizador abre antes de ler o álbum, e a foto ganhava outro endereço e o tamanho real quando o álbum carregava; o resultado da detecção era descartado como se fosse de outra foto. Agora a comparação é pela identidade da mídia, e a foto é conferida de novo quando o álbum termina de carregar.

## APK

- Arquivo Galeria-Android-versao-0.9.21.apk
- Pacote com.galeria.android
- Versão 0.9.21, código 9021
- Tamanho 45453469 bytes
- SHA-256 do arquivo 3a04dbeecb1546de5fa11dbac4979ceecb122bc40a6e41ce086b0a3203b6d9a3
- SHA-256 do certificado 1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce

A assinatura é a mesma da versão anterior e permite atualização sobre a instalação existente.

## Validação

A regressão completa rodou no emulador `Galeria_Codex_Test_36`, com a Galeria em português do Brasil.

- Execução 20261123-103859-842-9cf29053 (`-Delivery`): **162 testes de interface**, **329 unitários** e 54 contratos das ferramentas, sem falhas ou ignorados. Lint sem erros (155 avisos) e recibo de entrega aprovado pelo `assert-delivery-validation.ps1`.
- Na validação diária anterior, o teste de seleção de texto do OCR falhou uma vez ao fechar a seleção logo depois de um zoom; repetido três vezes, passou, e passou também na regressão completa.
- Teste de interface da foto em movimento: o botão toca o vídeo sobre a própria foto, na mesma página e logo acima da imagem ampliável, dentro do retângulo dela; o mesmo botão para; ao terminar, o vídeo some sozinho; sair do visualizador para a reprodução; a fila da galeria não avança.
- Teste de interface novo: uma foto com o vídeo indicado nos metadados (MicroVideo) é detectada sem varrer o arquivo.
- Unitários novos cobrem a leitura do XMP de um JPEG, os formatos Motion Photo (atributos e elementos, com itens e preenchimento depois do vídeo) e MicroVideo, o índice da Samsung, a ordem das fotos vizinhas, o enquadramento do vídeo sobre a foto e a troca de endereço da mesma foto.
- No emulador, uma foto em movimento de teste aberta pelo álbum mostrou o botão e tocou o vídeo sobre a foto.

Pendente de conferência em aparelho real: fotos em movimento gravadas por câmeras Samsung e Google (no emulador foi usado um arquivo de teste montado à mão) e o som durante a reprodução.
