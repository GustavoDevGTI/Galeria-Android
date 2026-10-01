# Galeria Android 0.8.61

## Correções e melhorias

- Navegação horizontal e vertical entre fotos e vídeos: conclusão segura de trocas confirmadas mesmo com um novo toque cancelado, animação interrompida ou retorno do segundo plano. Página, fila e player permanecem sincronizados; o player anterior é liberado uma única vez.
- Linha do tempo contínua com miniaturas por segundo e scroll horizontal cobrindo toda a duração. A barra simples de reprodução permanece disponível; o botão da faixa abre/fecha as miniaturas sob demanda. Preparação progressiva em segundo plano do trecho atual e cache privado, sem extrair o vídeo inteiro ao abrir.
- Correção da rotação de fotos JPEG/PNG/WebP suportadas, com atualização dos caches de imagem. Formatos sem suporte ao giro direto seguem o fluxo de cópia do editor, preservando o original.
- OCR local com tentativas de orientação, tratamento de imagens escuras, resolução de leitura detalhada maior e cancelamento de análises obsoletas. A imagem de regressão de texto girado está somente nos testes, não no APK de distribuição.
- Resposta visual suave para controles de clique repetido, preservando os estados reais de seleção.
- Caches de miniaturas/documentos já reconhecidos continuam ocultos após crescimento, atualização e troca de filtros, incluindo suas subpastas.
- Cinema imersivo com restauração das barras do sistema ao sair. Play/pause centralizado em vídeos e Motion Photos, com tempos à esquerda e velocidade à direita.

## Validação e limites

**Validação parcial da interface: a suíte completa teve 79 aprovações e 7 falhas. Não considerar esta versão integralmente validada.**

- 105 testes unitários aprovados; lint debug sem erros (125 avisos existentes).
- Antes de preparar esta publicação, 12 dos 15 testes direcionados passaram no emulador API 36: os cinco de navegação, os cinco da linha do tempo e dois de cinema. O caso que reproduzia o bloqueio da navegação passou também em três repetições isoladas.
- Os três testes direcionados restantes de cinema falharam por foco de janela no Espresso. A suíte de cinema não está integralmente validada. Nenhuma asserção foi removida ou teste desativado para obter aprovação.
- Compilação release assinada, debug e APK de testes aprovadas. Assinatura verificada com o mesmo certificado da versão release anterior.
- Suíte completa no emulador API 36: 86 testes executados em 320,1 segundos; 79 aprovados e 7 falhas. Pendências: dois testes de cinema/foco de janela, submenu Editar, giro de Motion Photo, dois fluxos de cópia do OCR e navegação intermitente após reabertura. O terceiro teste de cinema que falhou na suíte direcionada passou na execução completa.
- Repetição isolada do giro de Motion Photo: vídeo embutido detectado e seus bytes preservados, mas a orientação permaneceu incorreta. O teste continua falhando; o giro desse formato não foi validado.
- [Detalhamento das sete falhas e limites da validação](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.61/docs/VALIDACAO-0.8.61.md).
- Não houve teste automatizado no celular físico. A validação manual em aparelho continua necessária, especialmente para OCR, formatos de mídia, trilhas de áudio/legenda e comportamento do fabricante.
- Sem publicação na Play Store; sem chaves ou credenciais de assinatura no repositório; APKs históricos preservados.

## Instalação e testes manuais

Atualize por cima da release anterior para preservar preferências. Se houver conflito com uma instalação debug, não desinstale sem preservar os dados necessários. Use somente cópias ou arquivos sem importância para testar edição e giro de Motion Photos enquanto as pendências não forem resolvidas.

[Checklist desta atualização](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.61/docs/TESTES-MANUAIS-0.8.61.md).

[Roteiro completo da galeria/editor](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.61/docs/TESTES-MANUAIS-0.8.60.md).

SHA-256 do APK: `86c80c036e00ea9e4d5b8a531d70c7d05ee1bb3a811a4c2624c3cba046e71052`.
