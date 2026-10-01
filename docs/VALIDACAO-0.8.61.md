# Validação da versão 0.8.61 — 01/10/2026

## Resultados

- Compilação release assinada, debug e APK de testes: aprovadas.
- 105 testes unitários executados com JUnit pelo runner direto: aprovados. O runner local evita a falha de comunicação de workers de teste no Windows; não substitui nem desativa os testes.
- Lint debug: zero erros, 125 avisos existentes.
- Assinatura do APK verificada com apksigner, mesmo certificado da release anterior. versionName `0.8.61`, versionCode `8061`.
- Suíte completa instrumentada, debug, no AVD `Galeria_Codex_Test_36`, API 36, SwiftShader, sem janela: **86 testes em 320,1 segundos; 79 aprovações e 7 falhas**. Não houve aprovação integral. O APK release otimizado não foi instalado no celular físico.
- Execução direcionada anterior: 12/15 aprovações, incluindo os cinco testes de navegação e os cinco da linha do tempo. Caso de toque cancelado durante a troca: três aprovações isoladas. A navegação após reabertura voltou a falhar na suíte completa; não tratar o relato como completamente resolvido apenas com base na execução direcionada.

## Falhas da execução completa

| Teste | Primeiro erro |
| --- | --- |
| `CinemaModeInstrumentedTest#cinemaButtonChangesModeWithoutReplacingPlayer` | `RootViewWithoutFocusException` no Espresso: janela sem foco. |
| `CinemaModeInstrumentedTest#albumCinemaPreferenceOpensVideoInCinemaMode` | A janela não recuperou foco após alterar a orientação. |
| `ImageEditorInstrumentedTest#editButtonOffersFocusedCropRotateAndCustomEditor` | A opção `Cortar imagem` não apareceu para o teste. |
| `ImageRotationInstrumentedTest#rotationKeepsMotionPhotoEmbeddedVideoIntact` | Após girar, orientação esperada 90°, obtida 0°. |
| `ImageTextRecognitionInstrumentedTest#documentLongPressAndCustomEditorOfferCopyableText` | Interface de cópia do OCR não ficou disponível. |
| `ImageTextRecognitionInstrumentedTest#viewerDetectsTextAutomaticallyAndCopiesItFromTheIcon` | Botão `Copiar tudo` não encontrado. |
| `VideoViewerRegressionTest#bothSwipeAxesKeepWorkingForPhotosAndVideosAfterReopening` | Destino esperado era um vídeo, mas a mídia atual continuou `photo-a.png`, com fila de quatro itens, `switchingItem=false`, foco ativo e zoom mínimo. |

Nenhuma asserção foi removida ou teste desativado para obter aprovação. Falhas de foco ou de busca por um rótulo não provam, isoladamente, remoção de uma função; exigem investigação e validação manual. O erro de orientação da Motion Photo ocorreu antes da asserção de integridade do vídeo na primeira execução. Em uma repetição isolada (3,6 segundos), o teste verificou primeiro a detecção e extração do vídeo: os bytes do MP4 embutido foram preservados integralmente, mas a orientação continuou em 0° em vez de 90°. A asserção de orientação foi mantida e o teste continua falhando; não considerar o giro de Motion Photos corrigido.

## Limites e segurança

- Não houve testes automatizados em celular físico. Fabricantes, codecs, dual áudio/legendas, Motion Photos e bibliotecas reais precisam de validação adicional.
- Os testes usam suas próprias mídias e removem os arquivos temporários no término. Não foi necessário limpar dados nem desinstalar a galeria do emulador.
- Não foram removidas funções nem incluídas chaves/credenciais no Git. Os APKs históricos da raiz permanecem intactos.
- [Checklist manual](TESTES-MANUAIS-0.8.61.md). Para edição e giro de Motion Photos, usar cópias/arquivos sem importância enquanto houver pendências.
- [Diagnóstico e correção específica do bloqueio de transição](DIAGNOSTICO-GESTOS-2026-10-01.md).

SHA-256 do APK: `86c80c036e00ea9e4d5b8a531d70c7d05ee1bb3a811a4c2624c3cba046e71052`.
