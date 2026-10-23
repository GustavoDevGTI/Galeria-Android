# Galeria Android 0.9.1

## Alterações

- O organizador circular aproveita melhor a altura disponível, com adaptação para orientação vertical e horizontal.
- O envio da mídia usa uma trajetória curva, redução gradual e perspectiva. O setor de destino se desloca levemente ao receber; a próxima mídia continua disponível imediatamente.
- Tocar na imagem central abre uma prévia ampliada em perspectiva, com o entorno desfocado. Tocar novamente ou usar Voltar fecha a prévia sem alterar a fila.
- A reserva tem formato mais vertical e dá destaque à última imagem guardada. O próprio botão continua se transformando no carrossel para selecionar e arrastar miniaturas.
- Cada álbum mostra quantas mídias recebeu na sessão. O contador acompanha as trocas de posição e diminui ao desfazer; movimentos falhos não o incrementam.
- A escolha dos álbuns compartilha capas, tipografia e cores com o menu Mover para álbum.
- Desfazer fica na parte inferior; o aviso de instruções foi removido.
- Permanecem os 1 a 8 destinos personalizáveis, envio por impulso ou arrasto, reordenação por gesto e contraste dos temas.

## Validação

A execução completa `20261023-142602-541-a83016fe`, com versão 0.9.1 e código 9001 definidos, aprovou **250 testes unitários, 142 instrumentados e 45 contratos das ferramentas**, sem falhas ou ignorados. Lint: zero erros e 133 avisos. Os testes instrumentados foram executados no emulador Android 16/API 36.

O APK é otimizado com R8 e redução de recursos. A assinatura permanente permite atualizar a release instalada, preservando dados e preferências. A conferência em aparelhos físicos e codecs de fabricantes continua complementar à validação automatizada.

O recibo de entrega foi aceito por `assert-delivery-validation.ps1`. A compilação release preservou os três registradores OCR e seus construtores. Pacote, versão, assinatura e ausência de modo depurável foram verificados. Os fontes compilados e do repositório têm a mesma impressão digital: `0F46AFEE0F78470BE8A3B741E7B3795355EBB50B512BFC22C62F737F1530D644`.

## APK

- Arquivo: `Galeria-Android-versao-0.9.1.apk`
- Pacote: `com.galeria.android`
- Versão: `0.9.1` / código `9001`
- Tamanho: 45.169.989 bytes
- SHA-256: `11084021d82776ca8895cbe265693e038ceaf26f6d90ba5991f948a07d93cf7c`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`
