# Galeria Android 0.8.66

## Mudanças

- Recuperação automática dos metadados derivados na primeira abertura após atualizar o app, em segundo plano. Banco, mídias, favoritos, pins, capas, ocultos, ordem personalizada, lixeira e thumbnails persistidas são preservados.
- Carregamentos encerram corretamente em caso de falha, permitindo nova tentativa. Varreduras concorrentes têm limite de tentativas, e notificações de alterações não ficam presas atrás de transações longas do catálogo.
- Indicador circular de atualização padronizado, sem frases visíveis, no carregamento de álbuns/mídias, ocultos, carrossel de vídeo, Motion Photos e operações/destinos de arquivos. Descrições de acessibilidade e mensagens de erro permanecem.
- Cliques repetidos não iniciam pedidos duplicados enquanto o trabalho está em andamento. O carrossel mantém a barra simples de reprodução disponível, libera nova tentativa após falha/timeout e não abre tardiamente um pedido abandonado.
- Indicador bloqueante compacto e animação encerrada quando o componente sai da tela; símbolo estático quando as animações do Android estão desativadas.
- Testes de atualização por cima e de feedback visual, além de sincronização explícita dos testes com a conclusão das operações/transições.

Nenhuma funcionalidade foi intencionalmente removida. Nenhuma dependência nova foi adicionada. Detalhes de implementação e diagnóstico em [ATUALIZACAO-E-FEEDBACK-2026-10-09.md](https://github.com/GustavoDevGTI/Galeria-Android/blob/v0.8.66/docs/ATUALIZACAO-E-FEEDBACK-2026-10-09.md).

## Validação

Com versão `0.8.66` e código `8066` definidos, a regressão completa passou em **229/229 testes unitários e 114/114 testes instrumentados** no AVD dedicado Android 16/API 36, sem falhas ou testes ignorados. Lint: zero erros e 129 avisos. Também passaram os 45 contratos das ferramentas de validação.

Execução `20261009-142236-333-267b5e0d`: 542,3 segundos de validação, sendo 455,9 segundos de instrumentação. A aprovação completa foi conferida pelo portão de entrega com os hashes dos APKs/relatórios e fingerprint dos fontes finais.

A atualização por cima usa a versão debug histórica 0.8.62 e a candidata debug 0.8.66, sem limpar dados entre as duas instalações. Verifica a preservação de mídia, favoritos, ocultos, pins, capa, ordem personalizada e marcador privado de cache, migração do banco 2→3 e descarte de entrada obsoleta do catálogo.

A atualização por cima foi repetida com o APK final em `app/build/reports/upgrade/20261009-143213/result.json`. A build release otimizada/assinada também passou. Pacote `com.galeria.android`, versão `0.8.66`, código `8066`, manifesto sem a Activity exclusiva dos testes e sem modo depurável; assinatura válida, com o mesmo certificado da 0.8.65.

- Arquivo: `Galeria-Android-versao-0.8.66.apk`
- Tamanho: 45.120.311 bytes
- SHA-256: `7db74e91e9001985e5bd81a14c606896eb9308d44180bb7eb984432c2c603441`
- Certificado SHA-256: `1795e4918be4f3d0f9d7da2d16fd961658a27ce731f0343b9b357501fb6b63ce`

Evidências locais: `app/build/reports/validation/20261009-142236-333-267b5e0d/result.json`, `app/build/reports/validation/delivery.json`, relatórios de atualização acima e `app/build/reports/release-0.8.66-{build,signature,package,manifest}.txt`.

Os testes de interface e de migração usam debug. Não substituem a confirmação complementar do APK release otimizado em aparelhos físicos, com codecs/trilhas reais e variantes de Motion Photos de fabricantes. A correção trata riscos concretos encontrados no código; não atribui uma causa única comprovada ao incidente relatado no telefone, cujos dados anteriores não estavam disponíveis.

## Atualização

Atualize por cima da release anterior para preservar seus dados e preferências. Não desinstale uma instalação existente por conflito de assinatura sem preservar seus dados. Não há publicação na Play Store nem inclusão de chaves/credenciais no Git. Releases e APKs históricos permanecem intactos.
