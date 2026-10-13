# Galeria Android 0.8.68

## Mudanças

- OCR com seleção diretamente sobre a foto, sem substituir a imagem por uma janela de texto. Contornos discretos indicam palavras reconhecidas; o trecho escolhido recebe destaque translúcido.
- Toque seleciona uma palavra; arraste e alças ajustam o intervalo. A barra contextual oferece Copiar, Selecionar tudo e Fechar. Copiar utiliza a interpretação do OCR somente do trecho selecionado.
- A seleção acompanha o zoom e a posição da foto. Toque prolongado e o ícone de texto continuam disponíveis; Voltar fecha a seleção antes de sair do visualizador.
- No editor personalizado, o reconhecimento considera a imagem editada, incluindo textos, traços e filtros, sem salvar ou alterar o arquivo original. Fechar o OCR devolve as ferramentas de edição; Voltar fecha a seleção sem abandonar a edição.
- Mantidos o modelo local, caches limitados, descarte de resultados após troca de mídia/pausa e indicador circular de processamento com bloqueio de pedidos duplicados. Nenhuma nova dependência nem serviço remoto de OCR.

O diálogo separado foi substituído intencionalmente pela seleção na imagem, conforme solicitado. As demais funcionalidades não foram intencionalmente removidas. Detalhes e histórico de validação em [OCR-NA-IMAGEM-2026-10-13.md](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.68/docs/OCR-NA-IMAGEM-2026-10-13.md).

## Validação

Com versão `0.8.68` e código `8068` definidos, a regressão completa aprovou **235/235 testes unitários e 118/118 testes instrumentados**, sem falhas ou ignorados, no emulador dedicado Android 16/API 36 com SwiftShader. Lint: zero erros e 129 avisos; 45 contratos das ferramentas de teste também aprovados.

Execução final `20261013-142457-348-2595ac4f`: 559 segundos no total, sendo 510,9 segundos de instrumentação. Evidências locais: `app/build/reports/validation/20261013-142457-348-2595ac4f/result.json` e `app/build/reports/validation/delivery.json`. O portão de entrega confere os fontes e hashes dos APKs/relatórios; aprovações parciais não foram promovidas a entrega.

A build release otimizada e assinada passou. Conferidos pacote `com.galeria.android`, versão `0.8.68`, código `8068`, ausência da Activity/runner exclusivos dos testes e de modo depurável no manifesto, além de assinatura válida com o mesmo certificado permanente da 0.8.67.

- Arquivo: `Galeria-Android-versao-0.8.68.apk`
- Tamanho: 45.121.081 bytes
- SHA-256: `ac97fe75806d2a8cd336304cf8772d014bebca25c5d1dbeffda6b73dc8dad8f9`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Evidências locais da build e das verificações: `app/build/reports/release-0.8.68-{build,signature,package,manifest}.txt`.

A primeira tentativa completa (`20261013-141216-941-e379399d`) terminou em 117/118 aprovações e foi rejeitada. O caso de mover o último arquivo do visualizador lançou uma `NullPointerException` ao consultar `ActivityScenario.state` enquanto a Activity de origem estava no estágio intermediário STARTED. A espera do teste passou a consultar o monitor do ciclo de vida na thread principal, conservando a referência da origem e exigindo exatamente DESTROYED, dentro do mesmo prazo. Não foram removidas verificações de destino, contagem ou encerramento, nem alterado o comportamento de navegação do app.

O caso corrigido passou três vezes seguidas (9,5 / 7,4 / 7,5 segundos) antes da nova regressão completa. Evidências: `app/build/reports/release-0.8.68-test-sync/`. Essas repetições isoladas não foram usadas como aprovação de entrega.

## Atualização e limites

Atualize por cima da release anterior para preservar dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar os dados necessários. Não há publicação na Play Store nem inclusão de chaves/credenciais no Git; releases e APKs históricos permanecem intactos.

Os testes automatizados de interface usam debug no emulador, não substituindo a confirmação do APK release otimizado em aparelhos físicos. Precisão do OCR depende da nitidez, tamanho, orientação e idioma do texto; a seleção visual não torna a interpretação infalível. Codecs, trilhas e variantes reais de Motion Photos continuam sujeitos à verificação complementar no aparelho.
