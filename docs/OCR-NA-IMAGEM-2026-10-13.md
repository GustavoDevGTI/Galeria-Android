# Seleção de texto sobre a própria imagem

## Pedido e direção visual

Substituir a apresentação principal do OCR em diálogo de texto por seleção diretamente sobre a fotografia, mantendo a imagem visível. A orientação de design foi aplicada à hierarquia: a foto permanece como base; contornos discretos identificam palavras, azul translúcido indica a seleção e uma barra compacta permite Copiar, Selecionar tudo ou fechar, sem modal de texto.

## Implementação

- ML Kit local existente, sem nova dependência. O resultado conserva palavras, polígonos e agrupamentos de linhas/blocos, em vez de descartar a geometria após reconhecer.
- Coordenadas normalizadas na imagem orientada pelo EXIF, com transformação inversa das rotações de análise (0/90/180/270). Redimensionamento para OCR e inversão de cores em documentos escuros não mudam o espaço da seleção.
- `OcrDocument` guarda apenas texto/coordenadas; os caches limitados do controlador preservam a geometria junto com o texto, sem reter bitmaps.
- `OcrSelectionOverlay` é transparente e não altera pixels. Toque seleciona uma palavra; arraste e alças ampliam/reduzem o intervalo. Copiar usa o texto reconhecido correspondente ao intervalo, preservando quebras de linha/bloco. Selecionar tudo continua disponível.
- No visualizador, a transformação acompanha a matriz da foto, inclusive zoom. Dois dedos são encaminhados ao mesmo mecanismo de zoom, sem iniciar passagem de mídia enquanto a seleção está ativa. Fechar pelo ícone, pela barra ou por Voltar restaura a interação normal.
- Texto detectado continua habilitando o ícone automaticamente; toque prolongado permanece como entrada alternativa. Resultados obsoletos são descartados pelo controlador na troca de mídia/pausa.
- No editor, o OCR analisa um snapshot da imagem editada (incluindo textos, traços e filtros). Não salva, não altera o original e não usa o arquivo original como substituto da edição. A análise mantém o espaço visual completo para que caixas correspondam ao canvas, sem aplicar a saída do recorte antes de desenhá-la.
- Enquanto analisa, mostra o indicador circular padrão e rejeita pedidos repetidos. As ferramentas do editor ficam protegidas enquanto o bitmap é lido/selecionado; fechar o OCR reabilita a edição.
- Editor utiliza `ComponentActivity`, já disponível no projeto, para que Voltar feche a seleção antes de sair, inclusive com o despacho moderno do Android. Tema preto e ferramentas existentes preservados.
- Palavras e ações ficam disponíveis para acessibilidade via nós virtuais. Nenhuma imagem é enviada a serviço remoto.

## Referências

- [ML Kit: texto e coordenadas por bloco, linha, elemento e símbolo](https://developers.google.com/ml-kit/vision/text-recognition/v2/android).
- [Samsung: reconhecer e copiar texto de imagens](https://www.samsung.com/de/support/mobile-devices/text-auf-bild-erkennen-und-kopieren/).

## Validação e limites

A primeira execução (`20261013-104552-110-14225c77`) foi rejeitada: a camada de seleção existia, mas as ações flutuantes nativas não estavam disponíveis em cinco testes. A implementação passou a usar uma barra real na hierarquia da imagem; não foi removida a exigência de seleção/cópia para obter aprovação.

A tentativa `20261013-113534-104-eccb2be1` foi interrompida antes do recibo final. Após reiniciar o emulador dedicado sem apagar dados, `20261013-114256-739-11e3b037` aprovou 235 unitários, lint sem erros (129 avisos) e 9/9 testes de OCR. A captura `ocr-on-image-qa.png` dessa execução foi revisada: apenas BETA selecionada e copiada, com a fotografia ainda visível.

A regressão ampliada dos arquivos finais (`20261013-114757-825-efd75666`, áreas Ocr, ImageEditing, Viewer e Appearance) aprovou 235 testes unitários e 45/45 testes de interface no emulador API 36, sem testes ignorados. Lint: zero erros e 129 avisos. A rodada de interface levou 277,1 segundos; a validação inteira, 410,2 segundos. Inclui seleção/cópia de apenas uma palavra por toque real, arraste contínuo, alinhamento nas quatro rotações, zoom real com dois dedos, snapshot contendo texto adicionado no editor, Voltar fechando o OCR sem sair da edição e descarte de resultado após trocar de mídia. A captura final `ocr-on-image-qa.png` também foi revisada visualmente.

Sem incremento de versão, commit, push ou release nesta etapa; antes de publicar será necessária aprovação completa de entrega. Esta é uma validação por áreas afetadas, não a execução completa de todos os testes instrumentados do projeto. Precisão de leitura continua dependendo da imagem; seleção por palavras não torna infalível a interpretação do OCR.

## Preparação da publicação solicitada posteriormente

Após o pedido de push/release, a versão foi incrementada para 0.8.68 (8068). A primeira regressão completa foi rejeitada por uma exceção ao consultar o estado intermediário de uma Activity no teste de mover o último arquivo. A sincronização do teste foi corrigida sem relaxar a exigência de destruição da origem e passou três vezes isoladamente. A nova execução completa `20261013-142457-348-2595ac4f` aprovou 235 unitários e 118 instrumentados, sem falhas/ignorados, com lint sem erros e 129 avisos. A build release otimizada foi compilada e teve versão, manifesto, integridade e certificado permanente verificados. Consulte [as notas da 0.8.68](RELEASE-0.8.68.md), incluindo a tentativa rejeitada e os limites da validação debug.
