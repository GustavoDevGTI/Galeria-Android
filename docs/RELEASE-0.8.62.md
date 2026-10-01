# Galeria Android 0.8.62

## Correções

- Giro de fotos JPEG, PNG e WebP sem orientação EXIF prévia. A rotação não recomprime a imagem e preserva o vídeo embutido de Motion Photos.
- OCR detalhado mais rápido: evita repetir a análise em outras orientações quando já encontrou um trecho extenso e de alta confiança. Texto curto, fragmentado ou pouco confiável continua passando pelas tentativas adicionais. Preservadas a leitura de imagens escuras/giradas, a cópia pelo ícone e a extração por toque prolongado e pelo editor.
- Submenu Editar abre Cortar e Edição personalizada depois de fechar o diálogo, evitando disputas de foco; Girar permanece disponível pelo mesmo menu.
- Testes de cinema e navegação sincronizados com a janela correta, a conclusão das transições e a remoção das camadas de entrada do Android. Mantidas as verificações de áudio/legenda, orientação, barras do sistema, player, mídia de destino e integridade dos arquivos.
- Runner de testes conserva o relatório completo e permite repetir classes sem reinstalar os APKs, reduzindo execuções redundantes de diagnóstico.
- Nenhuma funcionalidade existente foi removida.

## Validação

- 106/106 testes unitários aprovados.
- 87/87 testes de interface aprovados no emulador API 36; suíte completa em 311,1 segundos.
- Três repetições extras da classe de navegação: 5/5 aprovados em cada execução.
- Lint debug sem erros, com 125 avisos existentes.
- Compilação release assinada aprovada; APK verificado com o mesmo certificado permanente da versão anterior, `versionName` 0.8.62 e `versionCode` 8062.
- As sete falhas documentadas na 0.8.61 foram investigadas e resolvidas; nenhum teste ou asserção foi removido ou ignorado para obter aprovação.
- [Causas e resultados completos](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.62/docs/CORRECOES-VALIDACAO-2026-10-01.md).

Os testes de interface validam a base de código na build debug. Não houve teste automatizado no celular físico; codecs, fabricantes, idiomas, variantes reais de Motion Photos e combinações de trilhas continuam sujeitos à validação manual. Nenhuma publicação na Play Store e nenhuma credencial de assinatura adicionada ao Git.

## Instalação

Atualize por cima da release anterior para preservar dados e preferências. Se houver conflito com uma instalação debug, não desinstale sem antes preservar os dados necessários. As releases e os APKs históricos permanecem intactos.

[Checklist de testes manuais](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.62/docs/TESTES-MANUAIS-0.8.61.md).

SHA-256 do APK: `72b22c37a69750039f3f330357ef06b74530cedffd1e4cfbe824eb7be0e8cad5`.
